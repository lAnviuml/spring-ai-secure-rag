# Notes de référence — RAG sécurisé avec Spring AI

Recherche vérifiée le **2026-08-01**. Ce document décrit des patterns d’architecture ; il ne copie aucun code tiers.

## Socle recommandé

- **Java 21 LTS** : choix volontairement conservateur et portable. Spring Boot 4.1.0 accepte Java 17 à 26.
- **Spring Boot 4.1.0** et **Spring AI 2.0.0**, versions stables courantes. La documentation Spring AI 2.0 confirme la compatibilité avec Boot 4.0.x et 4.1.x ; importer le BOM `spring-ai-bom:2.0.0` plutôt que versionner chaque module.
- **PostgreSQL + pgvector 0.8.6**, avec migration Flyway/Liquibase versionnée. Ne pas laisser l’auto-création Spring AI gérer le schéma en production (`initialize-schema=false`, valeur par défaut).
- **Testcontainers Java 2.0.5** pour les tests d’intégration locaux.

Éviter snapshots et milestones. Verrouiller les dépendances et l’image PostgreSQL/pgvector (version, idéalement digest), puis laisser Dependabot/Renovate proposer les mises à jour avec CI complète.

## Architecture retenue

### Ingestion séparée du chemin de réponse

Pipeline : contrôle d’accès à l’upload → validation taille/MIME/signature → stockage original immuable → extraction → normalisation → découpage → enrichissement des métadonnées → embeddings → upsert vectoriel → catalogue documentaire.

Spring AI formalise l’ETL en `DocumentReader`, `DocumentTransformer` et `DocumentWriter`; `TokenTextSplitter` préserve les métadonnées des documents sources. Ne jamais construire un `Resource` depuis une URL fournie directement par l’utilisateur : la documentation signale explicitement le risque de sécurité.

Métadonnées minimales par chunk :

- `tenant_id`, `document_id`, `document_version`, `chunk_id` ;
- `source_uri`, `source_title`, `page_start`, `page_end` ;
- `content_sha256`, `ingested_at`, `parser_version` ;
- ACL normalisée (`visibility`, rôles/groupes autorisés), sans données secrètes.

L’idempotence repose sur une clé unique `(tenant_id, document_id, document_version, chunk_id)` et le hash du contenu. Une nouvelle version remplace logiquement l’ancienne dans une transaction applicative ; la suppression physique peut être asynchrone et auditée.

### Retrieval avec ACL obligatoire

`AuthorizedRetriever` dérive `tenant_id`, utilisateur et groupes depuis Spring Security, jamais depuis le prompt ni depuis un filtre client. Il construit lui-même la `filterExpression` Spring AI et l’applique à `VectorStoreDocumentRetriever`, avec `topK` borné et `similarityThreshold` explicite. PgVector prend en charge les filtres de métadonnées portables de Spring AI.

Défense en profondeur : après la recherche vectorielle, chaque résultat passe aussi par `DocumentAuthorizationService`. Un chunk non autorisé est supprimé avant construction du contexte et déclenche une métrique de sécurité. Des tests négatifs inter-tenant sont obligatoires.

### Réponses, citations et abstention

Utiliser `RetrievalAugmentationAdvisor` avec `VectorStoreDocumentRetriever`, mais garder les règles de confiance dans du code déterministe :

1. aucun document autorisé ou score insuffisant → abstention immédiate, sans appel au modèle si possible ;
2. le modèle renvoie une structure typée `answer + citation_ids` ;
3. `CitationValidator` refuse tout identifiant absent du jeu effectivement récupéré ;
4. l’API transforme les identifiants validés en titre/source/pages ;
5. contexte contradictoire ou citation manquante → réponse d’abstention explicite.

Le prompt aide à la formulation mais ne constitue ni l’ACL ni le contrôle des citations.

### Observabilité sûre

Spring AI instrumente les modèles et vector stores avec Micrometer. Conserver latence, erreurs, tokens, nombre de chunks récupérés/écartés et identifiants hachés. Les prompts, réponses, résultats vectoriels et arguments d’outils peuvent contenir des données sensibles : leur export est désactivé par défaut dans Spring AI et doit le rester hors environnement de diagnostic isolé. Ajouter `trace_id`, `tenant_id` pseudonymisé, version du corpus et décision d’abstention, jamais le texte des documents.

