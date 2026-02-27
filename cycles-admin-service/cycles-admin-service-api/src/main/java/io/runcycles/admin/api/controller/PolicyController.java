package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.PolicyRepository;
import io.runcycles.admin.model.policy.Policy;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
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
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(Map.of("policies", repository.list(), "has_more", false));
    }
}
