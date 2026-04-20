package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.auth.*;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
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
import java.util.Set;
import java.util.stream.Collectors;
import redis.clients.jedis.JedisPool;
@RestController @RequestMapping("/v1/admin/api-keys") @Tag(name = "API Keys")
public class ApiKeyController {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyController.class);
    // Per spec v0.1.25.20. Keeping the whitelist in the controller (not
    // the repo) keeps the 400/validation surface at the HTTP boundary.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "key_id", "name", "tenant_id", "status", "created_at", "expires_at");
    private static final String DEFAULT_SORT_FIELD = "created_at";
    @Autowired private ApiKeyRepository repository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JedisPool jedisPool;
    @Autowired private TerminalOwnerMutationGuard mutationGuard;
    @PostMapping @Operation(operationId = "createApiKey")
    public ResponseEntity<ApiKeyCreateResponse> create(@Valid @RequestBody ApiKeyCreateRequest request, HttpServletRequest httpRequest) {
        mutationGuard.assertTenantOpen(request.getTenantId());
        ApiKeyCreateResponse response = repository.create(request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(request.getTenantId())
            .keyId(response.getKeyId())
            .resourceType("api_key").resourceId(response.getKeyId())
            .operation("createApiKey")
            .status(201)
            .metadata(request.getPermissions() != null
                ? Map.of("name", request.getName(), "permissions", request.getPermissions())
                : Map.of("name", request.getName()))
            .build());
        try {
            eventService.emit(EventType.API_KEY_CREATED, request.getTenantId(), null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataApiKey.builder()
                    .keyId(response.getKeyId()).keyName(request.getName())
                    .newStatus(ApiKeyStatus.ACTIVE).permissions(request.getPermissions()).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.status(201).body(response);
    }
    @GetMapping @Operation(operationId = "listApiKeys")
    public ResponseEntity<ApiKeyListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) ApiKeyStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_dir) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        SortSpec sortSpec = parseSortSpec(sort_by, sort_dir);
        String searchNorm = parseSearch(search);
        // Per governance spec v0.1.25.18: `tenant_id` is now optional under
        // AdminKeyAuth. When absent, list keys across every tenant; the
        // cursor carries "{tenantId}|{keyId}" so a follow-up page resumes
        // inside the correct tenant.
        java.util.List<ApiKey> keys;
        boolean crossTenant;
        if (tenant_id != null && !tenant_id.isBlank()) {
            crossTenant = false;
            keys = repository.list(tenant_id, status, cursor, effectiveLimit, sortSpec, searchNorm);
        } else {
            crossTenant = true;
            keys = repository.listAllTenants(status, cursor, effectiveLimit, sortSpec, searchNorm);
        }
        var responses = keys.stream().map(ApiKeyResponse::from).collect(Collectors.toList());
        String nextCursor = null;
        if (keys.size() >= effectiveLimit) {
            ApiKey last = keys.get(keys.size() - 1);
            nextCursor = crossTenant
                ? last.getTenantId() + "|" + last.getKeyId()
                : last.getKeyId();
        }
        ApiKeyListResponse response = ApiKeyListResponse.builder()
            .keys(responses)
            .hasMore(keys.size() >= effectiveLimit)
            .nextCursor(nextCursor)
            .build();
        return ResponseEntity.ok(response);
    }

    /**
     * Parse sort_by / sort_dir query params into a validated SortSpec.
     * See TenantController.parseSortSpec for the rationale — identical
     * contract here: unknown sort_by / sort_dir → 400 INVALID_REQUEST,
     * omitted values use the endpoint's canonical defaults.
     */
    private SortSpec parseSortSpec(String sortBy, String sortDir) {
        SortDirection direction;
        try {
            direction = SortDirection.fromWire(sortDir);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
        try {
            return SortSpec.resolve(sortBy, direction, ALLOWED_SORT_FIELDS, DEFAULT_SORT_FIELD);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
    }

    private String parseSearch(String raw) {
        try {
            return SearchSpec.resolve(raw);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
    }
    @PatchMapping("/{key_id}") @Operation(operationId = "updateApiKey")
    public ResponseEntity<ApiKeyResponse> update(@PathVariable("key_id") String keyId,
            @Valid @RequestBody ApiKeyUpdateRequest request, HttpServletRequest httpRequest) {
        // Read old key state for change detection + owner-tenant resolution (Rule 2).
        ApiKey oldKey = null;
        try (var jedis = jedisPool.getResource()) {
            String data = jedis.get("apikey:" + keyId);
            if (data != null) oldKey = objectMapper.readValue(data, ApiKey.class);
        } catch (Exception e) {
            LOG.warn("Failed to read old key for change detection: {}", e.getMessage());
        }
        if (oldKey != null) mutationGuard.assertTenantOpen(oldKey.getTenantId());

        ApiKey updated = repository.update(keyId, request);
        ApiKeyResponse response = ApiKeyResponse.from(updated);
        java.util.HashMap<String, Object> auditMeta = new java.util.HashMap<>();
        if (request.getPermissions() != null) auditMeta.put("permissions", request.getPermissions());
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
        // Resolve the owning tenant pre-revoke so Rule 2 fires before the write;
        // tolerant of missing keys so the repository's own NOT_FOUND path stays
        // authoritative for that case.
        mutationGuard.assertTenantOpen(resolveKeyTenantId(keyId));
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
                    .keyId(keyId).newStatus(ApiKeyStatus.REVOKED).failureReason(reason).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    private String resolveKeyTenantId(String keyId) {
        try (var jedis = jedisPool.getResource()) {
            String data = jedis.get("apikey:" + keyId);
            if (data == null) return null;
            return objectMapper.readValue(data, ApiKey.class).getTenantId();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> buildRevokeMeta(String name, String reason) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        if (name != null) meta.put("name", name);
        if (reason != null) meta.put("reason", reason);
        return meta.isEmpty() ? null : meta;
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(attr(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE))
            .traceId(attr(request, TraceContextFilter.TRACE_ID_ATTRIBUTE))
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v != null ? v.toString() : null;
    }
}
