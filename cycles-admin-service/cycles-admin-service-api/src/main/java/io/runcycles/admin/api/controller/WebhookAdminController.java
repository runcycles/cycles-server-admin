package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.webhook.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController @RequestMapping("/v1/admin/webhooks") @Tag(name = "Webhooks")
public class WebhookAdminController {
    @Autowired private WebhookService webhookService;
    @Autowired private AuditRepository auditRepository;

    @PostMapping @Operation(operationId = "createWebhookSubscription")
    public ResponseEntity<WebhookCreateResponse> create(
            @RequestParam(required = false) String tenant_id,
            @Valid @RequestBody WebhookCreateRequest request, HttpServletRequest httpRequest) {
        String tenantId = tenant_id != null ? tenant_id : "__system__";
        WebhookCreateResponse response = webhookService.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId).operation("createWebhookSubscription").status(201).build());
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping @Operation(operationId = "listWebhookSubscriptions")
    public ResponseEntity<WebhookListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String event_type,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(webhookService.listAll(tenant_id, status, event_type, cursor, limit));
    }

    @GetMapping("/{subscription_id}") @Operation(operationId = "getWebhookSubscription")
    public ResponseEntity<WebhookSubscription> get(@PathVariable("subscription_id") String subscriptionId) {
        return ResponseEntity.ok(webhookService.get(subscriptionId));
    }

    @PatchMapping("/{subscription_id}") @Operation(operationId = "updateWebhookSubscription")
    public ResponseEntity<WebhookSubscription> update(
            @PathVariable("subscription_id") String subscriptionId,
            @Valid @RequestBody WebhookUpdateRequest request, HttpServletRequest httpRequest) {
        WebhookSubscription updated = webhookService.update(subscriptionId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .operation("updateWebhookSubscription").status(200).build());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{subscription_id}") @Operation(operationId = "deleteWebhookSubscription")
    public ResponseEntity<Void> delete(@PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        webhookService.delete(subscriptionId);
        auditRepository.log(buildAuditEntry(httpRequest)
            .operation("deleteWebhookSubscription").status(204).build());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subscription_id}/test") @Operation(operationId = "testWebhookSubscription")
    public ResponseEntity<WebhookTestResponse> test(@PathVariable("subscription_id") String subscriptionId) {
        return ResponseEntity.ok(webhookService.test(subscriptionId));
    }

    @GetMapping("/{subscription_id}/deliveries") @Operation(operationId = "listWebhookDeliveries")
    public ResponseEntity<WebhookDeliveryListResponse> listDeliveries(
            @PathVariable("subscription_id") String subscriptionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(webhookService.listDeliveries(subscriptionId, status, from, to, cursor, limit));
    }

    @PostMapping("/{subscription_id}/replay") @Operation(operationId = "replayEvents")
    public ResponseEntity<ReplayResponse> replay(
            @PathVariable("subscription_id") String subscriptionId,
            @Valid @RequestBody ReplayRequest request) {
        return ResponseEntity.accepted().body(webhookService.replay(subscriptionId, request));
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
