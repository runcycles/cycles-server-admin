package io.runcycles.admin.api.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.AuditFailureService;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private AuditFailureService auditFailure;
    private AuthInterceptor interceptor;
    private ObjectMapper objectMapper = new ObjectMapper();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(apiKeyRepository, objectMapper, auditFailure);
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
        // v0.1.25.28 regression guard: a failed admin-key attempt must
        // NOT stamp actor_type — otherwise AuditFailureService would
        // promote pre-auth failures into the __admin__ sentinel.
        assertThat(request.getAttribute("authenticated_actor_type")).isNull();
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
        // v0.1.25.28 regression guard: misconfig path must reject BEFORE
        // stamping actor_type.
        assertThat(request.getAttribute("authenticated_actor_type")).isNull();
    }

    @Test
    void preHandle_adminEndpoint_blankHeader_returns401() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "   ");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        // v0.1.25.28 regression guard: blank-header 401 must reject
        // BEFORE stamping actor_type.
        assertThat(request.getAttribute("authenticated_actor_type")).isNull();
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
    void preHandle_introspectEndpoint_withoutAnyKey_returns401() throws Exception {
        // v0.1.25.19: dual-auth — neither header → 401 (spec yaml:4729-4734).
        request.setMethod("GET");
        request.setRequestURI("/v1/auth/introspect");
        request.setServletPath("/v1/auth/introspect");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandle_introspectEndpoint_withValidAdminKey_succeeds() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/auth/introspect");
        request.setServletPath("/v1/auth/introspect");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        // v0.1.25.28: admin auth path stamps actor_type="admin" so
        // AuditFailureService can pick __admin__ for admin-plane failures.
        // authenticated_tenant_id remains null — downstream controllers
        // use its null-ness as the "is admin?" discriminator.
        assertThat(request.getAttribute("authenticated_tenant_id")).isNull();
        assertThat(request.getAttribute("authenticated_actor_type")).isEqualTo("admin");
    }

    // --- v0.1.25.19: dual-auth on /v1/auth/introspect (spec v0.1.25.15) ---

    @Test
    void preHandle_introspectEndpoint_withValidTenantKey_succeedsAndStampsAttributes() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/auth/introspect");
        request.setServletPath("/v1/auth/introspect");
        request.addHeader("X-Cycles-API-Key", "tenant-key");

        when(apiKeyRepository.validate("tenant-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t-1").keyId("k-1")
                        .permissions(List.of("budgets:read"))
                        .scopeFilter(List.of("tenant:t-1/app:prod"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        // Tenant auth path: attributes stamped so controller can branch on them.
        assertThat(request.getAttribute("authenticated_tenant_id")).isEqualTo("t-1");
        assertThat(request.getAttribute("authenticated_permissions"))
                .isEqualTo(List.of("budgets:read"));
        assertThat(request.getAttribute("authenticated_scope_filter"))
                .isEqualTo(List.of("tenant:t-1/app:prod"));
    }

    @Test
    void preHandle_introspectEndpoint_withInvalidTenantKey_returns403() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/auth/introspect");
        request.setServletPath("/v1/auth/introspect");
        request.addHeader("X-Cycles-API-Key", "bad-key");

        when(apiKeyRepository.validate("bad-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("KEY_NOT_FOUND").build());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preHandle_introspectEndpoint_noPermissionRequired() throws Exception {
        // /v1/auth/introspect is not in PERMISSION_MAP — any valid tenant key
        // can introspect itself regardless of permissions (spec yaml:4703-4768).
        request.setMethod("GET");
        request.setRequestURI("/v1/auth/introspect");
        request.setServletPath("/v1/auth/introspect");
        request.addHeader("X-Cycles-API-Key", "minimal-key");

        when(apiKeyRepository.validate("minimal-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t-min").keyId("k-min")
                        .permissions(List.of()) // zero permissions
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    // --- Dual-auth allowlist (v0.1.25.1) ---

    @Test
    void preHandle_getBudgets_withAdminKey_acceptedViaDualAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        // v0.1.25.28: admin auth stamps actor_type="admin", not a sentinel
        // on authenticated_tenant_id — controllers rely on its null-ness
        // as the "is admin?" discriminator.
        assertThat(request.getAttribute("authenticated_tenant_id")).isNull();
        assertThat(request.getAttribute("authenticated_actor_type")).isEqualTo("admin");
    }

    @Test
    void preHandle_getBudgetLookup_withAdminKey_acceptedViaDualAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets/lookup");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isNull();
        assertThat(request.getAttribute("authenticated_actor_type")).isEqualTo("admin");
    }

    @Test
    void preHandle_getPolicies_withAdminKey_acceptedViaDualAuth() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/policies");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isNull();
        assertThat(request.getAttribute("authenticated_actor_type")).isEqualTo("admin");
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

    // --- v0.1.25.16: tenant-scoped webhook dual-auth (spec v0.1.25.14) ---
    // Locks the allowlist contract at the interceptor layer in addition to
    // the controller-level MockMvc tests. If someone ever refactors the
    // allowlist strings, these tests catch regressions before they reach
    // the controller.

    @Test
    void preHandle_getWebhooks_withAdminKey_accepted() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/webhooks");
        request.setServletPath("/v1/webhooks");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_getWebhookById_withAdminKey_accepted() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/webhooks/whsub_1");
        request.setServletPath("/v1/webhooks/whsub_1");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_patchWebhookById_withAdminKey_accepted() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/webhooks/whsub_1");
        request.setServletPath("/v1/webhooks/whsub_1");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_deleteWebhookById_withAdminKey_accepted() throws Exception {
        request.setMethod("DELETE");
        request.setRequestURI("/v1/webhooks/whsub_1");
        request.setServletPath("/v1/webhooks/whsub_1");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_postWebhookTest_withAdminKey_accepted() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/webhooks/whsub_1/test");
        request.setServletPath("/v1/webhooks/whsub_1/test");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_getWebhookDeliveries_withAdminKey_accepted() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/webhooks/whsub_1/deliveries");
        request.setServletPath("/v1/webhooks/whsub_1/deliveries");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_postWebhooksCreate_withAdminKey_rejected() throws Exception {
        // createTenantWebhook is NOT dual-auth (provenance footgun). The
        // prefix "POST:/v1/webhooks/" requires a non-empty suffix, so the
        // bare create path "POST:/v1/webhooks" correctly does not match.
        // Falls through to validateApiKey with no api key header → 401.
        // This is the critical "by construction" guard for the spec's
        // deliberate exclusion.
        request.setMethod("POST");
        request.setRequestURI("/v1/webhooks");
        request.setServletPath("/v1/webhooks");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // --- v0.1.25.20: audit-on-failure — every writeError() call site must
    // emit an audit entry with matching status + error_code via
    // AuditFailureService. Locks the contract at the interceptor layer
    // so subsequent refactors can't silently drop audit coverage. ---

    @Test
    void preHandle_adminEndpointMissingHeader_writesFailureAudit_401Unauthorized() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure).logFailure(eq(request), eq(401), eq(ErrorCode.UNAUTHORIZED), anyString(), isNull());
    }

    @Test
    void preHandle_adminEndpointInvalidKey_writesFailureAudit_401Unauthorized() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "wrong-key");

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure).logFailure(eq(request), eq(401), eq(ErrorCode.UNAUTHORIZED), anyString(), isNull());
    }

    @Test
    void preHandle_adminKeyMisconfigured_writesFailureAudit_500Internal() throws Exception {
        ReflectionTestUtils.setField(interceptor, "adminApiKey", "");
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        request.addHeader("X-Admin-API-Key", "any-value");

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure).logFailure(eq(request), eq(500), eq(ErrorCode.INTERNAL_ERROR), anyString(), isNull());
    }

    @Test
    void preHandle_pathTraversal_writesFailureAudit_400InvalidRequest() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/..%2Ftenants/t_1");
        request.setServletPath("/v1/admin/policies/../tenants/t_1");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure).logFailure(eq(request), eq(400), eq(ErrorCode.INVALID_REQUEST), anyString(), isNull());
    }

    @Test
    void preHandle_apiKeyEndpointMissingHeader_writesFailureAudit_401Unauthorized() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure).logFailure(eq(request), eq(401), eq(ErrorCode.UNAUTHORIZED), anyString(), isNull());
    }

    @Test
    void preHandle_apiKeyInvalid_writesFailureAudit_403Forbidden() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "bad-key");
        when(apiKeyRepository.validate("bad-key")).thenReturn(
                ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build());

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure).logFailure(eq(request), eq(403), eq(ErrorCode.FORBIDDEN), anyString(), isNull());
    }

    @Test
    void preHandle_apiKeyInsufficientPermissions_writesFailureAudit_403InsufficientPermissions() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");
        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("balances:read")) // missing budgets:write
                        .build());

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure).logFailure(eq(request), eq(403),
                eq(ErrorCode.INSUFFICIENT_PERMISSIONS), anyString(), isNull());
    }

    @Test
    void preHandle_repeatedAuthFailures_rateLimitedWithoutExtraAuditWrite() throws Exception {
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitEnabled", true);
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitMaxPerMinute", 1);

        request.setMethod("POST");
        request.setRequestURI("/v1/admin/tenants");
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);

        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        secondRequest.setMethod("POST");
        secondRequest.setRequestURI("/v1/admin/tenants");

        assertThat(interceptor.preHandle(secondRequest, secondResponse, new Object())).isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentAsString()).contains("LIMIT_EXCEEDED");
        verify(auditFailure, times(1)).logFailure(any(), eq(401), eq(ErrorCode.UNAUTHORIZED), anyString(), isNull());
    }

    @Test
    void preHandle_uniqueSourceFlood_keepsFailureTrackerBounded() throws Exception {
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitEnabled", true);
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitMaxPerMinute", 10);
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitMaxTrackedSources", 2);

        for (int i = 1; i <= 3; i++) {
            MockHttpServletRequest failedRequest = new MockHttpServletRequest();
            failedRequest.setMethod("POST");
            failedRequest.setRequestURI("/v1/admin/tenants");
            failedRequest.setRemoteAddr("192.0.2." + i);

            assertThat(interceptor.preHandle(
                failedRequest, new MockHttpServletResponse(), new Object())).isFalse();
        }

        assertThat(interceptor.trackedAuthFailureSourceCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void preHandle_uniqueSourceFlood_warnsOnlyOncePerWindow() throws Exception {
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitEnabled", true);
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitMaxPerMinute", 10);
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitMaxTrackedSources", 1);
        Logger logger = (Logger) LoggerFactory.getLogger(AuthInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 1; i <= 3; i++) {
                MockHttpServletRequest failedRequest = new MockHttpServletRequest();
                failedRequest.setMethod("POST");
                failedRequest.setRequestURI("/v1/admin/tenants");
                failedRequest.setRemoteAddr("198.51.100." + i);
                interceptor.preHandle(
                    failedRequest, new MockHttpServletResponse(), new Object());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list.stream()
            .filter(event -> event.getFormattedMessage().contains(
                "tracked-source cap exceeded")))
            .hasSize(1);
    }

    @Test
    void preHandle_adminKeyValid_noFailureAuditWritten() throws Exception {
        // Single-write invariant sanity check — success paths never trigger
        // a failure-side write. Controllers do their own (richer) success
        // audit-log.
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/audit/logs");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        interceptor.preHandle(request, response, new Object());

        verify(auditFailure, never()).logFailure(any(), anyInt(), any(), anyString(), any());
    }

    @Test
    void dualAuthFallsBackToRequestUriWhenServletPathIsNull() throws Exception {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getMethod()).thenReturn("GET");
        when(servletRequest.getRequestURI()).thenReturn("/v1/admin/budgets");
        when(servletRequest.getServletPath()).thenReturn(null);
        lenient().when(servletRequest.getHeader("X-Admin-API-Key")).thenReturn(null);
        when(servletRequest.getHeader("X-Cycles-API-Key")).thenReturn("tenant-secret");
        when(apiKeyRepository.validate("tenant-secret")).thenReturn(ApiKeyValidationResponse.builder()
            .valid(true).tenantId("tenant-1").keyId("key-1")
            .permissions(List.of("budgets:read")).build());

        assertThat(interceptor.preHandle(servletRequest, response, new Object())).isTrue();
        verify(servletRequest).setAttribute("authenticated_tenant_id", "tenant-1");
    }

    @Test
    void authFailureLimiterHandlesNullRequestsDisabledLimitsAndStatusClasses() {
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitEnabled", true);
        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitMaxPerMinute", 0);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
            interceptor, "shouldThrottleAuthFailure", (Object) null, 401)).isFalse();

        ReflectionTestUtils.setField(interceptor, "authFailureRateLimitMaxPerMinute", 1);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
            interceptor, "shouldThrottleAuthFailure", (Object) null, 400)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
            interceptor, "shouldThrottleAuthFailure", (Object) null, 403)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
            interceptor, "shouldThrottleAuthFailure", (Object) null, 403)).isTrue();

        assertThat((String) ReflectionTestUtils.invokeMethod(
            interceptor, "authFailurePathClass", " ")).isEqualTo("unknown");
        assertThat((String) ReflectionTestUtils.invokeMethod(
            interceptor, "authFailurePathClass", "/v1/auth/introspect")).isEqualTo("auth");
        assertThat((String) ReflectionTestUtils.invokeMethod(
            interceptor, "authFailurePathClass", "/v1/balances")).isEqualTo("tenant");
    }

    @Test
    void bareAdminPrefixDoesNotCountAsResourceId() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(interceptor,
            "matchesAdminPrefix", "PATCH", "/v1/admin/policies/")).isFalse();
    }
}
