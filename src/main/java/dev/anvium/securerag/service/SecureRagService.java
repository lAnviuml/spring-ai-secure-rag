package dev.anvium.securerag.service;

import dev.anvium.securerag.api.DocumentRequest;
import dev.anvium.securerag.api.QueryRequest;
import dev.anvium.securerag.api.QueryResponse;
import dev.anvium.securerag.config.RagProperties;
import dev.anvium.securerag.config.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SecureRagService {
    private static final String ABSTENTION = "I do not have enough authorized evidence to answer that question.";
    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final Counter ingested;
    private final Counter grounded;
    private final Counter abstained;

    public SecureRagService(VectorStore vectorStore, RagProperties properties, MeterRegistry registry) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.ingested = registry.counter("secure_rag_documents_ingested_total");
        this.grounded = registry.counter("secure_rag_queries_total", "outcome", "grounded");
        this.abstained = registry.counter("secure_rag_queries_total", "outcome", "abstained");
    }

    public int ingest(DocumentRequest request) {
        TenantContext.Identity identity = TenantContext.require();
        if (request.content().length() > properties.maxContentLength()) {
            throw new IllegalArgumentException("content exceeds configured maximum");
        }
        Set<String> principals = new LinkedHashSet<>();
        principals.add(identity.principalId());
        if (request.allowedPrincipals() != null) principals.addAll(request.allowedPrincipals());

        List<Document> documents = principals.stream().map(principal -> Document.builder()
                .id(stableId(identity.tenantId(), request.sourceId(), principal))
                .text(request.content())
                .metadata(Map.of(
                        "tenantId", identity.tenantId(),
                        "principalId", principal,
                        "sourceId", request.sourceId(),
                        "title", request.title()))
                .build()).toList();
        vectorStore.add(documents);
        ingested.increment();
        return documents.size();
    }

    public QueryResponse query(QueryRequest request) {
        TenantContext.Identity identity = TenantContext.require();
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        var authorization = filters.and(
                filters.eq("tenantId", identity.tenantId()),
                filters.eq("principalId", identity.principalId())).build();
        int topK = request.topK() == null ? properties.topK() : request.topK();
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(request.question())
                .topK(topK)
                .similarityThreshold(properties.similarityThreshold())
                .filterExpression(authorization)
                .build());

        Map<String, Document> unique = new LinkedHashMap<>();
        for (Document hit : hits) unique.putIfAbsent(hit.getMetadata().get("sourceId").toString(), hit);
        List<QueryResponse.Citation> citations = unique.values().stream().map(this::citation).toList();
        if (citations.isEmpty()) {
            abstained.increment();
            return new QueryResponse(ABSTENTION, false, List.of());
        }
        grounded.increment();
        String answer = citations.stream().map(c -> c.excerpt() + " [" + c.sourceId() + "]")
                .reduce((left, right) -> left + "\n\n" + right).orElse(ABSTENTION);
        return new QueryResponse(answer, true, citations);
    }

    private QueryResponse.Citation citation(Document document) {
        String text = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
        String excerpt = text.length() <= 280 ? text : text.substring(0, 277) + "...";
        return new QueryResponse.Citation(
                document.getMetadata().get("sourceId").toString(),
                document.getMetadata().get("title").toString(),
                excerpt,
                document.getScore() == null ? 0 : document.getScore());
    }

    private String stableId(String tenant, String source, String principal) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenant + "\u0000" + source + "\u0000" + principal).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
