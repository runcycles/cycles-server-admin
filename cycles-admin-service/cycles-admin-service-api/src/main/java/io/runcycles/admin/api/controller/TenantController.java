package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantCreateRequest;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.tenant.TenantUpdateRequest;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/admin/tenants") @Tag(name = "Tenants")
public class TenantController {
    @Autowired private TenantRepository repository;
    @PostMapping @Operation(operationId = "createTenant")
    public ResponseEntity<Tenant> create(@Valid @RequestBody TenantCreateRequest request) {
        return ResponseEntity.status(201).body(repository.create(request));
    }
    @GetMapping @Operation(operationId = "listTenants")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) TenantStatus status, @RequestParam(defaultValue = "50") int limit) {
        var tenants = repository.list(status, limit);
        return ResponseEntity.ok(Map.of("tenants", tenants, "has_more", tenants.size() >= limit));
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