## Tests sans clé API

- **Unitaires** : doubles locaux implémentant `ChatModel` et `EmbeddingModel`. L’embedding déterministe produit un vecteur stable à partir d’un corpus connu ; le faux chat renvoie des réponses structurées prédéfinies. Aucun appel réseau.
- **Contrats RAG** : corpus golden minuscule ; pertinence top-k, seuil, citations autorisées, abstention, déduplication et déterminisme.
- **Sécurité** : isolation tenant/groupe, filtre forgé ignoré, citation inventée rejetée, document révoqué inaccessible, URL distante/SSRF refusée, fichier surdimensionné ou mal formé rejeté.
- **Intégration** : PostgreSQL/pgvector via Testcontainers, migrations réelles, index et filtres réels, reprise d’ingestion idempotente.
- **Évaluation** : les `RelevancyEvaluator` et `FactCheckingEvaluator` Spring AI sont utiles dans une suite optionnelle locale/live, mais ils utilisent eux-mêmes un modèle. Ils ne remplacent pas la CI déterministe et sont exclus par défaut (`@Tag("live-model")`).
- **Observabilité** : vérifier que prompts, contenu, ACL et PII ne figurent pas dans logs/spans.

## Arborescence conseillée

```text
pom.xml
compose.yaml
src/main/java/com/example/securerag/
  SecureRagApplication.java
  config/              AiConfig.java, SecurityConfig.java, ObservabilityConfig.java
  ingestion/           IngestionController.java, DocumentIngestionService.java,
                       ParserGateway.java, ChunkingPolicy.java
  catalog/             DocumentRecord.java, DocumentCatalogRepository.java
  retrieval/           AuthorizedRetriever.java, RetrievalPolicy.java
  answer/              AnswerService.java, AnswerWithCitations.java,
                       CitationValidator.java, AbstentionPolicy.java
  security/            CurrentPrincipal.java, DocumentAuthorizationService.java
src/main/resources/
  application.yml
  db/migration/        V1__catalog_and_vector_schema.sql
src/test/java/com/example/securerag/
  unit/                ingestion/, retrieval/, answer/, security/
  integration/         PgVectorRetrievalIT.java, AuthorizedRetrievalIT.java
  support/             FakeChatModel.java, DeterministicEmbeddingModel.java,
                       TestDocuments.java
docs/
```

## Risques et retour arrière

- Spring AI 2.0 est une version majeure avec changements incompatibles depuis 1.1.x : le POC doit rester sur un commit/tag connu et le BOM facilite le rollback cohérent.
- Une erreur de filtre ACL est une fuite de données : activation progressive, corpus synthétique multi-tenant et tests de non-régression avant toute donnée réelle.
- Changer de modèle d’embedding impose un nouvel index/version de corpus. Construire le nouvel index en parallèle, basculer un alias, puis conserver l’ancien jusqu’à validation.
- Les parseurs de documents traitent des entrées hostiles : limites mémoire/temps, formats autorisés, quarantaine et mises à jour de sécurité sont obligatoires.

## Sources primaires et licences

- [Spring AI 2.0 — démarrage et compatibilité Boot](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI 2.0.0 — release officielle](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)
- [Spring Boot 4.1.0 — prérequis système](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI — RAG et filtres de métadonnées](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI — pipeline ETL et avertissement sur les URL utilisateur](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)
- [Spring AI — PgVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
- [Spring AI — observabilité](https://docs.spring.io/spring-ai/reference/observability/index.html)
- [Spring AI — évaluation](https://docs.spring.io/spring-ai/reference/api/testing.html)
- [Spring AI — notes de migration 2.0](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- [Spring AI, licence Apache-2.0](https://github.com/spring-projects/spring-ai/blob/main/LICENSE.txt)
- [pgvector, licence PostgreSQL](https://github.com/pgvector/pgvector/blob/master/LICENSE)
- [Testcontainers Java, licence MIT](https://github.com/testcontainers/testcontainers-java/blob/main/LICENSE)
- [Exemples Spring AI officiels](https://github.com/spring-projects/spring-ai-examples) : utiles pour observer la composition des modules, mais aucune licence racine n’était déclarée via GitHub au jour de la recherche ; ne pas réutiliser leur code sans clarification. Les patterns ci-dessus sont reformulés depuis la documentation officielle.
