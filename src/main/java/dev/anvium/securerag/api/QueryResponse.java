package dev.anvium.securerag.api;

import java.util.List;

public record QueryResponse(String answer, boolean grounded, List<Citation> citations) {
    public record Citation(String sourceId, String title, String excerpt, double score) { }
}
