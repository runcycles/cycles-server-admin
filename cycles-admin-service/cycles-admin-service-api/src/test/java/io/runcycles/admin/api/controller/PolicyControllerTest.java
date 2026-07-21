package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.PolicyRepository;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.policy.*;
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

@WebMvcTest(PolicyController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class PolicyControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private PolicyRepository policyRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;
    @MockitoBean private io.runcycles.admin.api.service.EventService eventService;
    @MockitoBean private TerminalOwnerMutationGuard mutationGuard;

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("policies:read", "policies:write", "balances:read")).build());
    }

    @Test
    void createPolicy_returns201() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("tenant:tenant-1/*").name("Rate Limit")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.create(eq("tenant-1"), any())).thenReturn(policy);

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rate Limit\",\"scope_pattern\":\"tenant:tenant-1/*\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policy_id").value("pol_123"))
                .andExpect(jsonPath("$.scope_pattern").value("tenant:tenant-1/*"));
    }

    @Test
    void createPolicy_missingName_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_pattern\":\"tenant:tenant-1/*\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createPolicy_negativePriority_returns400WithoutPersistence() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"scope_pattern\":\"*\",\"priority\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(policyRepository, never()).create(any(), any());
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
                .policyId("pol_1").scopePattern("tenant:tenant-1/*").name("P1")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), anyInt())).thenReturn(List.of(policy));

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies").isArray())
                .andExpect(jsonPath("$.policies[0].policy_id").value("pol_1"));
    }

    @Test
    void listPolicies_withFilters() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), eq("tenant:tenant-1/*"), eq(PolicyStatus.ACTIVE), any(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("scope_pattern", "tenant:tenant-1/*")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies").isEmpty());
    }

    @Test
    void createPolicy_logsAuditEntry() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("tenant:tenant-1/*").name("Rate Limit")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.create(eq("tenant-1"), any())).thenReturn(policy);

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rate Limit\",\"scope_pattern\":\"tenant:tenant-1/*\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                "createPolicy".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 201));
    }

    @Test
    void listPolicies_usesAuthenticatedTenantId() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk());

        verify(policyRepository).list(eq("tenant-1"), any(), any(), any(), anyInt());
    }

    @Test
    void listPolicies_emptyResult_hasMoreFalseAndNoCursor() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listPolicies_limitClampedToMax100() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), eq(101))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "500"))
                .andExpect(status().isOk());

        verify(policyRepository).list(eq("tenant-1"), any(), any(), any(), eq(101));
    }

    @Test
    void listPolicies_limitClampedToMin1() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), eq(2))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(policyRepository).list(eq("tenant-1"), any(), any(), any(), eq(2));
    }

    @Test
    void createPolicy_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("tenant:tenant-1/*").name("Rate Limit")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.create(eq("tenant-1"), any())).thenReturn(policy);

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rate Limit\",\"scope_pattern\":\"tenant:tenant-1/*\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getRequestId() != null &&
                entry.getRequestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") &&
                "createPolicy".equals(entry.getOperation())));
    }

    @Test
    void createPolicy_auditEntry_userAgentIsNullWhenHeaderMissing() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("tenant:tenant-1/*").name("Rate Limit")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.create(eq("tenant-1"), any())).thenReturn(policy);

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rate Limit\",\"scope_pattern\":\"tenant:tenant-1/*\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getUserAgent() == null &&
                "createPolicy".equals(entry.getOperation())));
    }

    @Test
    void createPolicy_auditEntry_capturesSourceIp() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("tenant:tenant-1/*").name("Rate Limit")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.create(eq("tenant-1"), any())).thenReturn(policy);

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rate Limit\",\"scope_pattern\":\"tenant:tenant-1/*\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getSourceIp() != null &&
                "createPolicy".equals(entry.getOperation())));
    }

    @Test
    void listPolicies_resultCountExceedsLimit_hasMoreTrueWithCursor() throws Exception {
        setupApiKeyAuth();
        Policy p1 = Policy.builder()
                .policyId("pol_1").scopePattern("tenant:tenant-1/*").name("P1")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        Policy p2 = Policy.builder()
                .policyId("pol_2").scopePattern("tenant:tenant-1/agent:*").name("P2")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        Policy p3 = Policy.builder()
                .policyId("pol_3").scopePattern("tenant:tenant-1/toolset:*").name("P3")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), eq(3)))
                .thenReturn(List.of(p1, p2, p3));

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("pol_2"));
    }

    @Test
    void listPolicies_withCursorParam_passesToRepository() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), any(), any(), eq("pol_abc"), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("cursor", "pol_abc"))
                .andExpect(status().isOk());

        verify(policyRepository).list(eq("tenant-1"), any(), any(), eq("pol_abc"), anyInt());
    }

    @Test
    void updatePolicy_unknownFieldIsRejectedAtHttpBoundary() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(patch("/v1/admin/policies/pol_1")
                .header("X-Cycles-API-Key", "valid-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"future_field\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(policyRepository, never()).update(anyString(), anyString(), any());
    }

    @Test
    void updatePolicy_negativePriority_returns400WithoutPersistence() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(patch("/v1/admin/policies/pol_1")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(policyRepository, never()).update(anyString(), anyString(), any());
    }

    // ========== PATCH /v1/admin/policies/{policy_id} ==========

    @Test
    void updatePolicy_returns200() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("tenant:tenant-1/*").name("Updated Name")
                .priority(10).status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.update(eq("tenant-1"), eq("pol_123"), any())).thenReturn(policy);

        mockMvc.perform(patch("/v1/admin/policies/pol_123")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\",\"priority\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy_id").value("pol_123"))
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.priority").value(10));
    }

    @Test
    void updatePolicy_notFound_returns404() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.update(eq("tenant-1"), eq("pol_missing"), any()))
                .thenThrow(GovernanceException.policyNotFound("pol_missing"));

        mockMvc.perform(patch("/v1/admin/policies/pol_missing")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updatePolicy_logsAuditEntry() throws Exception {
        setupApiKeyAuth();
        Policy policy = Policy.builder()
                .policyId("pol_123").scopePattern("tenant:tenant-1/*").name("P")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.update(eq("tenant-1"), eq("pol_123"), any())).thenReturn(policy);

        mockMvc.perform(patch("/v1/admin/policies/pol_123")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":5}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updatePolicy".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void updatePolicy_noApiKey_returns401() throws Exception {
        mockMvc.perform(patch("/v1/admin/policies/pol_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isUnauthorized());
    }

    // v0.1.25.8: has_action_quotas and references_action_kind must be accepted and ignored
    @Test
    void listPolicies_withActionQuotaParams_acceptedAndIgnored() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("has_action_quotas", "true")
                        .param("references_action_kind", "payment.charge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies").isArray());

        // Repository call unchanged — these params are not passed through on v0.1.25.x
        verify(policyRepository).list(eq("tenant-1"), any(), any(), any(), anyInt());
    }

    // v0.1.25.8: spec says "MUST ignore without error" — malformed values must not 400
    @Test
    void listPolicies_withMalformedHasActionQuotas_stillReturns200() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.list(eq("tenant-1"), any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("has_action_quotas", "not-a-boolean"))
                .andExpect(status().isOk());
    }

    // ========== Admin-on-behalf-of createPolicy / updatePolicy (v0.1.25.14, spec v0.1.25.13) ==========

    @Test
    void createPolicy_withAdminKey_andTenantIdInBody_returns201() throws Exception {
        Policy policy = Policy.builder()
                .policyId("pol_admin").scopePattern("tenant:tenant-acme/*").name("Admin Policy")
                .tenantId("tenant-acme")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.create(eq("tenant-acme"), any())).thenReturn(policy);

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"tenant-acme\",\"name\":\"Admin Policy\",\"scope_pattern\":\"tenant:tenant-acme/*\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policy_id").value("pol_admin"));

        verify(auditRepository).log(argThat(entry ->
                "createPolicy".equals(entry.getOperation()) &&
                "tenant-acme".equals(entry.getTenantId()) &&
                entry.getMetadata() != null &&
                "admin_on_behalf_of".equals(entry.getMetadata().get("actor_type"))));
    }

    @Test
    void createPolicy_withAdminKey_missingTenantIdInBody_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Admin Policy\",\"scope_pattern\":\"tenant:tenant-acme/*\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void createPolicy_withAdminKey_andJsonNullTenantId_returns400() throws Exception {
        // {"tenant_id": null} — Jackson → Java null → caught by !=null guard.
        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":null,\"name\":\"P\",\"scope_pattern\":\"tenant:tenant-acme/*\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void createPolicy_withApiKey_andTenantIdInBody_returns400() throws Exception {
        // Tenant-key callers MUST NOT send tenant_id (spec v0.1.25.13).
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"some-other\",\"name\":\"Sneaky\",\"scope_pattern\":\"tenant:other/*\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePolicy_withAdminKey_returns200_andLogsAdminOnBehalfOf() throws Exception {
        // Admin update: repository.update receives null tenantId (Lua skips
        // ownership). Audit subject is the policy's stored tenant_id, not
        // null — verifies the controller substitutes correctly.
        when(policyRepository.getScopePattern("pol_xyz")).thenReturn("tenant:tenant-acme/*");
        Policy updated = Policy.builder()
                .policyId("pol_xyz").scopePattern("tenant:tenant-acme/*").name("Updated")
                .tenantId("tenant-acme")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.update(isNull(), eq("pol_xyz"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/policies/pol_xyz")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updatePolicy".equals(entry.getOperation()) &&
                "tenant-acme".equals(entry.getTenantId()) && // subject from policy, not null caller
                entry.getMetadata() != null &&
                "admin_on_behalf_of".equals(entry.getMetadata().get("actor_type"))));
    }

    // v0.1.25.36 — Cascade Rule 2: owner-tenant guard.
    @Test
    void createPolicy_closedTenant_returns409_tenantClosed() throws Exception {
        setupApiKeyAuth();
        doThrow(new GovernanceException(ErrorCode.TENANT_CLOSED,
            "Tenant tenant-1 is closed; owned objects are read-only", 409))
            .when(mutationGuard).assertTenantOpen("tenant-1");

        mockMvc.perform(post("/v1/admin/policies")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"scope_pattern\":\"tenant:tenant-1/*\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TENANT_CLOSED"));

        verify(policyRepository, never()).create(any(), any());
    }

    @Test
    void updatePolicy_closedTenant_returns409_tenantClosed() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.getScopePattern("pol_x")).thenReturn("tenant:tenant-1/*");
        doThrow(new GovernanceException(ErrorCode.TENANT_CLOSED,
            "Tenant tenant-1 is closed; owned objects are read-only", 409))
            .when(mutationGuard).assertOpenForScope("tenant:tenant-1/*");

        mockMvc.perform(patch("/v1/admin/policies/pol_x")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"blocked\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TENANT_CLOSED"));

        verify(policyRepository, never()).update(any(), any(), any());
    }

    @Test
    void updatePolicy_withApiKey_returns200_andLogsApiKey() throws Exception {
        setupApiKeyAuth();
        when(policyRepository.getScopePattern("pol_self")).thenReturn("tenant:tenant-1/*");
        Policy updated = Policy.builder()
                .policyId("pol_self").scopePattern("tenant:tenant-1/*").name("Self-update")
                .tenantId("tenant-1")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        when(policyRepository.update(eq("tenant-1"), eq("pol_self"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/policies/pol_self")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Self-update\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updatePolicy".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                entry.getMetadata() != null &&
                "api_key".equals(entry.getMetadata().get("actor_type"))));
    }

    @Test
    void updatePolicyAllAuditMetadataFieldsAndEmptyPatchAreHandled() throws Exception {
        when(policyRepository.getScopePattern("pol_all")).thenReturn("tenant:tenant-1/*");
        Policy updated = Policy.builder().policyId("pol_all").tenantId("tenant-1")
            .scopePattern("tenant:tenant-1/*").status(PolicyStatus.DISABLED).createdAt(Instant.now()).build();
        when(policyRepository.update(isNull(), eq("pol_all"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/policies/pol_all").header("X-Admin-API-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"All\",\"priority\":7,\"status\":\"DISABLED\",\"caps\":{\"max_tokens\":10},\"commit_overage_policy\":\"REJECT\"}"))
            .andExpect(status().isOk());
        verify(auditRepository).log(argThat(entry -> entry.getMetadata() != null
            && entry.getMetadata().containsKey("caps_updated")
            && entry.getMetadata().containsKey("commit_overage_policy")
            && entry.getMetadata().containsKey("priority")
            && entry.getMetadata().containsKey("new_status")));

        reset(auditRepository);
        mockMvc.perform(patch("/v1/admin/policies/pol_all").header("X-Admin-API-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
        verify(auditRepository).log(argThat(entry -> entry.getMetadata() != null
            && entry.getMetadata().size() == 2
            && entry.getMetadata().containsKey("scope_pattern")
            && entry.getMetadata().containsKey("actor_type")));
    }

    @Test
    void blankAdminTenantIsRejectedForCreateAndList() throws Exception {
        mockMvc.perform(post("/v1/admin/policies").header("X-Admin-API-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\" \",\"name\":\"Blank\",\"scope_pattern\":\"tenant:tenant-1/*\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/admin/policies").header("X-Admin-API-Key", "test-admin-key")
                .param("tenant_id", " "))
            .andExpect(status().isBadRequest());
    }
}
