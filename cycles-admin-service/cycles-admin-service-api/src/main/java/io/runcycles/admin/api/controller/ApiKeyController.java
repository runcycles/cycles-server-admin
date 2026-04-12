package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.auth.*;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import redis.clients.jedis.JedisPool;
@RestController @RequestMapping("/v1/admin/api-keys") @Tag(name = "API Keys")
public class ApiKeyController {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyController.class);
    @Autowired private ApiKeyRepository repository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JedisPool jedisPool;
    @PostMapping @Operation(operationId = "createApiKey")
    public ResponseEntity<ApiKeyCreateResponse> create(@Valid @RequestBody ApiKeyCreateRequest request, HttpServletRequest httpRequest) {
        ApiKeyCreateResponse response = repository.create(request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(request.getTenantId())
            .keyId(response.getKeyId())
            .resourceType("api_key").resourceId(response.getKeyId())
            .operation("createApiKey")
            .status(201)
            .metadata(request.getPermissions() != null
                ? Map.of("name", request.getName(), "permissions", request.getPermissionsAsStrings())
                : Map.of("name", request.getName()))
            .build());
        try {
            eventService.emit(EventType.API_KEY_CREATED, request.getTenantId(), null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataApiKey.builder()
                    .keyId(response.getKeyId()).keyName(request.getName())
                    .newStatus("ACTIVE").permissions(request.getPermissionsAsStrings()).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.status(201).body(response);
    }
    @GetMapping @Operation(operationId = "listApiKeys")
    public ResponseEntity<ApiKeyListResponse> list(
            @RequestParam(required = true) String tenant_id,
            @RequestParam(required = false) ApiKeyStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var keys = repository.list(tenant_id, status, cursor, effectiveLimit);
        var responses = keys.stream().map(ApiKeyResponse::from).collect(Collectors.toList());
        ApiKeyListResponse response = ApiKeyListResponse.builder()
            .keys(responses)
            .hasMore(keys.size() >= effectiveLimit)
            .nextCursor(keys.size() >= effectiveLimit ? keys.get(keys.size() - 1).getKeyId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
    @PatchMapping("/{key_id}") @Operation(operationId = "updateApiKey")
    public ResponseEntity<ApiKeyResponse> update(@PathVariable("key_id") String keyId,
            @Valid @RequestBody ApiKeyUpdateRequest request, HttpServletRequest httpRequest) {
        // Read old key state for change detection
        ApiKey oldKey = null;
        try (var jedis = jedisPool.getResource()) {
            String data = jedis.get("apikey:" + keyId);
            if (data != null) oldKey = objectMapper.readValue(data, ApiKey.class);
        } catch (Exception e) {
            LOG.warn("Failed to read old key for change detection: {}", e.getMessage());
        }

        ApiKey updated = repository.update(keyId, request);
        ApiKeyResponse response = ApiKeyResponse.from(updated);
        java.util.HashMap<String, Object> auditMeta = new java.util.HashMap<>();
        if (request.getPermissions() != null) auditMeta.put("permissions", request.getPermissionsAsStrings());
        if (request.getScopeFilter() != null) auditMeta.put("scope_filter", request.getScopeFilter());
        if (request.getName() != null) auditMeta.put("name", request.getName());
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(response.getTenantId())
            .keyId(keyId)
            .resourceType("api_key").resourceId(keyId)
            .operation("updateApiKey")
            .status(200)
            .metadata(auditMeta.isEmpty() ? null : auditMeta)
            .build());

        // Emit api_key.permissions_changed only if permissions or scope_filter actually changed
        boolean permissionsChanged = request.getPermissions() != null &&
            (oldKey == null || !Objects.equals(oldKey.getPermissions(), updated.getPermissions()));
        boolean scopeFilterChanged = request.getScopeFilter() != null &&
            (oldKey == null || !Objects.equals(oldKey.getScopeFilter(), updated.getScopeFilter()));

        if (permissionsChanged || scopeFilterChanged) {
            try {
                eventService.emit(EventType.API_KEY_PERMISSIONS_CHANGED, response.getTenantId(), null, "cycles-admin",
                    Actor.builder().type(ActorType.ADMIN).build(),
                    objectMapper.convertValue(EventDataApiKey.builder()
                        .keyId(keyId).keyName(updated.getName())
                        .permissions(updated.getPermissions()).build(), Map.class),
                    null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
            } catch (Exception e) {
                LOG.warn("Failed to emit event: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{key_id}") @Operation(operationId = "revokeApiKey")
    public ResponseEntity<ApiKeyResponse> revoke(@PathVariable("key_id") String keyId, @RequestParam(required = false) String reason, HttpServletRequest httpRequest) {
        ApiKeyResponse response = ApiKeyResponse.from(repository.revoke(keyId, reason));
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(response.getTenantId())
            .keyId(keyId)
            .resourceType("api_key").resourceId(keyId)
            .operation("revokeApiKey")
            .status(200)
            .metadata(buildRevokeMeta(response.getName(), reason))
            .build());
        try {
            eventService.emit(EventType.API_KEY_REVOKED, response.getTenantId(), null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataApiKey.builder()
                    .keyId(keyId).newStatus("REVOKED").failureReason(reason).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildRevokeMeta(String name, String reason) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        if (name != null) meta.put("name", name);
        if (reason != null) meta.put("reason", reason);
        return meta.isEmpty() ? null : meta;
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
