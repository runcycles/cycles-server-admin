package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationRequest;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/v1/auth") @Tag(name = "Authentication")
public class AuthController {
    @Autowired private ApiKeyRepository repository;
    @PostMapping("/validate") @Operation(operationId = "validateApiKey")
    public ResponseEntity<ApiKeyValidationResponse> validate(@Valid @RequestBody ApiKeyValidationRequest request) {
        return ResponseEntity.ok(repository.validate(request.getKeySecret()));
    }
}
