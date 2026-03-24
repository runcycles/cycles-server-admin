package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.PolicyRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.policy.Policy;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.policy.PolicyListResponse;
import io.runcycles.admin.model.policy.PolicyStatus;
import io.runcycles.admin.model.policy.PolicyUpdateRequest;
import io.runcycles.admin.api.config.ScopeFilterUtil;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
// v0: policies (caps, overage overrides, TTL overrides, rate limits) are stored for future consumption.
// Runtime enforcement by the protocol server is deferred to a future version.
@RestController @RequestMapping("/v1/admin/policies") @Tag(name = "Policies")
public class PolicyController {
    @Autowired private PolicyRepository repository;
    @Autowired private AuditRepository auditRepository;
    @PostMapping @Operation(operationId = "createPolicy")
    public ResponseEntity<Policy> create(@Valid @RequestBody PolicyCreateRequest request, HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, request.getScopePattern());
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        Policy policy = repository.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("createPolicy")
            .status(201)
            .build());
        return ResponseEntity.status(201).body(policy);
    }
    @PatchMapping("/{policy_id}") @Operation(operationId = "updatePolicy")
    public ResponseEntity<Policy> update(@PathVariable("policy_id") String policyId, @Valid @RequestBody PolicyUpdateRequest request, HttpServletRequest httpRequest) {
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        Policy policy = repository.update(tenantId, policyId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("updatePolicy")
            .status(200)
            .build());
        return ResponseEntity.ok(policy);
    }
    @GetMapping @Operation(operationId = "listPolicies")
    public ResponseEntity<PolicyListResponse> list(
            @RequestParam(required = false) String scope_pattern,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope_pattern);
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

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
