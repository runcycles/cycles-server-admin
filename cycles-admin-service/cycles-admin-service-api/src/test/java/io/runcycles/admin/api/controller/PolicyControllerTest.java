package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.PolicyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.policy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PolicyController.class)
class PolicyControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private PolicyRepository policyRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("balances:read")).build());
    }

    @Test
    void createPolicy_returns201() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("org/*").name("Rate Limit")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.create(eq("t1"), any())).thenReturn(policy);

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rate Limit\",\"scope_pattern\":\"org/*\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policy_id").value("pol_123"))
                .andExpect(jsonPath("$.scope_pattern").value("org/*"));
    }

    @Test
    void createPolicy_missingName_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_pattern\":\"org/*\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createPolicy_noApiKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"scope_pattern\":\"*\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listPolicies_returns200() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_1").scopePattern("org/*").name("P1")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.list(eq("t1"), any(), any(), any(), anyInt())).thenReturn(List.of(policy));

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies").isArray())
                .andExpect(jsonPath("$.policies[0].policy_id").value("pol_1"));
    }

    @Test
    void listPolicies_withFilters() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("t1"), eq("org/*"), eq(PolicyStatus.ACTIVE), any(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("scope_pattern", "org/*")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies").isEmpty());
    }
}
