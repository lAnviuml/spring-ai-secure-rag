package dev.anvium.securerag.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QueryRequest(@NotBlank @Size(max = 1000) String question, @Min(1) @Max(20) Integer topK) { }
