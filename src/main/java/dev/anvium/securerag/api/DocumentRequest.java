package dev.anvium.securerag.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record DocumentRequest(
        @NotBlank @Size(max = 128) String sourceId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 20000) String content,
        @Size(max = 50) Set<@NotBlank @Size(max = 64)
                @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}") String> allowedPrincipals) { }
