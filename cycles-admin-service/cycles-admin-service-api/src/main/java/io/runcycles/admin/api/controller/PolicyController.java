package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.PolicyRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.policy.Policy;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.policy.PolicyListResponse;
import io.runcycles.admin.model.policy.PolicyStatus;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/v1/admin/policies") @Tag(name = "Policies")
public class PolicyController {
    @Autowired private PolicyRepository repository;
    @Autowired private AuditRepository auditRepository;
    @PostMapping @Operation(operationId = "createPolicy")
    public ResponseEntity<Policy> create(@Valid @RequestBody PolicyCreateRequest request, HttpServletRequest httpRequest) {
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        Policy policy = repository.create(tenantId, request);
        auditRepository.log(AuditLogEntry.builder()
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("createPolicy")
            .status(201)
            .build());
        return ResponseEntity.status(201).body(policy);
    }
    @GetMapping @Operation(operationId = "listPolicies")
    public ResponseEntity<PolicyListResponse> list(
            @RequestParam(required = false) String scope_pattern,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var policies = repository.list(tenantId, scope_pattern, status, cursor, effectiveLimit);
        PolicyListResponse response = PolicyListResponse.builder()
            .policies(policies)
            .hasMore(policies.size() >= effectiveLimit)
            .nextCursor(policies.size() >= effectiveLimit ? policies.get(policies.size() - 1).getPolicyId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
}
