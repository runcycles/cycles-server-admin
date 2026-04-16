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
import java.util.Set;
import java.util.UUID;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String ADMIN_KEY_HEADER = "X-Admin-API-Key";
    private static final String API_KEY_HEADER = "X-Cycles-API-Key";

    // Explicit allowlist of ApiKeyAuth endpoints that also accept AdminKeyAuth.
    // Each entry MUST be reflected in the governance spec and API docs.
    // Uses exact method:path matching — no prefix matching, no wildcards.
    private static final Set<String> ADMIN_ALLOWED_ENDPOINTS = Set.of(
        "GET:/v1/admin/budgets",
        "GET:/v1/admin/budgets/lookup",
        "GET:/v1/admin/policies",
        "POST:/v1/admin/budgets/fund",
        // v0.1.25.14: dual-auth on create writes added per spec v0.1.25.13.
        // Admin operators can now provision budgets and policies on behalf
        // of tenants. Body MUST include tenant_id when admin-key —
        // controllers enforce + audit-log records actor_type=
        // ADMIN_ON_BEHALF_OF for these calls.
        "POST:/v1/admin/budgets",
        "POST:/v1/admin/policies",
        // v0.1.25.16: dual-auth on tenant-scoped webhook LIST per spec
        // v0.1.25.14. Admin operators need to inspect a tenant's webhook
        // subscriptions during incident response without holding the
        // tenant's API key. Per-subscription paths (id in URL) use the
        // prefix matcher below.
        "GET:/v1/webhooks",
        // v0.1.25.19: dual-auth on GET /v1/auth/introspect per spec
        // v0.1.25.15. AdminKey returns admin-shape (auth_type=admin,
        // permissions=["*"], no tenant_id/scope_filter). Tenant ApiKey
        // returns tenant-shape (auth_type=tenant, concrete permissions,
        // tenant_id, optional scope_filter) with capabilities derived
        // per the NORMATIVE table — admin-plane caps (view_tenants,
        // view_api_keys, view_audit, view_overview, manage_tenants,
        // manage_api_keys) forced to false under tenant auth.
        "GET:/v1/auth/introspect"
    );

    // Method:path-prefix entries for dual-auth where the path includes a
    // resource id (e.g. PATCH /v1/admin/policies/{policy_id}). Exact-match
    // lookup in ADMIN_ALLOWED_ENDPOINTS doesn't help here because every
    // request has a different concrete id. policy_id pins the owning
    // tenant in the persistence layer, so no tenant_id body field is
    // needed for admin-on-behalf-of updates. (v0.1.25.14, spec v0.1.25.13.)
    private static final Set<String> ADMIN_ALLOWED_PREFIXES = Set.of(
        "PATCH:/v1/admin/policies/",
        // v0.1.25.16: dual-auth on tenant-scoped per-subscription webhook
        // endpoints per spec v0.1.25.14. Subscription id pins the owning
        // tenant in the persistence layer (controller resolves from the
        // subscription record under admin auth), so no tenant query/body
        // field is needed on these. Covers:
        //   GET    /v1/webhooks/{id}
        //   GET    /v1/webhooks/{id}/deliveries
        //   PATCH  /v1/webhooks/{id}
        //   DELETE /v1/webhooks/{id}
        //   POST   /v1/webhooks/{id}/test
        // POST /v1/webhooks (create) is NOT dual-auth — prefix requires
        // a non-empty suffix after the trailing slash, so a bare
        // "POST:/v1/webhooks" (no id) correctly does not match.
        "GET:/v1/webhooks/",
        "PATCH:/v1/webhooks/",
        "DELETE:/v1/webhooks/",
        "POST:/v1/webhooks/"
    );

    // Endpoint path+method → required permission per admin governance spec
    private static final Map<String, String> PERMISSION_MAP = Map.ofEntries(
        Map.entry("POST:/v1/admin/budgets/fund", "budgets:write"),
        Map.entry("POST:/v1/admin/budgets", "budgets:write"),
        Map.entry("GET:/v1/admin/budgets", "budgets:read"),
        // PATCH /v1/admin/budgets uses AdminKeyAuth per spec v0.1.25 — no permission map entry needed
        Map.entry("POST:/v1/admin/policies", "policies:write"),
        Map.entry("PATCH:/v1/admin/policies", "policies:write"),
        Map.entry("GET:/v1/admin/policies", "policies:read"),
        Map.entry("GET:/v1/balances", "balances:read"),
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
            // Check dual-auth allowlist: some ApiKeyAuth endpoints also accept AdminKeyAuth.
            // Two layers — exact match for fixed paths (e.g. POST /v1/admin/budgets/fund),
            // and prefix match for paths with a resource id (e.g. PATCH /v1/admin/policies/{id}).
            //
            // Defense in depth: reject any request whose path contains traversal
            // segments before the dual-auth match. Tomcat's connector already
            // rejects "../" requests by default (RFC 3986 strict mode), but if
            // a future deployment relaxes that or sits behind a proxy that
            // forwards the raw URI, we don't want a request like
            // "PATCH /v1/admin/policies/../api-keys/k_1" to pass the interceptor's
            // prefix matcher and then get re-routed by Spring's dispatcher to a
            // different endpoint (auth-context confusion). Spring uses the
            // servlet path for dispatch; we now match against that same value
            // and additionally short-circuit on any traversal segment.
            String dispatchPath = request.getServletPath();
            if (dispatchPath != null && (dispatchPath.contains("/../") ||
                    dispatchPath.contains("/./") || dispatchPath.endsWith("/.."))) {
                writeError(request, response, 400,
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Path traversal segments not allowed");
                return false;
            }
            // Use the dispatch path (Spring-normalized) for matching so the
            // interceptor and the dispatcher always see the same URL.
            String matchPath = (dispatchPath != null && !dispatchPath.isEmpty()) ? dispatchPath : path;
            String normalizedPath = matchPath.endsWith("/") ? matchPath.substring(0, matchPath.length() - 1) : matchPath;
            String lookupKey = method + ":" + normalizedPath;
            if (hasAdminKeyHeader(request) && (
                    ADMIN_ALLOWED_ENDPOINTS.contains(lookupKey) ||
                    matchesAdminPrefix(method, normalizedPath))) {
                return validateAdminKey(request, response);
            }
            return validateApiKey(request, response);
        }

        return true;
    }

    private boolean requiresAdminKey(String path) {
        return requiresAdminKey(null, path);
    }

    private boolean requiresAdminKey(String method, String path) {
        // Admin endpoints per spec: tenants, api-keys, auth/validate, auth/introspect, audit, webhooks, events, config, overview
        // Also: PATCH /v1/admin/budgets, freeze, unfreeze require AdminKeyAuth
        // v0.1.25.19: /v1/auth/introspect moves off admin-only to the
        // dual-auth path (see ADMIN_ALLOWED_ENDPOINTS + requiresApiKey).
        if (path.startsWith("/v1/admin/tenants") ||
               path.startsWith("/v1/admin/api-keys") ||
               path.startsWith("/v1/auth/validate") ||
               path.startsWith("/v1/admin/audit") ||
               path.startsWith("/v1/admin/webhooks") ||
               path.startsWith("/v1/admin/events") ||
               path.startsWith("/v1/admin/overview") ||
               path.startsWith("/v1/admin/config")) {
            return true;
        }
        // PATCH /v1/admin/budgets requires AdminKeyAuth per spec v0.1.25
        if ("PATCH".equals(method) && path.startsWith("/v1/admin/budgets")) {
            return true;
        }
        // POST /v1/admin/budgets/freeze and /unfreeze require AdminKeyAuth
        if ("POST".equals(method) && (path.startsWith("/v1/admin/budgets/freeze") ||
                                       path.startsWith("/v1/admin/budgets/unfreeze"))) {
            return true;
        }
        return false;
    }

    private boolean matchesAdminPrefix(String method, String normalizedPath) {
        // Entry format: "PATCH:/v1/admin/policies/" (trailing slash).
        // Request must start with the entry AND have a non-empty suffix
        // after it — that suffix is the resource id. Bare-prefix requests
        // (no id) wouldn't be valid REST anyway, but the length check
        // guards against accidentally matching them.
        String reqKey = method + ":" + normalizedPath;
        for (String entry : ADMIN_ALLOWED_PREFIXES) {
            if (reqKey.startsWith(entry) && reqKey.length() > entry.length()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAdminKeyHeader(HttpServletRequest request) {
        String key = request.getHeader(ADMIN_KEY_HEADER);
        return key != null && !key.isBlank();
    }

    private boolean requiresApiKey(String path) {
        // Tenant-scoped endpoints per spec: budgets, policies, balances, reservations.
        // v0.1.25.19: /v1/auth/introspect is dual-auth (spec v0.1.25.15) —
        // listed here so the interceptor routes tenant keys through the
        // ApiKey path; AdminKey variant is picked up via the
        // ADMIN_ALLOWED_ENDPOINTS dual-auth check inside preHandle.
        return path.startsWith("/v1/admin/budgets") ||
               path.startsWith("/v1/admin/policies") ||
               path.startsWith("/v1/balances") ||
               path.startsWith("/v1/reservations") ||
               path.startsWith("/v1/webhooks") ||
               path.startsWith("/v1/events") ||
               path.startsWith("/v1/auth/introspect");
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
            if (permissions == null || !hasPermission(permissions, requiredPermission)) {
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

    /**
     * Check if the permission list satisfies the required permission.
     * Supports backward-compatible wildcards: admin:read implies any *:read,
     * admin:write implies any *:write (spec v0.1.25.6 backward compatibility).
     */
    private boolean hasPermission(List<String> permissions, String required) {
        if (permissions.contains(required)) return true;
        if (required.endsWith(":read") && permissions.contains("admin:read")) return true;
        if (required.endsWith(":write") && permissions.contains("admin:write")) return true;
        return false;
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
