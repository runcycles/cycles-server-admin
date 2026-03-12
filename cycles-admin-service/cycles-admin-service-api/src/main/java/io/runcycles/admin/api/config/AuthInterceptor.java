package io.runcycles.admin.api.config;

import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import io.runcycles.admin.api.filter.RequestIdFilter;

import java.util.UUID;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String ADMIN_KEY_HEADER = "X-Admin-API-Key";
    private static final String API_KEY_HEADER = "X-Cycles-API-Key";

    @Value("${admin.api-key:}")
    private String adminApiKey;

    private final ApiKeyRepository apiKeyRepository;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(ApiKeyRepository apiKeyRepository, ObjectMapper objectMapper) {
        this.apiKeyRepository = apiKeyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Skip auth for health, docs, and OPTIONS
        if ("OPTIONS".equals(method) || path.startsWith("/api-docs") || path.startsWith("/swagger") ||
            path.startsWith("/actuator") || path.startsWith("/v3/api-docs")) {
            return true;
        }

        // Determine which auth is required based on path
        if (requiresAdminKey(path)) {
            return validateAdminKey(request, response);
        } else if (requiresApiKey(path)) {
            return validateApiKey(request, response);
        }

        return true;
    }

    private boolean requiresAdminKey(String path) {
        // Admin endpoints per spec: tenants, api-keys, auth/validate, audit
        return path.startsWith("/v1/admin/tenants") ||
               path.startsWith("/v1/admin/api-keys") ||
               path.startsWith("/v1/auth/validate") ||
               path.startsWith("/v1/admin/audit");
    }

    private boolean requiresApiKey(String path) {
        // Tenant-scoped endpoints per spec: budgets, policies, balances, reservations
        return path.startsWith("/v1/admin/budgets") ||
               path.startsWith("/v1/admin/policies") ||
               path.startsWith("/v1/balances") ||
               path.startsWith("/v1/reservations");
    }

    private boolean validateAdminKey(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String key = request.getHeader(ADMIN_KEY_HEADER);
        if (key == null || key.isBlank()) {
            writeError(request, response, 401, ErrorCode.UNAUTHORIZED, "Missing " + ADMIN_KEY_HEADER + " header");
            return false;
        }
        if (adminApiKey == null || adminApiKey.isBlank()) {
            LOG.error("Admin API key is not configured — rejecting admin request");
            writeError(request, response, 500, ErrorCode.INTERNAL_ERROR, "Server misconfiguration: admin API key not set");
            return false;
        }
        if (!adminApiKey.equals(key)) {
            writeError(request, response, 403, ErrorCode.FORBIDDEN, "Invalid admin API key");
            return false;
        }
        return true;
    }

    private boolean validateApiKey(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String key = request.getHeader(API_KEY_HEADER);
        if (key == null || key.isBlank()) {
            writeError(request, response, 401, ErrorCode.UNAUTHORIZED, "Missing " + API_KEY_HEADER + " header");
            return false;
        }
        ApiKeyValidationResponse validation = apiKeyRepository.validate(key);
        if (!Boolean.TRUE.equals(validation.getValid())) {
            writeError(request, response, 403, ErrorCode.FORBIDDEN, "Invalid API key: " + (validation.getReason() != null ? validation.getReason() : "UNKNOWN"));
            return false;
        }
        // Store validated tenant_id in request attributes for controllers
        request.setAttribute("authenticated_tenant_id", validation.getTenantId());
        request.setAttribute("authenticated_key_id", validation.getKeyId());
        request.setAttribute("authenticated_permissions", validation.getPermissions());
        request.setAttribute("authenticated_scope_filter", validation.getScopeFilter());
        return true;
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, ErrorCode code, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object reqId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        ErrorResponse error = ErrorResponse.builder()
            .error(code)
            .message(message)
            .requestId(reqId != null ? reqId.toString() : UUID.randomUUID().toString())
            .build();
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
