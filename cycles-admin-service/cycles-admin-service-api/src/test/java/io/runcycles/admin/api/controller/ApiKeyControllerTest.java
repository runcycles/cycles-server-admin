package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.*;
import io.runcycles.admin.model.shared.ErrorCode;
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

@WebMvcTest(ApiKeyController.class)
class ApiKeyControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void createApiKey_returns201() throws Exception {
        ApiKeyCreateResponse response = ApiKeyCreateResponse.builder()
                .keyId("key_123").keySecret("gov_secret").keyPrefix("gov_secret1234")
                .tenantId("t1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"name\":\"My Key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key_id").value("key_123"))
                .andExpect(jsonPath("$.key_secret").value("gov_secret"));
    }

    @Test
    void createApiKey_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Key\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listApiKeys_returns200() throws Exception {
        ApiKey key = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyPrefix("gov_pre")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.list(eq("t1"), any(), any(), anyInt())).thenReturn(List.of(key));

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].key_id").value("key_1"));
    }

    @Test
    void revokeApiKey_returns200() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyPrefix("gov_pre")
                .status(ApiKeyStatus.REVOKED).revokedAt(Instant.now())
                .revokedReason("test").createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.revoke("key_1", "test")).thenReturn(revoked);

        mockMvc.perform(delete("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("reason", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void revokeApiKey_notFound_returns404() throws Exception {
        when(apiKeyRepository.revoke(eq("missing"), any()))
                .thenThrow(GovernanceException.apiKeyNotFound("missing"));

        mockMvc.perform(delete("/v1/admin/api-keys/missing")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void createApiKey_logsAuditEntry() throws Exception {
        ApiKeyCreateResponse response = ApiKeyCreateResponse.builder()
                .keyId("key_123").keySecret("gov_secret").keyPrefix("gov_secret1234")
                .tenantId("t1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"name\":\"My Key\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                "createApiKey".equals(entry.getOperation()) &&
                "t1".equals(entry.getTenantId()) &&
                entry.getStatus() == 201));
    }

    @Test
    void revokeApiKey_logsAuditEntry() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyPrefix("gov_pre")
                .status(ApiKeyStatus.REVOKED).revokedAt(Instant.now())
                .revokedReason("test").createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.revoke("key_1", "test")).thenReturn(revoked);

        mockMvc.perform(delete("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("reason", "test"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "revokeApiKey".equals(entry.getOperation()) &&
                "t1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void listApiKeys_emptyResult_hasMoreFalseAndNoCursor() throws Exception {
        when(apiKeyRepository.list(eq("t1"), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listApiKeys_limitClampedToMax100() throws Exception {
        when(apiKeyRepository.list(eq("t1"), any(), any(), eq(100))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1")
                        .param("limit", "500"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("t1"), any(), any(), eq(100));
    }

    @Test
    void listApiKeys_limitClampedToMin1() throws Exception {
        when(apiKeyRepository.list(eq("t1"), any(), any(), eq(1))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1")
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("t1"), any(), any(), eq(1));
    }

    @Test
    void createApiKey_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        ApiKeyCreateResponse response = ApiKeyCreateResponse.builder()
                .keyId("key_123").keySecret("gov_secret").keyPrefix("gov_secret1234")
                .tenantId("t1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        // No requestId attribute set on request — buildAuditEntry should produce a fallback UUID
        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"name\":\"My Key\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getRequestId() != null &&
                entry.getRequestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") &&
                "createApiKey".equals(entry.getOperation())));
    }

    @Test
    void createApiKey_auditEntry_userAgentIsNullWhenHeaderMissing() throws Exception {
        ApiKeyCreateResponse response = ApiKeyCreateResponse.builder()
                .keyId("key_123").keySecret("gov_secret").keyPrefix("gov_secret1234")
                .tenantId("t1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        // No User-Agent header — buildAuditEntry should produce null userAgent
        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"name\":\"My Key\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getUserAgent() == null &&
                "createApiKey".equals(entry.getOperation())));
    }

    @Test
    void revokeApiKey_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyPrefix("gov_pre")
                .status(ApiKeyStatus.REVOKED).revokedAt(Instant.now())
                .revokedReason("test").createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.revoke("key_1", "test")).thenReturn(revoked);

        mockMvc.perform(delete("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("reason", "test"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                entry.getRequestId() != null &&
                entry.getRequestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") &&
                "revokeApiKey".equals(entry.getOperation())));
    }

    @Test
    void listApiKeys_withStatusFilter_passesToRepository() throws Exception {
        when(apiKeyRepository.list(eq("t1"), eq(ApiKeyStatus.REVOKED), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1")
                        .param("status", "REVOKED"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("t1"), eq(ApiKeyStatus.REVOKED), any(), anyInt());
    }

    @Test
    void listApiKeys_withCursorParam_passesToRepository() throws Exception {
        when(apiKeyRepository.list(eq("t1"), any(), eq("key_abc"), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1")
                        .param("cursor", "key_abc"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("t1"), any(), eq("key_abc"), anyInt());
    }

    @Test
    void revokeApiKey_withoutReason_passesNullReason() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyPrefix("gov_pre")
                .status(ApiKeyStatus.REVOKED).revokedAt(Instant.now())
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.revoke("key_1", null)).thenReturn(revoked);

        mockMvc.perform(delete("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }
}
