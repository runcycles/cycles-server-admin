package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKey;
import io.runcycles.admin.model.auth.ApiKeyCreateRequest;
import io.runcycles.admin.model.auth.ApiKeyCreateResponse;
import io.runcycles.admin.model.auth.ApiKeyStatus;
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
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = true) String tenant_id,
            @RequestParam(required = false) ApiKeyStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        var keys = repository.list(tenant_id, status, cursor, limit);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("keys", keys);
        response.put("has_more", keys.size() >= limit);
        if (!keys.isEmpty() && keys.size() >= limit) {
            response.put("next_cursor", keys.get(keys.size() - 1).getKeyId());
        }
        return ResponseEntity.ok(response);
    }
    @DeleteMapping("/{key_id}") @Operation(operationId = "revokeApiKey")
    public ResponseEntity<ApiKey> revoke(@PathVariable("key_id") String keyId, @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(repository.revoke(keyId, reason));
    }
}
