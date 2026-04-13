package io.runcycles.admin.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    private AuthInterceptor interceptor;
    private ObjectMapper objectMapper = new ObjectMapper();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(apiKeyRepository, objectMapper);
        ReflectionTestUtils.setField(interceptor, "adminApiKey", "admin-secret-key");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // --- Skip auth for non-API paths ---

    @Test
    void preHandle_optionsRequest_skipsAuth() throws Exception {
        request.setMethod("OPTIONS");
        request.setRequestURI("/v1/admin/tenants");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_swaggerPath_skipsAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/swagger-ui/index.html");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_apiDocsPath_skipsAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v3/api-docs");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_actuatorPath_skipsAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/actuator/health");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    // --- Admin key auth (tenants, api-keys, auth/validate, audit) ---

    @Test
    void preHandle_adminEndpoint_missingHeader_returns401() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_adminEndpoint_invalidKey_returns401() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "wrong-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_adminEndpoint_validKey_returns200() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_adminEndpoint_blankAdminApiKey_rejectsWithServerError() throws Exception {
        ReflectionTestUtils.setField(interceptor, "adminApiKey", "");
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "any-value");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void preHandle_apiKeysEndpoint_requiresAdminKey() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/api-keys");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_authValidateEndpoint_requiresAdminKey() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/auth/validate");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_auditEndpoint_requiresAdminKey() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/audit/logs");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    // --- PATCH /v1/admin/budgets requires AdminKeyAuth per spec v0.1.25 ---

    @Test
    void preHandle_patchBudget_requiresAdminKey() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_patchBudget_missingAdminKey_returns401() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/budgets");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_patchBudget_apiKeyNotAccepted() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // --- API key auth (budgets POST/GET, policies, balances) ---

    @Test
    void preHandle_budgetEndpoint_missingHeader_returns401() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_budgetEndpoint_invalidApiKey_returns403() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "invalid-key");

        when(apiKeyRepository.validate("invalid-key")).thenReturn(
                ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preHandle_budgetEndpoint_validApiKey_setsAttributes() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("budgets:write", "balances:read"))
                        .scopeFilter(List.of("org/*"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isEqualTo("tenant-1");
        assertThat(request.getAttribute("authenticated_key_id")).isEqualTo("key_1");
        assertThat(request.getAttribute("authenticated_permissions")).isEqualTo(List.of("budgets:write", "balances:read"));
        assertThat(request.getAttribute("authenticated_scope_filter")).isEqualTo(List.of("org/*"));
    }

    @Test
    void preHandle_budgetEndpoint_insufficientPermissions_returns403() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("balances:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preHandle_balancesEndpoint_balancesReadPermission_allows() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/balances");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("balances:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_budgetListEndpoint_budgetsReadPermission_allows() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("budgets:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_policyUpdate_withPathVariable_requiresPoliciesWrite() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/pol_123");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("policies:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    // --- admin:read/admin:write wildcard backward compatibility ---

    @Test
    void preHandle_adminWriteWildcard_satisfiesBudgetsWrite() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("admin:write"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_adminReadWildcard_satisfiesBudgetsRead() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("admin:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_adminWriteWildcard_satisfiesPoliciesWrite() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/policies");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("admin:write"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_adminReadWildcard_doesNotSatisfyWrite() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("admin:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preHandle_nullPermissions_returns403() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(null)
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preHandle_policyEndpoint_requiresApiKey() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/policies");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_balancesEndpoint_requiresApiKey() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/balances");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // --- Error response format ---

    @Test
    void preHandle_errorResponse_containsJsonErrorBody() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");

        interceptor.preHandle(request, response, new Object());

        assertThat(response.getContentType()).startsWith("application/json");
        String body = response.getContentAsString();
        assertThat(body).contains("\"error\"");
        assertThat(body).contains("\"message\"");
        assertThat(body).contains("\"request_id\"");
    }

    // --- Reservations endpoint (API key auth) ---

    @Test
    void preHandle_reservationsEndpoint_requiresApiKey() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/reservations");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_reservationsEndpoint_validApiKey_succeeds() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/reservations");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("reservations:write"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isEqualTo("tenant-1");
    }

    // --- API key validation edge cases ---

    @Test
    void preHandle_apiKeyEndpoint_blankApiKey_returns401() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "   ");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_apiKeyValidation_nullReason_returns403() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "bad-key");

        when(apiKeyRepository.validate("bad-key")).thenReturn(
                ApiKeyValidationResponse.builder().valid(false).tenantId("").reason(null).build());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("UNKNOWN");
    }

    // --- Non-matching paths pass through ---

    @Test
    void preHandle_unknownV1Path_passesThrough() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/unknown");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_nonV1Path_passesThrough() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/health");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_adminEndpoint_nullAdminApiKey_rejectsWithServerError() throws Exception {
        ReflectionTestUtils.setField(interceptor, "adminApiKey", null);
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "any-value");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("Server misconfiguration");
    }

    @Test
    void preHandle_adminEndpoint_blankHeader_returns401() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "   ");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_errorResponse_usesRequestIdAttributeWhenPresent() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.setAttribute("requestId", "test-req-id-abc");

        interceptor.preHandle(request, response, new Object());

        assertThat(response.getContentAsString()).contains("test-req-id-abc");
    }

    @Test
    void preHandle_errorResponse_generatesUuidWhenRequestIdAttributeMissing() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        // No requestId attribute set

        interceptor.preHandle(request, response, new Object());

        String body = response.getContentAsString();
        assertThat(body).contains("\"request_id\"");
        // The request_id should be a UUID since no attribute was set
        assertThat(body).containsPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void preHandle_apiDocsLegacyPath_skipsAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api-docs/something");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    // --- New endpoint auth routing (v0.1.25.1) ---

    @Test
    void preHandle_overviewEndpoint_withoutAdminKey_returns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/overview");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_overviewEndpoint_withValidAdminKey_succeeds() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/overview");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_introspectEndpoint_withoutAdminKey_returns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/auth/introspect");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_introspectEndpoint_withValidAdminKey_succeeds() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/auth/introspect");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    // --- Dual-auth allowlist (v0.1.25.1) ---

    @Test
    void preHandle_getBudgets_withAdminKey_acceptedViaDualAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        // Admin auth sets no request attributes
        assertThat(request.getAttribute("authenticated_tenant_id")).isNull();
    }

    @Test
    void preHandle_getBudgetLookup_withAdminKey_acceptedViaDualAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets/lookup");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isNull();
    }

    @Test
    void preHandle_getPolicies_withAdminKey_acceptedViaDualAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/policies");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isNull();
    }

    // --- Dual-auth: admin-on-behalf-of writes (v0.1.25.14, spec v0.1.25.13) ---

    @Test
    void preHandle_postBudgets_withAdminKey_accepted() throws Exception {
        // POST /v1/admin/budgets is now in the dual-auth allowlist
        // (v0.1.25.14, spec v0.1.25.13). Admin operators can create
        // budgets on behalf of tenants — controller enforces tenant_id
        // in body separately.
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.setServletPath("/v1/admin/budgets");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_postPolicies_withAdminKey_accepted() throws Exception {
        // POST /v1/admin/policies dual-auth (v0.1.25.14).
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/policies");
        request.setServletPath("/v1/admin/policies");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_patchPolicyById_withAdminKey_accepted() throws Exception {
        // PATCH /v1/admin/policies/{policy_id} dual-auth via prefix matching
        // (v0.1.25.14). Exact match doesn't help here because every request
        // has a different concrete policy id.
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/pol_abc123");
        request.setServletPath("/v1/admin/policies/pol_abc123");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_patchPoliciesBarePrefix_withAdminKey_rejected() throws Exception {
        // The prefix matcher requires a non-empty resource id after the prefix.
        // PATCH /v1/admin/policies (no id) should NOT be accepted via the
        // prefix entry — would be a malformed request anyway, but the guard
        // matters for correctness.
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies");
        request.setServletPath("/v1/admin/policies");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        // Falls through to validateApiKey since not in exact allowlist and
        // doesn't match the prefix-with-suffix rule. With admin-only header
        // and no api key header, rejected with 401.
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_patchPolicyById_withAdminKey_trailingSlashNormalized() throws Exception {
        // Trailing slash on the request path is normalized; should still match.
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/pol_xyz/");
        request.setServletPath("/v1/admin/policies/pol_xyz/");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    // Defense-in-depth: even though Tomcat's connector rejects "../" by
    // default, the interceptor short-circuits any request whose path
    // contains a traversal segment before applying the dual-auth allowlist.
    // Without this guard, a request like
    //   PATCH /v1/admin/policies/../tenants/t_1
    // could (in a hypothetical relaxed-Tomcat or behind-a-rewriting-proxy
    // deployment) pass the prefix matcher and then be re-routed by Spring's
    // dispatcher to a different endpoint with admin auth already approved
    // (auth-context confusion).
    @Test
    void preHandle_pathContainsDotDot_returns400() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/..%2Ftenants/t_1");
        request.setServletPath("/v1/admin/policies/../tenants/t_1");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void preHandle_pathEndsInDotDot_returns400() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/foo/..");
        request.setServletPath("/v1/admin/policies/foo/..");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void preHandle_pathContainsDotSegment_returns400() throws Exception {
        // /./ is also a normalization-ambiguous segment.
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/./pol_x");
        request.setServletPath("/v1/admin/policies/./pol_x");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void preHandle_postBudgetsFund_withAdminKey_accepted() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets/fund");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");
        // POST /v1/admin/budgets/fund is now in the dual-auth allowlist (v0.1.25.6)

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_postBudgetsFreeze_withAdminKey_accepted() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets/freeze");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_postBudgetsUnfreeze_withAdminKey_accepted() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets/unfreeze");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    // --- Dual-auth: API key still works on allowlisted endpoints ---

    @Test
    void preHandle_getBudgets_withApiKey_stillWorks() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("budgets:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isEqualTo("tenant-1");
    }

    @Test
    void preHandle_getPolicies_withApiKey_stillWorks() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/policies");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("policies:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isEqualTo("tenant-1");
    }

    // --- Dual-auth: scope filter no-op for admin key ---

    @Test
    void preHandle_getBudgetLookup_adminKey_noScopeFilterAttribute() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets/lookup");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        // ScopeFilterUtil.enforceScopeFilter() no-ops when authenticated_scope_filter is null
        assertThat(request.getAttribute("authenticated_scope_filter")).isNull();
    }
}
