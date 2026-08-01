package dev.anvium.securerag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecureRagApplicationTests {
    @Autowired MockMvc mvc;

    @Test
    void retrievesAuthorizedEvidenceWithCitation() throws Exception {
        ingest("acme", "alice", "runbook-42", "Payments Recovery", "Restart the payments worker with the blue deployment procedure.");

        mvc.perform(post("/api/v1/queries")
                        .header("X-Tenant-Id", "acme").header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How do I restart the payments worker?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.citations", hasSize(1)))
                .andExpect(jsonPath("$.citations[0].sourceId").value("runbook-42"))
                .andExpect(jsonPath("$.answer", containsString("[runbook-42]")));
    }

    @Test
    void neverLeaksAcrossTenants() throws Exception {
        ingest("tenant-a", "alice", "secret-1", "Private", "The launch authorization phrase is amber falcon.");

        mvc.perform(post("/api/v1/queries")
                        .header("X-Tenant-Id", "tenant-b").header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is the launch authorization phrase?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.citations", hasSize(0)))
                .andExpect(jsonPath("$.answer").value("I do not have enough authorized evidence to answer that question."));
    }

    @Test
    void neverLeaksAcrossPrincipals() throws Exception {
        ingest("private-co", "alice", "board-1", "Board notes", "The confidential project codename is aurora maple.");

        mvc.perform(post("/api/v1/queries")
                        .header("X-Tenant-Id", "private-co").header("X-Principal-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is the confidential project codename?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.citations", hasSize(0)));
    }

    @Test
    void rejectsMissingIdentityAndUnknownFields() throws Exception {
        mvc.perform(post("/api/v1/queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hello\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/queries")
                        .header("X-Tenant-Id", "acme").header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hello\",\"unsafeFilter\":\"tenantId=*\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposesHealthWithoutTenantHeaders() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private void ingest(String tenant, String principal, String sourceId, String title, String content) throws Exception {
        String body = """
                {"sourceId":"%s","title":"%s","content":"%s","allowedPrincipals":[]}
                """.formatted(sourceId, title, content);
        mvc.perform(post("/api/v1/documents")
                        .header("X-Tenant-Id", tenant).header("X-Principal-Id", principal)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
