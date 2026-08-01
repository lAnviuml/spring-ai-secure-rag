package dev.anvium.securerag.api;

import dev.anvium.securerag.service.SecureRagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class RagController {
    private final SecureRagService service;

    public RagController(SecureRagService service) { this.service = service; }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> ingest(@Valid @RequestBody DocumentRequest request) {
        return Map.of("status", "indexed", "authorizationCopies", service.ingest(request));
    }

    @PostMapping("/queries")
    QueryResponse query(@Valid @RequestBody QueryRequest request) {
        return service.query(request);
    }
}
