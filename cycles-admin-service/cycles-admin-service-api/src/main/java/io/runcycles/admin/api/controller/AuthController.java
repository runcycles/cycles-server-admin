package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationRequest;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.auth.AuthIntrospectResponse;
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
                LOG.warn("Failed to emit event: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/introspect") @Operation(operationId = "introspectAuth", tags = {"Dashboard"})
    public ResponseEntity<AuthIntrospectResponse> introspect(HttpServletRequest request) {
        // Admin key already validated by interceptor (v1: AdminKeyAuth only)
        List<String> permissions = List.of("*");
        return ResponseEntity.ok(AuthIntrospectResponse.builder()
                .authenticated(true)
                .authType("admin")
                .permissions(permissions)
                .capabilities(deriveCapabilities(permissions))
                .build());
    }

    private Map<String, Boolean> deriveCapabilities(List<String> permissions) {
        boolean isAdmin = permissions.contains("*");
        return Map.of(
                "view_overview",  isAdmin,
                "view_budgets",   isAdmin || permissions.contains("admin:read") || permissions.contains("admin:budgets:read"),
                "view_events",    isAdmin || permissions.contains("events:read") || permissions.contains("admin:events:read"),
                "view_webhooks",  isAdmin || permissions.contains("webhooks:read") || permissions.contains("admin:webhooks:read"),
                "view_audit",     isAdmin || permissions.contains("admin:audit:read"),
                "view_tenants",   isAdmin || permissions.contains("admin:tenants:read"),
                "view_api_keys",  isAdmin || permissions.contains("admin:apikeys:read"),
                "view_policies",  isAdmin || permissions.contains("admin:read") || permissions.contains("admin:policies:read")
        );
    }
}
