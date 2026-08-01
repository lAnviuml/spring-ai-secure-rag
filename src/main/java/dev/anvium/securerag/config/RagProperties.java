package dev.anvium.securerag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "secure-rag.retrieval")
public record RagProperties(int topK, double similarityThreshold, int maxContentLength) {
    public RagProperties {
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("topK must be between 1 and 20");
        if (similarityThreshold < 0 || similarityThreshold > 1) throw new IllegalArgumentException("similarityThreshold must be between 0 and 1");
        if (maxContentLength < 100) throw new IllegalArgumentException("maxContentLength must be at least 100");
    }
}
