package io.runcycles.admin.api.controller;

import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.auth.Capabilities;
import io.runcycles.admin.model.auth.ApiKeyValidationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import io.runcycles.admin.api.contract.ContractValidationConfig;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthController authController;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private EventService eventService;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void validate_invalidKey_withoutRequestId_emitsFailureEvent() {
        when(apiKeyRepository.validate("bad-direct")).thenReturn(
            ApiKeyValidationResponse.builder().valid(false).reason("KEY_NOT_FOUND").build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        authController.validate(
            new ApiKeyValidationRequest("bad-direct"), request);

        org.mockito.Mockito.verify(eventService).emit(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void deriveTenantCapabilities_nullPermissions_deniesTenantPlaneCapabilities() {
        Capabilities capabilities = AuthController.deriveTenantCapabilities(null);

        assertThat(capabilities.isViewBudgets()).isFalse();
        assertThat(capabilities.getManageReservations()).isFalse();
    }

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

    // --- Introspect endpoint (v0.1.25.1 admin-only; v0.1.25.19 dual-auth) ---

    @Test
    void introspect_withAdminKey_returnsAdminShape() throws Exception {
        // Spec v0.1.25.15 yaml:3157-3159: admin auth SHOULD return all caps true,
        // plus tenant_id and scope_filter MUST be absent.
        mockMvc.perform(get("/v1/auth/introspect")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.auth_type").value("admin"))
                .andExpect(jsonPath("$.permissions[0]").value("*"))
                // All 8 required view_* caps true.
                .andExpect(jsonPath("$.capabilities.view_overview").value(true))
                .andExpect(jsonPath("$.capabilities.view_budgets").value(true))
                .andExpect(jsonPath("$.capabilities.view_events").value(true))
                .andExpect(jsonPath("$.capabilities.view_webhooks").value(true))
                .andExpect(jsonPath("$.capabilities.view_audit").value(true))
                .andExpect(jsonPath("$.capabilities.view_tenants").value(true))
                .andExpect(jsonPath("$.capabilities.view_api_keys").value(true))
                .andExpect(jsonPath("$.capabilities.view_policies").value(true))
                // All 7 optional caps also true under admin auth.
                .andExpect(jsonPath("$.capabilities.view_reservations").value(true))
                .andExpect(jsonPath("$.capabilities.manage_budgets").value(true))
                .andExpect(jsonPath("$.capabilities.manage_policies").value(true))
                .andExpect(jsonPath("$.capabilities.manage_webhooks").value(true))
                .andExpect(jsonPath("$.capabilities.manage_tenants").value(true))
                .andExpect(jsonPath("$.capabilities.manage_api_keys").value(true))
                .andExpect(jsonPath("$.capabilities.manage_reservations").value(true))
                // tenant_id and scope_filter MUST NOT appear under admin auth.
                .andExpect(jsonPath("$.tenant_id").doesNotExist())
                .andExpect(jsonPath("$.scope_filter").doesNotExist());
    }

    @Test
    void introspect_withWrongMethod_returns405ErrorEnvelopeAndAllowHeader() throws Exception {
        mockMvc.perform(post("/v1/auth/introspect")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("POST")));
    }

    @Test
    void introspect_withoutAnyKey_returns401() throws Exception {
        // v0.1.25.19: the endpoint is dual-auth but still rejects missing
        // credentials. Without either header, the interceptor routes through
        // validateApiKey (missing X-Cycles-API-Key) → 401 per spec yaml:4729-4734.
        mockMvc.perform(get("/v1/auth/introspect"))
                .andExpect(status().isUnauthorized());
    }

    // --- v0.1.25.19: tenant-key dual-auth on /v1/auth/introspect (spec v0.1.25.15) ---

    @Test
    void introspect_withTenantKey_returnsTenantShape() throws Exception {
        when(apiKeyRepository.validate("tenant_key_1")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("budgets:read", "webhooks:write"))
                        .build());

        mockMvc.perform(get("/v1/auth/introspect")
                        .header("X-Cycles-API-Key", "tenant_key_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.auth_type").value("tenant"))
                .andExpect(jsonPath("$.tenant_id").value("tenant-1"))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions[0]").value("budgets:read"))
                .andExpect(jsonPath("$.permissions[1]").value("webhooks:write"))
                // Admin-plane capabilities MUST be false under tenant auth
                // (spec yaml:3138-3147 — NORMATIVE regardless of admin:* perms).
                .andExpect(jsonPath("$.capabilities.view_overview").value(false))
                .andExpect(jsonPath("$.capabilities.view_tenants").value(false))
                .andExpect(jsonPath("$.capabilities.view_api_keys").value(false))
                .andExpect(jsonPath("$.capabilities.view_audit").value(false))
                .andExpect(jsonPath("$.capabilities.manage_tenants").value(false))
                .andExpect(jsonPath("$.capabilities.manage_api_keys").value(false))
                // Tenant-plane derived per the table (yaml:3115-3135).
                .andExpect(jsonPath("$.capabilities.view_budgets").value(true))
                .andExpect(jsonPath("$.capabilities.view_webhooks").value(false))
                .andExpect(jsonPath("$.capabilities.manage_webhooks").value(true))
                .andExpect(jsonPath("$.capabilities.manage_budgets").value(false))
                .andExpect(jsonPath("$.capabilities.view_events").value(false))
                .andExpect(jsonPath("$.capabilities.view_policies").value(false))
                // scope_filter absent when empty/unset.
                .andExpect(jsonPath("$.scope_filter").doesNotExist());
    }

    @Test
    void introspect_withTenantKey_scopeFilterPresent_includesField() throws Exception {
        when(apiKeyRepository.validate("tenant_key_scoped")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-9").keyId("key_9")
                        .permissions(List.of("budgets:read"))
                        .scopeFilter(List.of("tenant:tenant-9/workspace:team-a"))
                        .build());

        mockMvc.perform(get("/v1/auth/introspect")
                        .header("X-Cycles-API-Key", "tenant_key_scoped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth_type").value("tenant"))
                .andExpect(jsonPath("$.tenant_id").value("tenant-9"))
                .andExpect(jsonPath("$.scope_filter[0]").value("tenant:tenant-9/workspace:team-a"));
    }

    @Test
    void introspect_withTenantKey_scopeFilterEmpty_omitsField() throws Exception {
        when(apiKeyRepository.validate("tenant_key_noscope")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("events:read"))
                        .scopeFilter(List.of())
                        .build());

        mockMvc.perform(get("/v1/auth/introspect")
                        .header("X-Cycles-API-Key", "tenant_key_noscope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth_type").value("tenant"))
                .andExpect(jsonPath("$.scope_filter").doesNotExist());
    }

    @Test
    void introspect_tenantKeyWithAdminRead_adminPlaneCapsStillFalse() throws Exception {
        // NORMATIVE guard (spec yaml:3138-3147): admin:read on a tenant key
        // MUST NOT elevate the admin-plane dashboard caps. Prevents accidental
        // admin-UI access via legacy admin-permission tenant keys.
        when(apiKeyRepository.validate("legacy_tenant_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-legacy").keyId("key_lg")
                        .permissions(List.of("admin:read"))
                        .build());

        mockMvc.perform(get("/v1/auth/introspect")
                        .header("X-Cycles-API-Key", "legacy_tenant_key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth_type").value("tenant"))
                // admin:read opens the tenant-plane read caps (table).
                .andExpect(jsonPath("$.capabilities.view_budgets").value(true))
                .andExpect(jsonPath("$.capabilities.view_policies").value(true))
                .andExpect(jsonPath("$.capabilities.view_webhooks").value(true))
                .andExpect(jsonPath("$.capabilities.view_events").value(true))
                .andExpect(jsonPath("$.capabilities.view_reservations").value(true))
                // …but NOT the admin-plane caps (NORMATIVE forced-false).
                .andExpect(jsonPath("$.capabilities.view_overview").value(false))
                .andExpect(jsonPath("$.capabilities.view_tenants").value(false))
                .andExpect(jsonPath("$.capabilities.view_api_keys").value(false))
                .andExpect(jsonPath("$.capabilities.view_audit").value(false))
                .andExpect(jsonPath("$.capabilities.manage_tenants").value(false))
                .andExpect(jsonPath("$.capabilities.manage_api_keys").value(false));
    }

    @Test
    void introspect_invalidTenantKey_returns403() throws Exception {
        when(apiKeyRepository.validate("bad_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("KEY_NOT_FOUND").build());

        mockMvc.perform(get("/v1/auth/introspect")
                        .header("X-Cycles-API-Key", "bad_key"))
                .andExpect(status().isForbidden());
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
