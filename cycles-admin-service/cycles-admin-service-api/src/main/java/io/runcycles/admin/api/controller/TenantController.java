package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantCreateRequest;
import io.runcycles.admin.model.tenant.TenantListResponse;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.tenant.TenantUpdateRequest;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.model.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController @RequestMapping("/v1/admin/tenants") @Tag(name = "Tenants")
public class TenantController {
    @Autowired private TenantRepository repository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @PostMapping @Operation(operationId = "createTenant")
    public ResponseEntity<Tenant> create(@Valid @RequestBody TenantCreateRequest request, HttpServletRequest httpRequest) {
        var result = repository.create(request);
        int httpStatus = result.created() ? 201 : 200;
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(request.getTenantId())
            .operation("createTenant")
            .status(httpStatus)
            .build());
        try {
            eventService.emit(EventType.TENANT_CREATED, request.getTenantId(), null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataTenantLifecycle.builder()
                    .tenantId(request.getTenantId()).newStatus("ACTIVE").changedFields(List.of()).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            // Non-blocking: don't break the business operation
        }
        return ResponseEntity.status(httpStatus).body(result.tenant());
    }
    @GetMapping @Operation(operationId = "listTenants")
    public ResponseEntity<TenantListResponse> list(
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(required = false) String parent_tenant_id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var tenants = repository.list(status, parent_tenant_id, cursor, effectiveLimit);
        TenantListResponse response = TenantListResponse.builder()
            .tenants(tenants)
            .hasMore(tenants.size() >= effectiveLimit)
            .nextCursor(tenants.size() >= effectiveLimit ? tenants.get(tenants.size() - 1).getTenantId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
    @GetMapping("/{tenant_id}") @Operation(operationId = "getTenant")
    public ResponseEntity<Tenant> get(@PathVariable("tenant_id") String tenantId) {
        return ResponseEntity.ok(repository.get(tenantId));
    }
    @PatchMapping("/{tenant_id}") @Operation(operationId = "updateTenant")
    public ResponseEntity<Tenant> update(@PathVariable("tenant_id") String tenantId, @Valid @RequestBody TenantUpdateRequest request, HttpServletRequest httpRequest) {
        Tenant updated = repository.update(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .operation("updateTenant")
            .status(200)
            .build());
        try {
            EventType eventType = EventType.TENANT_UPDATED;
            if (request.getStatus() != null) {
                switch (request.getStatus()) {
                    case SUSPENDED: eventType = EventType.TENANT_SUSPENDED; break;
                    case ACTIVE: eventType = EventType.TENANT_REACTIVATED; break;
                    case CLOSED: eventType = EventType.TENANT_CLOSED; break;
                    default: break;
                }
            }
            eventService.emit(eventType, tenantId, null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataTenantLifecycle.builder()
                    .tenantId(tenantId)
                    .newStatus(updated.getStatus() != null ? updated.getStatus().name() : null)
                    .changedFields(List.of()).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            // Non-blocking: don't break the business operation
        }
        return ResponseEntity.ok(updated);
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
