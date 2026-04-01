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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String ADMIN_KEY_HEADER = "X-Admin-API-Key";
    private static final String API_KEY_HEADER = "X-Cycles-API-Key";

    // Endpoint path+method → required permission per admin governance spec
    private static final Map<String, String> PERMISSION_MAP = Map.ofEntries(
        Map.entry("POST:/v1/admin/budgets/fund", "admin:write"),
        Map.entry("POST:/v1/admin/budgets", "admin:write"),
        Map.entry("GET:/v1/admin/budgets", "admin:read"),
        // PATCH /v1/admin/budgets uses AdminKeyAuth per spec v0.1.25 — no permission map entry needed
        Map.entry("POST:/v1/admin/policies", "admin:write"),
        Map.entry("PATCH:/v1/admin/policies", "admin:write"),
        Map.entry("GET:/v1/admin/policies", "admin:read"),
        Map.entry("GET:/v1/balances", "balances:read"),
        Map.entry("GET:/v1/reservations", "reservations:list"),
        Map.entry("POST:/v1/webhooks", "webhooks:write"),
        Map.entry("GET:/v1/webhooks", "webhooks:read"),
        Map.entry("PATCH:/v1/webhooks", "webhooks:write"),
        Map.entry("DELETE:/v1/webhooks", "webhooks:write"),
        Map.entry("GET:/v1/events", "events:read")
    );

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

        // Determine which auth is required based on path (and method for PATCH /v1/admin/budgets)
        if (requiresAdminKey(method, path)) {
            return validateAdminKey(request, response);
        } else if (requiresApiKey(path)) {
            return validateApiKey(request, response);
        }

        return true;
    }

    private boolean requiresAdminKey(String path) {
        return requiresAdminKey(null, path);
    }

    private boolean requiresAdminKey(String method, String path) {
        // Admin endpoints per spec: tenants, api-keys, auth/validate, audit, webhooks, events, config
        // Also: PATCH /v1/admin/budgets requires AdminKeyAuth per spec v0.1.25
        if (path.startsWith("/v1/admin/tenants") ||
               path.startsWith("/v1/admin/api-keys") ||
               path.startsWith("/v1/auth/validate") ||
               path.startsWith("/v1/admin/audit") ||
               path.startsWith("/v1/admin/webhooks") ||
               path.startsWith("/v1/admin/events") ||
               path.startsWith("/v1/admin/config")) {
            return true;
        }
        // PATCH /v1/admin/budgets requires AdminKeyAuth per spec v0.1.25
        if ("PATCH".equals(method) && path.startsWith("/v1/admin/budgets")) {
            return true;
        }
        return false;
    }

    private boolean requiresApiKey(String path) {
        // Tenant-scoped endpoints per spec: budgets, policies, balances, reservations
        return path.startsWith("/v1/admin/budgets") ||
               path.startsWith("/v1/admin/policies") ||
               path.startsWith("/v1/balances") ||
               path.startsWith("/v1/reservations") ||
               path.startsWith("/v1/webhooks") ||
               path.startsWith("/v1/events");
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
        if (!MessageDigest.isEqual(adminApiKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            writeError(request, response, 401, ErrorCode.UNAUTHORIZED, "Invalid admin API key");
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
        // Check permissions before allowing the request
        String requiredPermission = resolveRequiredPermission(request.getMethod(), request.getRequestURI());
        if (requiredPermission != null) {
            List<String> permissions = validation.getPermissions();
            if (permissions == null || !permissions.contains(requiredPermission)) {
                writeError(request, response, 403, ErrorCode.INSUFFICIENT_PERMISSIONS,
                    "API key lacks required permission: " + requiredPermission);
                return false;
            }
        }

        // Store validated tenant_id in request attributes for controllers
        request.setAttribute("authenticated_tenant_id", validation.getTenantId());
        request.setAttribute("authenticated_key_id", validation.getKeyId());
        request.setAttribute("authenticated_permissions", validation.getPermissions());
        request.setAttribute("authenticated_scope_filter", validation.getScopeFilter());
        return true;
    }

    /**
     * Resolve the required permission for the given method + path.
     * Matches longest path prefix first (e.g. /v1/admin/budgets/fund before /v1/admin/budgets).
     */
    private String resolveRequiredPermission(String method, String path) {
        // Try exact key first, then strip path segments for prefix matching
        // (handles paths like /v1/admin/policies/{id})
        String key = method + ":" + path;
        if (PERMISSION_MAP.containsKey(key)) {
            return PERMISSION_MAP.get(key);
        }
        // Walk up the path to match prefix (e.g. PATCH:/v1/admin/policies/pol_123 → PATCH:/v1/admin/policies)
        int lastSlash = path.lastIndexOf('/');
        while (lastSlash > 0) {
            path = path.substring(0, lastSlash);
            key = method + ":" + path;
            if (PERMISSION_MAP.containsKey(key)) {
                return PERMISSION_MAP.get(key);
            }
            lastSlash = path.lastIndexOf('/');
        }
        return null;
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
