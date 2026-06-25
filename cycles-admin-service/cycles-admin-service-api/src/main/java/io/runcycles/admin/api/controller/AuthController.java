package io.runcycles.admin.api.controller;
import static io.runcycles.admin.api.logging.LogSanitizer.safe;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationRequest;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.auth.AuthIntrospectResponse;
import io.runcycles.admin.model.auth.Capabilities;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.model.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController @RequestMapping("/v1/auth") @Tag(name = "Authentication")
public class AuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    @Autowired private ApiKeyRepository repository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @PostMapping("/validate") @Operation(operationId = "validateApiKey")
    public ResponseEntity<ApiKeyValidationResponse> validate(@Valid @RequestBody ApiKeyValidationRequest request, HttpServletRequest httpRequest) {
        ApiKeyValidationResponse response = repository.validate(request.getKeySecret());
        if (!response.isValid()) {
            try {
                eventService.emit(EventType.API_KEY_AUTH_FAILED, response.getTenantId(), null, "cycles-admin",
                    Actor.builder().type(ActorType.API_KEY)
                        .sourceIp(httpRequest.getRemoteAddr()).build(),
                    objectMapper.convertValue(EventDataApiKey.builder()
                        .keyId(response.getKeyId()).failureReason(response.getReason())
                        .sourceIp(httpRequest.getRemoteAddr()).build(), Map.class),
                    null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
            } catch (Exception e) {
                LOG.warn("Failed to emit API key auth failure event: event_type={} tenant_id={} key_id={} reason={} request_id={} trace_id={} source_ip={} error={}",
                    EventType.API_KEY_AUTH_FAILED.getValue(), safe(response.getTenantId()), safe(response.getKeyId()),
                    response.getReason(), attr(httpRequest, "requestId"), attr(httpRequest, "traceId"),
                    safe(httpRequest.getRemoteAddr()), safe(e.getMessage()), e);
            }
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Introspect the authenticated credential (spec v0.1.25.15, dual-auth).
     *
     * AdminKeyAuth → admin-shape: auth_type="admin", permissions=["*"], all
     * 15 capabilities true, no tenant_id, no scope_filter.
     *
     * ApiKeyAuth → tenant-shape: auth_type="tenant", concrete permissions
     * from the key, tenant_id populated, scope_filter populated when
     * non-empty, capabilities derived per the NORMATIVE table
     * (yaml:3105-3166) with the six admin-plane caps forced to false
     * regardless of any admin:* permissions on the key.
     *
     * Dual-auth routing is wired in {@code AuthInterceptor}:
     * {@code GET:/v1/auth/introspect} is in ADMIN_ALLOWED_ENDPOINTS and
     * {@code /v1/auth/introspect} is in requiresApiKey(). When the
     * admin key header is present and valid, the interceptor stamps no
     * authenticated_* attributes and we take the admin branch; when a
     * tenant api key header is present and valid, the interceptor stamps
     * authenticated_tenant_id / authenticated_permissions /
     * authenticated_scope_filter and we take the tenant branch.
     */
    @GetMapping("/introspect") @Operation(operationId = "introspectAuth", tags = {"Dashboard"})
    public ResponseEntity<AuthIntrospectResponse> introspect(HttpServletRequest request) {
        Object tenantIdAttr = request.getAttribute("authenticated_tenant_id");
        if (tenantIdAttr != null) {
            @SuppressWarnings("unchecked")
            List<String> permissions = (List<String>) request.getAttribute("authenticated_permissions");
            @SuppressWarnings("unchecked")
            List<String> scopeFilter = (List<String>) request.getAttribute("authenticated_scope_filter");
            if (permissions == null) {
                permissions = List.of();
            }
            // scope_filter serializes via @JsonInclude(NON_EMPTY) — absent or
            // empty at source → absent in response.
            return ResponseEntity.ok(AuthIntrospectResponse.builder()
                    .authenticated(true)
                    .authType("tenant")
                    .permissions(permissions)
                    .tenantId(tenantIdAttr.toString())
                    .scopeFilter(scopeFilter)
                    .capabilities(deriveTenantCapabilities(permissions))
                    .build());
        }
        // AdminKeyAuth path — unconstrained, all caps true.
        return ResponseEntity.ok(AuthIntrospectResponse.builder()
                .authenticated(true)
                .authType("admin")
                .permissions(List.of("*"))
                .capabilities(allCapabilitiesTrue())
                .build());
    }

    /**
     * Admin-shape capabilities: every view_* and manage_* flag true.
     * Spec yaml:3157-3159: "Under auth_type=admin the server SHOULD return
     * all capabilities as true (permissions=[\"*\"] being unconstrained)".
     */
    static Capabilities allCapabilitiesTrue() {
        return Capabilities.builder()
                .viewOverview(true)
                .viewBudgets(true)
                .viewEvents(true)
                .viewWebhooks(true)
                .viewAudit(true)
                .viewTenants(true)
                .viewApiKeys(true)
                .viewPolicies(true)
                .viewReservations(true)
                .manageBudgets(true)
                .managePolicies(true)
                .manageWebhooks(true)
                .manageTenants(true)
                .manageApiKeys(true)
                .manageReservations(true)
                .build();
    }

    /**
     * Derive capabilities under tenant auth per the NORMATIVE table at
     * yaml:3115-3148. Admin-plane caps (view_tenants, view_api_keys,
     * view_audit, view_overview, manage_tenants, manage_api_keys) are
     * hard-coded to false regardless of any admin:* permissions the key
     * holds — yaml:3138-3147 is explicit: "The admin:read / admin:write
     * wildcard semantics apply to per-endpoint access control, NOT to
     * dashboard capability gating. This decoupling prevents accidental
     * admin-UI elevation via legacy admin-permission tenant keys and is
     * intentional."
     */
    static Capabilities deriveTenantCapabilities(List<String> perms) {
        return Capabilities.builder()
                // Admin-plane: forced false under tenant auth.
                .viewOverview(false)
                .viewTenants(false)
                .viewApiKeys(false)
                .viewAudit(false)
                .manageTenants(false)
                .manageApiKeys(false)
                // Tenant-plane: derived per table.
                .viewBudgets(hasAny(perms, "budgets:read", "admin:read", "admin:budgets:read"))
                .viewPolicies(hasAny(perms, "policies:read", "admin:read", "admin:policies:read"))
                .viewWebhooks(hasAny(perms, "webhooks:read", "admin:read", "admin:webhooks:read"))
                .viewEvents(hasAny(perms, "events:read", "admin:read", "admin:events:read"))
                .viewReservations(hasAny(perms,
                        "reservations:list", "reservations:create",
                        "reservations:commit", "reservations:release",
                        "reservations:extend", "admin:read"))
                .manageBudgets(hasAny(perms, "budgets:write", "admin:write", "admin:budgets:write"))
                .managePolicies(hasAny(perms, "policies:write", "admin:write", "admin:policies:write"))
                .manageWebhooks(hasAny(perms, "webhooks:write", "admin:write", "admin:webhooks:write"))
                .manageReservations(hasAny(perms,
                        "reservations:create", "reservations:commit",
                        "reservations:release", "reservations:extend",
                        "admin:write"))
                .build();
    }

    private static boolean hasAny(List<String> perms, String... needles) {
        if (perms == null) {
            return false;
        }
        for (String n : needles) {
            if (perms.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String attr(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return safe(value);
    }
}
