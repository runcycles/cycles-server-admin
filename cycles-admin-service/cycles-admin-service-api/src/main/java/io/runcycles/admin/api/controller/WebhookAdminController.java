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
            .tenantId(tenantId)
            .resourceType("webhook").resourceId(response.getSubscription().getSubscriptionId())
            .operation("createWebhookSubscription").status(201)
            .metadata(java.util.Map.of("url", request.getUrl()))
            .build());
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping @Operation(operationId = "listWebhookSubscriptions")
    public ResponseEntity<WebhookListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String event_type,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        limit = Math.max(1, Math.min(limit, 100));
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
        java.util.Map<String, Object> updateMeta = new java.util.LinkedHashMap<>();
        updateMeta.put("url", updated.getUrl());
        if (request.getName() != null) updateMeta.put("name", request.getName());
        if (request.getUrl() != null) updateMeta.put("new_url", request.getUrl());
        if (request.getEventTypes() != null) updateMeta.put("event_types", request.getEventTypes().stream().map(Object::toString).toList());
        if (request.getStatus() != null) updateMeta.put("new_status", request.getStatus().name());
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(updated.getTenantId())
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("updateWebhookSubscription").status(200)
            .metadata(updateMeta)
            .build());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{subscription_id}") @Operation(operationId = "deleteWebhookSubscription")
    public ResponseEntity<Void> delete(@PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        WebhookSubscription sub = webhookService.get(subscriptionId);
        webhookService.delete(subscriptionId);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(sub.getTenantId())
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("deleteWebhookSubscription").status(204)
            .metadata(java.util.Map.of("url", sub.getUrl()))
            .build());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subscription_id}/test") @Operation(operationId = "testWebhookSubscription")
    public ResponseEntity<WebhookTestResponse> test(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        WebhookTestResponse response = webhookService.test(subscriptionId);
        java.util.Map<String, Object> testMeta = new java.util.LinkedHashMap<>();
        testMeta.put("success", response.isSuccess());
        if (response.getResponseStatus() != null) testMeta.put("response_status", response.getResponseStatus());
        if (response.getErrorMessage() != null) testMeta.put("error_message", response.getErrorMessage());
        if (response.getResponseTimeMs() != null) testMeta.put("response_time_ms", response.getResponseTimeMs());
        auditRepository.log(buildAuditEntry(httpRequest)
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("testWebhookSubscription").status(200)
            .metadata(testMeta)
            .build());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{subscription_id}/deliveries") @Operation(operationId = "listWebhookDeliveries")
    public ResponseEntity<WebhookDeliveryListResponse> listDeliveries(
            @PathVariable("subscription_id") String subscriptionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        limit = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(webhookService.listDeliveries(subscriptionId, status, from, to, cursor, limit));
    }

    @PostMapping("/{subscription_id}/replay") @Operation(operationId = "replayEvents")
    public ResponseEntity<ReplayResponse> replay(
            @PathVariable("subscription_id") String subscriptionId,
            @Valid @RequestBody ReplayRequest request, HttpServletRequest httpRequest) {
        ReplayResponse response = webhookService.replay(subscriptionId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("replayEvents").status(202)
            .metadata(java.util.Map.of("replay_id", response.getReplayId(),
                "events_queued", response.getEventsQueued()))
            .build());
        return ResponseEntity.accepted().body(response);
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
