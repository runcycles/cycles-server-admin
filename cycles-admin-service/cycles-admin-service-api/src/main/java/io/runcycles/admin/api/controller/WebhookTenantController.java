package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.webhook.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController @RequestMapping("/v1/webhooks") @Tag(name = "Webhooks")
public class WebhookTenantController {
    @Autowired private WebhookService webhookService;

    @PostMapping @Operation(operationId = "createTenantWebhook")
    public ResponseEntity<WebhookCreateResponse> create(
            @Valid @RequestBody WebhookCreateRequest request, HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        validateTenantEventTypes(request.getEventTypes());
        WebhookCreateResponse response = webhookService.create(tenantId, request);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping @Operation(operationId = "listTenantWebhooks")
    public ResponseEntity<WebhookListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        return ResponseEntity.ok(webhookService.listByTenant(tenantId, status, null, cursor, limit));
    }

    @GetMapping("/{subscription_id}") @Operation(operationId = "getTenantWebhook")
    public ResponseEntity<WebhookSubscription> get(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        WebhookSubscription sub = webhookService.get(subscriptionId);
        enforceTenantOwnership(sub, tenantId);
        return ResponseEntity.ok(sub);
    }

    @PatchMapping("/{subscription_id}") @Operation(operationId = "updateTenantWebhook")
    public ResponseEntity<WebhookSubscription> update(
            @PathVariable("subscription_id") String subscriptionId,
            @Valid @RequestBody WebhookUpdateRequest request, HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        WebhookSubscription existing = webhookService.get(subscriptionId);
        enforceTenantOwnership(existing, tenantId);
        if (request.getEventTypes() != null) {
            validateTenantEventTypes(request.getEventTypes());
        }
        return ResponseEntity.ok(webhookService.update(subscriptionId, request));
    }

    @DeleteMapping("/{subscription_id}") @Operation(operationId = "deleteTenantWebhook")
    public ResponseEntity<Void> delete(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        WebhookSubscription existing = webhookService.get(subscriptionId);
        enforceTenantOwnership(existing, tenantId);
        webhookService.delete(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subscription_id}/test") @Operation(operationId = "testTenantWebhook")
    public ResponseEntity<WebhookTestResponse> test(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        WebhookSubscription existing = webhookService.get(subscriptionId);
        enforceTenantOwnership(existing, tenantId);
        return ResponseEntity.ok(webhookService.test(subscriptionId));
    }

    @GetMapping("/{subscription_id}/deliveries") @Operation(operationId = "listTenantWebhookDeliveries")
    public ResponseEntity<WebhookDeliveryListResponse> listDeliveries(
            @PathVariable("subscription_id") String subscriptionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        WebhookSubscription existing = webhookService.get(subscriptionId);
        enforceTenantOwnership(existing, tenantId);
        return ResponseEntity.ok(webhookService.listDeliveries(subscriptionId, status, from, to, cursor, limit));
    }

    private String getAuthenticatedTenantId(HttpServletRequest request) {
        return (String) request.getAttribute("authenticated_tenant_id");
    }

    private void enforceTenantOwnership(WebhookSubscription sub, String tenantId) {
        if (!tenantId.equals(sub.getTenantId())) {
            // Return 404 (not 403) to avoid leaking existence
            throw GovernanceException.webhookNotFound(sub.getSubscriptionId());
        }
    }

    /**
     * Tenant can only subscribe to budget.*, reservation.*, tenant.* event types.
     */
    private void validateTenantEventTypes(List<EventType> eventTypes) {
        if (eventTypes == null) return;
        for (EventType type : eventTypes) {
            if (!type.isTenantAccessible()) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    "Event type " + type.getValue() + " is admin-only; tenants can subscribe to budget.*, reservation.*, tenant.* only", 400);
            }
        }
    }
}
