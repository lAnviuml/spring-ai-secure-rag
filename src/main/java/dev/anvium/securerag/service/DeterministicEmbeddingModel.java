package dev.anvium.securerag.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A local feature-hashing model for reproducible tests and offline demos. */
public final class DeterministicEmbeddingModel implements EmbeddingModel {
    private final int dimensions;

    public DeterministicEmbeddingModel(int dimensions) {
        if (dimensions < 64) throw new IllegalArgumentException("dimensions must be at least 64");
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(vectorize(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorize(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() { return dimensions; }

    private float[] vectorize(String text) {
        float[] vector = new float[dimensions];
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() < 2) continue;
            int hash = token.hashCode();
            int index = Math.floorMod(hash, dimensions);
            vector[index] += (hash & 1) == 0 ? 1.0f : -1.0f;
        }
        double norm = 0;
        for (float value : vector) norm += value * value;
        if (norm == 0) vector[0] = 1.0f;
        else {
            float scale = (float) Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) vector[i] /= scale;
        }
        return vector;
    }
}
