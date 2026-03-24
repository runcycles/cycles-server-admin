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

    // --- API key auth (budgets, policies, balances) ---

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
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("admin:write", "balances:read"))
                        .scopeFilter(List.of("org/*"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isEqualTo("t1");
        assertThat(request.getAttribute("authenticated_key_id")).isEqualTo("key_1");
        assertThat(request.getAttribute("authenticated_permissions")).isEqualTo(List.of("admin:write", "balances:read"));
        assertThat(request.getAttribute("authenticated_scope_filter")).isEqualTo(List.of("org/*"));
    }

    @Test
    void preHandle_budgetEndpoint_insufficientPermissions_returns403() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
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
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("balances:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_budgetListEndpoint_adminReadPermission_allows() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("admin:read"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void preHandle_policyUpdate_withPathVariable_requiresAdminWrite() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/v1/admin/policies/pol_123");
        request.addHeader("X-Cycles-API-Key", "valid-key");

        when(apiKeyRepository.validate("valid-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
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
                        .valid(true).tenantId("t1").keyId("key_1")
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
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("reservations:write"))
                        .build());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("authenticated_tenant_id")).isEqualTo("t1");
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
}
