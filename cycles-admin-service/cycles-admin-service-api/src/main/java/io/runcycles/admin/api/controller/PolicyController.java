package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.PolicyRepository;
import io.runcycles.admin.model.policy.Policy;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.policy.PolicyStatus;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/admin/policies") @Tag(name = "Policies")
public class PolicyController {
    @Autowired private PolicyRepository repository;
    @PostMapping @Operation(operationId = "createPolicy")
    public ResponseEntity<Policy> create(@Valid @RequestBody PolicyCreateRequest request) {
        return ResponseEntity.status(201).body(repository.create(request));
    }
    @GetMapping @Operation(operationId = "listPolicies")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String scope_pattern,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        var policies = repository.list(scope_pattern, status, cursor, limit);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("policies", policies);
        response.put("has_more", policies.size() >= limit);
        if (!policies.isEmpty() && policies.size() >= limit) {
            response.put("next_cursor", policies.get(policies.size() - 1).getPolicyId());
        }
        return ResponseEntity.ok(response);
    }
}
