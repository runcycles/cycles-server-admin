package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantCreateRequest;
import io.runcycles.admin.model.tenant.TenantListResponse;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.tenant.TenantUpdateRequest;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/v1/admin/tenants") @Tag(name = "Tenants")
public class TenantController {
    @Autowired private TenantRepository repository;
    @PostMapping @Operation(operationId = "createTenant")
    public ResponseEntity<Tenant> create(@Valid @RequestBody TenantCreateRequest request) {
        var result = repository.create(request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.tenant());
    }
    @GetMapping @Operation(operationId = "listTenants")
    public ResponseEntity<TenantListResponse> list(
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(required = false) String parent_tenant_id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int effectiveLimit = Math.min(limit, 100);
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
    public ResponseEntity<Tenant> update(@PathVariable("tenant_id") String tenantId, @Valid @RequestBody TenantUpdateRequest request) {
        return ResponseEntity.ok(repository.update(tenantId, request));
    }
}
