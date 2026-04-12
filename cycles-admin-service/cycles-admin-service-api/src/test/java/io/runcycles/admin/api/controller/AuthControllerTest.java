package io.runcycles.admin.api.controller;

import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(MetricsTestConfiguration.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void validate_validKey_returns200WithValid() throws Exception {
        when(apiKeyRepository.validate("gov_valid_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("balances:read"))
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"gov_valid_key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.tenant_id").value("tenant-1"))
                .andExpect(jsonPath("$.key_id").value("key_1"));
    }

    @Test
    void validate_invalidKey_returns200WithInvalid() throws Exception {
        when(apiKeyRepository.validate("invalid")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("KEY_NOT_FOUND")
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"invalid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("KEY_NOT_FOUND"));
    }

    @Test
    void validate_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validate_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validate_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Introspect endpoint (v0.1.25.1) ---

    @Test
    void introspect_withAdminKey_returnsCapabilities() throws Exception {
        mockMvc.perform(get("/v1/auth/introspect")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.auth_type").value("admin"))
                .andExpect(jsonPath("$.permissions[0]").value("*"))
                .andExpect(jsonPath("$.capabilities.view_overview").value(true))
                .andExpect(jsonPath("$.capabilities.view_budgets").value(true))
                .andExpect(jsonPath("$.capabilities.view_events").value(true))
                .andExpect(jsonPath("$.capabilities.view_webhooks").value(true))
                .andExpect(jsonPath("$.capabilities.view_audit").value(true))
                .andExpect(jsonPath("$.capabilities.view_tenants").value(true))
                .andExpect(jsonPath("$.capabilities.view_api_keys").value(true))
                .andExpect(jsonPath("$.capabilities.view_policies").value(true));
    }

    @Test
    void introspect_withoutAdminKey_returns401() throws Exception {
        mockMvc.perform(get("/v1/auth/introspect"))
                .andExpect(status().isUnauthorized());
    }

    // --- KEY_EXPIRED / KEY_REVOKED validation ---

    @Test
    void validate_expiredKey_returns200WithReasonKeyExpired() throws Exception {
        when(apiKeyRepository.validate("expired_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("tenant-1").keyId("key_exp")
                        .reason("KEY_EXPIRED")
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"expired_key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("KEY_EXPIRED"))
                .andExpect(jsonPath("$.key_id").value("key_exp"));
    }

    @Test
    void validate_revokedKey_returns200WithReasonKeyRevoked() throws Exception {
        when(apiKeyRepository.validate("revoked_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("tenant-1").keyId("key_rev")
                        .reason("KEY_REVOKED")
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"revoked_key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("KEY_REVOKED"))
                .andExpect(jsonPath("$.key_id").value("key_rev"));
    }

    @Test
    void validate_suspendedTenant_returns200WithReasonTenantSuspended() throws Exception {
        when(apiKeyRepository.validate("suspended_tenant_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("t_sus").keyId("key_st")
                        .reason("TENANT_SUSPENDED")
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"suspended_tenant_key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("TENANT_SUSPENDED"));
    }

    @Test
    void validate_closedTenant_returns200WithReasonTenantClosed() throws Exception {
        when(apiKeyRepository.validate("closed_tenant_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("t_cls").keyId("key_ct")
                        .reason("TENANT_CLOSED")
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"closed_tenant_key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("TENANT_CLOSED"));
    }
}
