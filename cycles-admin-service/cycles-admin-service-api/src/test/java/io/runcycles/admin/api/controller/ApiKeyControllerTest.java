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
import org.springframework.context.annotation.Import;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import io.runcycles.admin.api.contract.ContractValidationConfig;
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
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class ApiKeyControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private io.runcycles.admin.api.service.EventService eventService;
    @MockitoBean private JedisPool jedisPool;
    @MockitoBean private io.runcycles.admin.api.service.TerminalOwnerMutationGuard mutationGuard;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void createApiKey_returns201() throws Exception {
        ApiKeyCreateResponse response = ApiKeyCreateResponse.builder()
                .keyId("key_123").keySecret("gov_secret").keyPrefix("gov_secret1234")
                .tenantId("tenant-1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"tenant-1\",\"name\":\"My Key\"}"))
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
                .keyId("key_1").tenantId("tenant-1").keyPrefix("gov_pre")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.list(eq("tenant-1"), any(), any(), anyInt(), any(), any())).thenReturn(List.of(key));

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].key_id").value("key_1"));
    }

    @Test
    void revokeApiKey_returns200() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("gov_pre")
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

    // v0.1.25.36 — Cascade Rule 2: owner-tenant guard on create.
    @Test
    void createApiKey_closedTenant_returns409_tenantClosed() throws Exception {
        doThrow(new GovernanceException(ErrorCode.TENANT_CLOSED,
            "Tenant tenant-1 is closed; owned objects are read-only", 409))
            .when(mutationGuard).assertTenantOpen("tenant-1");

        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"tenant-1\",\"name\":\"Blocked\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TENANT_CLOSED"));

        verify(apiKeyRepository, never()).create(any());
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
                .tenantId("tenant-1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"tenant-1\",\"name\":\"My Key\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                "createApiKey".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                "key_123".equals(entry.getKeyId()) &&
                entry.getStatus() == 201));
    }

    @Test
    void revokeApiKey_logsAuditEntry() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("gov_pre")
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
                "tenant-1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void listApiKeys_emptyResult_hasMoreFalseAndNoCursor() throws Exception {
        when(apiKeyRepository.list(eq("tenant-1"), any(), any(), anyInt(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listApiKeys_limitClampedToMax100() throws Exception {
        when(apiKeyRepository.list(eq("tenant-1"), any(), any(), eq(101), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .param("limit", "500"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("tenant-1"), any(), any(), eq(101), any(), any());
    }

    @Test
    void listApiKeys_limitClampedToMin1() throws Exception {
        when(apiKeyRepository.list(eq("tenant-1"), any(), any(), eq(2), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("tenant-1"), any(), any(), eq(2), any(), any());
    }

    @Test
    void createApiKey_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        ApiKeyCreateResponse response = ApiKeyCreateResponse.builder()
                .keyId("key_123").keySecret("gov_secret").keyPrefix("gov_secret1234")
                .tenantId("tenant-1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        // No requestId attribute set on request — buildAuditEntry should produce a fallback UUID
        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"tenant-1\",\"name\":\"My Key\"}"))
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
                .tenantId("tenant-1").permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        // No User-Agent header — buildAuditEntry should produce null userAgent
        mockMvc.perform(post("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"tenant-1\",\"name\":\"My Key\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getUserAgent() == null &&
                "createApiKey".equals(entry.getOperation())));
    }

    @Test
    void revokeApiKey_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("gov_pre")
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
        when(apiKeyRepository.list(eq("tenant-1"), eq(ApiKeyStatus.REVOKED), any(), anyInt(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .param("status", "REVOKED"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("tenant-1"), eq(ApiKeyStatus.REVOKED), any(), anyInt(), any(), any());
    }

    @Test
    void listApiKeys_withCursorParam_passesToRepository() throws Exception {
        when(apiKeyRepository.list(eq("tenant-1"), any(), eq("key_abc"), anyInt(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .param("cursor", "key_abc"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).list(eq("tenant-1"), any(), eq("key_abc"), anyInt(), any(), any());
    }

    @Test
    void listApiKeys_resultCountExceedsLimit_hasMoreTrueWithCursor() throws Exception {
        ApiKey k1 = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("gov_pre1")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        ApiKey k2 = ApiKey.builder()
                .keyId("key_2").tenantId("tenant-1").keyPrefix("gov_pre2")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        ApiKey k3 = ApiKey.builder()
                .keyId("key_3").tenantId("tenant-1").keyPrefix("gov_pre3")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.list(eq("tenant-1"), any(), any(), eq(3), any(), any()))
                .thenReturn(List.of(k1, k2, k3));

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("key_2"));
    }

    @Test
    void revokeApiKey_withoutReason_passesNullReason() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("gov_pre")
                .status(ApiKeyStatus.REVOKED).revokedAt(Instant.now())
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.revoke("key_1", null)).thenReturn(revoked);

        mockMvc.perform(delete("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    // ========== PATCH /v1/admin/api-keys/{key_id} ==========

    @Test
    void updateApiKey_returns200() throws Exception {
        ApiKey updated = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .name("Updated Key").description("new desc")
                .permissions(List.of("budgets:read", "budgets:write"))
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.update(eq("key_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Key\",\"permissions\":[\"budgets:read\",\"budgets:write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Key"))
                .andExpect(jsonPath("$.permissions[0]").value("budgets:read"));
    }

    @Test
    void updateApiKey_notFound_returns404() throws Exception {
        when(apiKeyRepository.update(eq("missing"), any()))
                .thenThrow(GovernanceException.apiKeyNotFound("missing"));

        mockMvc.perform(patch("/v1/admin/api-keys/missing")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updateApiKey_revokedKey_returns409() throws Exception {
        when(apiKeyRepository.update(eq("key_rev"), any()))
                .thenThrow(new GovernanceException(ErrorCode.INVALID_REQUEST,
                        "Cannot modify a REVOKED key", 409));

        mockMvc.perform(patch("/v1/admin/api-keys/key_rev")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateApiKey_noAdminKey_returns401() throws Exception {
        mockMvc.perform(patch("/v1/admin/api-keys/key_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateApiKey_logsAuditEntry() throws Exception {
        ApiKey updated = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .name("Updated").status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.update(eq("key_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updateApiKey".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void updateApiKey_permissionsChanged_emitsEvent() throws Exception {
        // Mock old key with different permissions
        ApiKey oldKey = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .permissions(List.of("balances:read"))
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        redis.clients.jedis.Jedis jedisMock = mock(redis.clients.jedis.Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedisMock);
        when(jedisMock.get("apikey:key_1")).thenReturn(objectMapper.writeValueAsString(oldKey));

        ApiKey updated = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .permissions(List.of("budgets:read", "budgets:write"))
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.update(eq("key_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"budgets:read\",\"budgets:write\"]}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(io.runcycles.admin.model.event.EventType.API_KEY_PERMISSIONS_CHANGED),
                eq("tenant-1"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateApiKey_nameOnlyChange_doesNotEmitPermissionsEvent() throws Exception {
        ApiKey updated = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .name("New Name").status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.update(eq("key_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/api-keys/key_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isOk());

        verify(eventService, never()).emit(eq(io.runcycles.admin.model.event.EventType.API_KEY_PERMISSIONS_CHANGED),
                any(), any(), any(), any(), any(), any(), any());
    }

    // --- v0.1.25.22 cross-tenant listApiKeys ---

    @Test
    void listApiKeys_noTenantId_dispatchesCrossTenant() throws Exception {
        ApiKey keyA = ApiKey.builder()
                .keyId("key_a").tenantId("tenant-a").keyPrefix("cyc_live_aa")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        ApiKey keyB = ApiKey.builder()
                .keyId("key_b").tenantId("tenant-b").keyPrefix("cyc_live_bb")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400)).build();
        when(apiKeyRepository.listAllTenants(any(), any(), anyInt(), any(), any()))
                .thenReturn(List.of(keyA, keyB));

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].key_id").value("key_a"))
                .andExpect(jsonPath("$.keys[1].key_id").value("key_b"));

        verify(apiKeyRepository).listAllTenants(isNull(), isNull(), eq(51), any(), any());
        verify(apiKeyRepository, never()).list(anyString(), any(), any(), anyInt());
    }

    @Test
    void listApiKeys_crossTenant_nextCursorIsTenantKeyComposite() throws Exception {
        // 51 results prove another page exists; the 51st look-ahead row is not returned.
        List<ApiKey> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fullPage.add(ApiKey.builder()
                    .keyId("key_" + i).tenantId("tenant-" + (i / 10))
                    .keyPrefix("cyc_live_" + i)
                    .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(86400)).build());
        }
        when(apiKeyRepository.listAllTenants(any(), any(), anyInt(), any(), any())).thenReturn(fullPage);

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("tenant-4|key_49"));
    }

    @Test
    void listApiKeys_perTenant_nextCursorIsBareKeyId() throws Exception {
        List<ApiKey> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fullPage.add(ApiKey.builder()
                    .keyId("key_" + i).tenantId("tenant-1").keyPrefix("cyc_live_" + i)
                    .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(86400)).build());
        }
        when(apiKeyRepository.list(eq("tenant-1"), any(), any(), anyInt(), any(), any())).thenReturn(fullPage);

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("key_49"));

        verify(apiKeyRepository, never()).listAllTenants(any(), any(), anyInt());
    }

    @Test
    void listApiKeys_crossTenant_passesStatusAndCursor() throws Exception {
        when(apiKeyRepository.listAllTenants(eq(ApiKeyStatus.REVOKED), eq("tenant-9|key_abc"), eq(26), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status", "REVOKED")
                        .param("cursor", "tenant-9|key_abc")
                        .param("limit", "25"))
                .andExpect(status().isOk());

        verify(apiKeyRepository).listAllTenants(eq(ApiKeyStatus.REVOKED), eq("tenant-9|key_abc"), eq(26), any(), any());
    }

    // --- v0.1.25.24 sort support ---

    @org.junit.jupiter.api.Test
    void listApiKeys_withValidSortByAndSortDir_delegatesToRepository() throws Exception {
        when(apiKeyRepository.listAllTenants(any(), any(), anyInt(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "name")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<io.runcycles.admin.model.shared.SortSpec> captor =
                org.mockito.ArgumentCaptor.forClass(io.runcycles.admin.model.shared.SortSpec.class);
        verify(apiKeyRepository).listAllTenants(any(), any(), anyInt(), captor.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals("name", captor.getValue().field());
        org.junit.jupiter.api.Assertions.assertEquals(
                io.runcycles.admin.model.shared.SortDirection.ASC, captor.getValue().direction());
    }

    @org.junit.jupiter.api.Test
    void listApiKeys_defaultsToCreatedAtDescending() throws Exception {
        when(apiKeyRepository.listAllTenants(any(), any(), anyInt(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<io.runcycles.admin.model.shared.SortSpec> captor =
                org.mockito.ArgumentCaptor.forClass(io.runcycles.admin.model.shared.SortSpec.class);
        verify(apiKeyRepository).listAllTenants(any(), any(), anyInt(), captor.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals("created_at", captor.getValue().field());
        org.junit.jupiter.api.Assertions.assertEquals(
                io.runcycles.admin.model.shared.SortDirection.DESC, captor.getValue().direction());
    }

    @org.junit.jupiter.api.Test
    void listApiKeys_unknownSortByReturns400() throws Exception {
        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @org.junit.jupiter.api.Test
    void listApiKeys_unknownSortDirReturns400() throws Exception {
        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_dir", "sideways"))
                .andExpect(status().isBadRequest());
    }

    @org.junit.jupiter.api.Test
    void listApiKeys_acceptsAllWhitelistedSortFields() throws Exception {
        when(apiKeyRepository.listAllTenants(any(), any(), anyInt(), any(), any())).thenReturn(List.of());

        for (String field : List.of("key_id", "name", "tenant_id", "status", "created_at", "expires_at")) {
            mockMvc.perform(get("/v1/admin/api-keys")
                            .header("X-Admin-API-Key", ADMIN_KEY)
                            .param("sort_by", field))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void listApiKeys_searchOver128Chars_returns400() throws Exception {
        String over = "x".repeat(129);
        mockMvc.perform(get("/v1/admin/api-keys")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("search", over))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(apiKeyRepository, never()).list(any(), any(), any(), anyInt(), any(), any());
        verify(apiKeyRepository, never()).listAllTenants(any(), any(), anyInt(), any(), any());
    }

    @Test
    void createApiKeyWithExplicitPermissionsIncludesPermissionAuditBranch() throws Exception {
        ApiKeyCreateResponse response = ApiKeyCreateResponse.builder().keyId("key-perms")
            .keySecret("secret").keyPrefix("prefix").tenantId("tenant-1")
            .permissions(List.of("budgets:read")).createdAt(Instant.now()).build();
        when(apiKeyRepository.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/api-keys").header("X-Admin-API-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\"tenant-1\",\"name\":\"Permitted\",\"permissions\":[\"budgets:read\"]}"))
            .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry -> entry.getMetadata() != null
            && entry.getMetadata().containsKey("permissions")));
    }

    @Test
    void updateApiKeyUnchangedPermissionsAndScopeDoesNotEmit() throws Exception {
        ApiKey oldKey = ApiKey.builder().keyId("key-same").tenantId("tenant-1")
            .permissions(List.of("budgets:read")).scopeFilter(List.of("tenant:tenant-1"))
            .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(apiKeyRepository.findByIdOrNull("key-same")).thenReturn(oldKey);
        when(apiKeyRepository.update(eq("key-same"), any())).thenReturn(oldKey);

        mockMvc.perform(patch("/v1/admin/api-keys/key-same").header("X-Admin-API-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"budgets:read\"],\"scope_filter\":[\"tenant:tenant-1\"]}"))
            .andExpect(status().isOk());

        verify(eventService, never()).emit(eq(io.runcycles.admin.model.event.EventType.API_KEY_PERMISSIONS_CHANGED),
            any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateApiKeyMissingSnapshotTreatsPermissionAndScopePresenceAsChange() throws Exception {
        redis.clients.jedis.Jedis jedis = mock(redis.clients.jedis.Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("apikey:key-nosnapshot")).thenReturn(null);
        ApiKey updated = ApiKey.builder().keyId("key-nosnapshot").tenantId("tenant-1")
            .permissions(List.of("budgets:read")).scopeFilter(List.of("tenant:tenant-1"))
            .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(apiKeyRepository.update(eq("key-nosnapshot"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/api-keys/key-nosnapshot").header("X-Admin-API-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"budgets:read\"],\"scope_filter\":[\"tenant:tenant-1\"]}"))
            .andExpect(status().isOk());

        verify(eventService).emit(eq(io.runcycles.admin.model.event.EventType.API_KEY_PERMISSIONS_CHANGED),
            eq("tenant-1"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void blankTenantListUsesCrossTenantPath() throws Exception {
        when(apiKeyRepository.listAllTenants(any(), any(), anyInt(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/api-keys").header("X-Admin-API-Key", ADMIN_KEY)
                .param("tenant_id", " "))
            .andExpect(status().isOk());

        verify(apiKeyRepository).listAllTenants(any(), any(), anyInt(), any(), any());
    }

    @Test
    void emptyUpdateUsesNullAuditMetadata() throws Exception {
        ApiKey unchanged = ApiKey.builder().keyId("key-empty").tenantId("tenant-1")
            .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(apiKeyRepository.update(eq("key-empty"), any())).thenReturn(unchanged);

        mockMvc.perform(patch("/v1/admin/api-keys/key-empty").header("X-Admin-API-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry -> entry.getMetadata() == null));
    }

    @Test
    void scopeOnlyChangeEmitsPermissionsChangedEvent() throws Exception {
        ApiKey oldKey = ApiKey.builder().keyId("key-scope").tenantId("tenant-1")
            .scopeFilter(List.of("tenant:old")).status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey updated = ApiKey.builder().keyId("key-scope").tenantId("tenant-1")
            .scopeFilter(List.of("tenant:new")).status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        redis.clients.jedis.Jedis jedis = mock(redis.clients.jedis.Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("apikey:key-scope")).thenReturn(objectMapper.writeValueAsString(oldKey));
        when(apiKeyRepository.update(eq("key-scope"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/api-keys/key-scope").header("X-Admin-API-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope_filter\":[\"tenant:new\"]}"))
            .andExpect(status().isOk());

        verify(eventService).emit(eq(io.runcycles.admin.model.event.EventType.API_KEY_PERMISSIONS_CHANGED),
            eq("tenant-1"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void revokeOwnerResolutionFailsClosedForMalformedRows() throws Exception {
        ApiKey stored = ApiKey.builder().keyId("present").tenantId("tenant-1").build();
        when(apiKeyRepository.findByIdOrNull("missing")).thenReturn(null);
        when(apiKeyRepository.findByIdOrNull("present")).thenReturn(stored);
        when(apiKeyRepository.findByIdOrNull("malformed"))
            .thenThrow(new IllegalStateException("Malformed API-key storage row"));
        when(apiKeyRepository.revoke(anyString(), any())).thenAnswer(invocation ->
            ApiKey.builder().keyId(invocation.getArgument(0)).tenantId("tenant-1")
                .status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build());

        for (String keyId : List.of("missing", "present")) {
            mockMvc.perform(delete("/v1/admin/api-keys/" + keyId)
                    .header("X-Admin-API-Key", ADMIN_KEY).param("reason", "cleanup"))
                .andExpect(status().isOk());
        }
        mockMvc.perform(delete("/v1/admin/api-keys/malformed")
                .header("X-Admin-API-Key", ADMIN_KEY).param("reason", "cleanup"))
            .andExpect(status().isInternalServerError());

        verify(mutationGuard).assertTenantOpen("tenant-1");
        verify(mutationGuard).assertTenantOpen(isNull());
        verify(apiKeyRepository, never()).revoke(eq("malformed"), any());
    }
}
