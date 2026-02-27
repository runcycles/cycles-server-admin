package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKey;
import io.runcycles.admin.model.auth.ApiKeyCreateRequest;
import io.runcycles.admin.model.auth.ApiKeyCreateResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/admin/api-keys") @Tag(name = "API Keys")
public class ApiKeyController {
    @Autowired private ApiKeyRepository repository;
    @PostMapping @Operation(operationId = "createApiKey")
    public ResponseEntity<ApiKeyCreateResponse> create(@Valid @RequestBody ApiKeyCreateRequest request) {
        return ResponseEntity.status(201).body(repository.create(request));
    }
    @GetMapping @Operation(operationId = "listApiKeys")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = true) String tenant_id) {
        return ResponseEntity.ok(Map.of("keys", repository.list(tenant_id), "has_more", false));
    }
    @DeleteMapping("/{key_id}") @Operation(operationId = "revokeApiKey")
    public ResponseEntity<ApiKey> revoke(@PathVariable("key_id") String keyId, @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(repository.revoke(keyId, reason));
    }
}
