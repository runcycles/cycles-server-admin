package io.runcycles.admin.api.controller;

import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/v1/admin/config") @Tag(name = "Webhooks")
public class WebhookSecurityConfigController {
    @Autowired private WebhookSecurityConfigRepository repository;

    @GetMapping("/webhook-security") @Operation(operationId = "getWebhookSecurityConfig")
    public ResponseEntity<WebhookSecurityConfig> get() {
        return ResponseEntity.ok(repository.get());
    }

    @PutMapping("/webhook-security") @Operation(operationId = "updateWebhookSecurityConfig")
    public ResponseEntity<WebhookSecurityConfig> update(@Valid @RequestBody WebhookSecurityConfig config) {
        repository.save(config);
        return ResponseEntity.ok(repository.get());
    }
}
