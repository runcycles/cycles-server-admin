package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.PolicyRepository;
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
    @PostMapping @Operation(operationId = "createPolicy")
    public ResponseEntity<Policy> create(@Valid @RequestBody PolicyCreateRequest request, HttpServletRequest httpRequest) {
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        return ResponseEntity.status(201).body(repository.create(tenantId, request));
    }
    @GetMapping @Operation(operationId = "listPolicies")
    public ResponseEntity<PolicyListResponse> list(
            @RequestParam(required = false) String scope_pattern,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        int effectiveLimit = Math.min(limit, 100);
        var policies = repository.list(tenantId, scope_pattern, status, cursor, effectiveLimit);
        PolicyListResponse response = PolicyListResponse.builder()
            .policies(policies)
            .hasMore(policies.size() >= effectiveLimit)
            .nextCursor(policies.size() >= effectiveLimit ? policies.get(policies.size() - 1).getPolicyId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
}
