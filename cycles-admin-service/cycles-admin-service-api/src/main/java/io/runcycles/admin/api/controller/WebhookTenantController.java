package io.runcycles.admin.api.controller;

import static io.runcycles.admin.api.logging.LogSanitizer.safe;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.event.ActorType;
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
    @Autowired private AuditRepository auditRepository;
    @Autowired private TerminalOwnerMutationGuard mutationGuard;

    // createTenantWebhook is intentionally NOT dual-auth (spec v0.1.25.14):
    // URL / signing secret / event choice are tenant policy; admin creating
    // on a tenant's behalf obscures provenance. If admin needs to manage
    // a subscription, /v1/admin/webhooks/* already serves admin-provisioned
    // ones — dual-auth on the tenant-scoped read/update/delete paths covers
    // the incident-response use case without letting admin mint subs.
    @PostMapping @Operation(operationId = "createTenantWebhook")
    public ResponseEntity<WebhookCreateResponse> create(
            @Valid @RequestBody WebhookCreateRequest request, HttpServletRequest httpRequest) {
        String tenantId = getAuthenticatedTenantId(httpRequest);
        validateTenantEventTypes(request.getEventTypes());
        mutationGuard.assertTenantOpen(tenantId);
        WebhookCreateResponse response = webhookService.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("webhook").resourceId(response.getSubscription().getSubscriptionId())
            .operation("createTenantWebhook").status(201)
            .metadata(java.util.Map.of("url", request.getUrl()))
            .build());
        return ResponseEntity.status(201).body(response);
    }

    // v0.1.25.16 dual-auth: admin caller passes `tenant` query param
    // (REQUIRED under admin, MUST NOT be set under ApiKeyAuth). Same
    // single-param dual-semantic shape as listBudgets / listPolicies /
    // listReservations.
    @GetMapping @Operation(operationId = "listTenantWebhooks")
    public ResponseEntity<WebhookListResponse> list(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        String authTenantId = getAuthenticatedTenantId(httpRequest);
        boolean isAdminAuth = authTenantId == null;
        String tenantId;
        if (isAdminAuth) {
            if (tenant == null || tenant.isBlank()) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    "tenant query parameter is required when using admin key authentication", 400);
            }
            tenantId = tenant;
        } else {
            if (tenant != null) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    "tenant query parameter MUST NOT be set when using API key authentication", 400);
            }
            tenantId = authTenantId;
        }
        limit = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(webhookService.listByTenant(tenantId, status, null, cursor, limit));
    }

    @GetMapping("/{subscription_id}") @Operation(operationId = "getTenantWebhook")
    public ResponseEntity<WebhookSubscription> get(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        WebhookSubscription sub = webhookService.get(subscriptionId);
        enforceTenantOwnership(httpRequest, sub);
        return ResponseEntity.ok(sub);
    }

    @PatchMapping("/{subscription_id}") @Operation(operationId = "updateTenantWebhook")
    public ResponseEntity<WebhookSubscription> update(
            @PathVariable("subscription_id") String subscriptionId,
            @Valid @RequestBody WebhookUpdateRequest request, HttpServletRequest httpRequest) {
        WebhookSubscription existing = webhookService.get(subscriptionId);
        boolean isAdminAuth = isAdminAuth(httpRequest);
        enforceTenantOwnership(httpRequest, existing);
        if (request.getEventTypes() != null) {
            validateTenantEventTypes(request.getEventTypes());
        }
        // Spec v0.1.25.29 Rule 2: any mutation on a webhook owned by a CLOSED
        // tenant returns 409 TENANT_CLOSED. This is the layer that makes
        // DISABLED effectively-terminal for closed-owner webhooks without
        // widening the WebhookStatus enum — a DISABLED webhook cascaded at
        // tenant close can still be read, but cannot be re-enabled via PATCH.
        mutationGuard.assertTenantOpen(existing.getTenantId());
        WebhookSubscription updated = webhookService.update(subscriptionId, request);
        java.util.Map<String, Object> updateMeta = new java.util.LinkedHashMap<>();
        updateMeta.put("url", updated.getUrl());
        if (request.getName() != null) updateMeta.put("name", request.getName());
        if (request.getUrl() != null) updateMeta.put("new_url", request.getUrl());
        if (request.getEventTypes() != null) updateMeta.put("event_types", request.getEventTypes().stream().map(Object::toString).toList());
        if (request.getStatus() != null) updateMeta.put("new_status", request.getStatus().name());
        updateMeta.put("actor_type", (isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY).getValue());
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(existing.getTenantId())
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("updateTenantWebhook").status(200)
            .metadata(updateMeta)
            .build());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{subscription_id}") @Operation(operationId = "deleteTenantWebhook")
    public ResponseEntity<Void> delete(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        WebhookSubscription existing = webhookService.get(subscriptionId);
        boolean isAdminAuth = isAdminAuth(httpRequest);
        enforceTenantOwnership(httpRequest, existing);
        mutationGuard.assertTenantOpen(existing.getTenantId());
        webhookService.delete(subscriptionId);
        java.util.Map<String, Object> delMeta = new java.util.LinkedHashMap<>();
        delMeta.put("url", existing.getUrl());
        delMeta.put("actor_type", (isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY).getValue());
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(existing.getTenantId())
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("deleteTenantWebhook").status(204)
            .metadata(delMeta)
            .build());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subscription_id}/test") @Operation(operationId = "testTenantWebhook")
    public ResponseEntity<WebhookTestResponse> test(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        WebhookSubscription existing = webhookService.get(subscriptionId);
        boolean isAdminAuth = isAdminAuth(httpRequest);
        enforceTenantOwnership(httpRequest, existing);
        mutationGuard.assertTenantOpen(existing.getTenantId());
        WebhookTestResponse response = webhookService.test(subscriptionId);
        java.util.Map<String, Object> testMeta = new java.util.LinkedHashMap<>();
        testMeta.put("success", response.isSuccess());
        if (response.getResponseStatus() != null) testMeta.put("response_status", response.getResponseStatus());
        if (response.getErrorMessage() != null) testMeta.put("error_message", response.getErrorMessage());
        if (response.getResponseTimeMs() != null) testMeta.put("response_time_ms", response.getResponseTimeMs());
        testMeta.put("actor_type", (isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY).getValue());
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(existing.getTenantId())
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("testTenantWebhook").status(200)
            .metadata(testMeta)
            .build());
        return ResponseEntity.ok(response);
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
        WebhookSubscription existing = webhookService.get(subscriptionId);
        enforceTenantOwnership(httpRequest, existing);
        limit = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(webhookService.listDeliveries(subscriptionId, status, from, to, cursor, limit));
    }

    private String getAuthenticatedTenantId(HttpServletRequest request) {
        return (String) request.getAttribute("authenticated_tenant_id");
    }

    private boolean isAdminAuth(HttpServletRequest request) {
        return getAuthenticatedTenantId(request) == null;
    }

    // v0.1.25.16: ApiKeyAuth enforces that the subscription belongs to
    // the caller's tenant (404 on mismatch, not 403 — avoids leaking
    // existence). AdminKeyAuth has no effective tenant and may read any
    // subscription; the owning tenant is resolved from the subscription
    // record and used as the audit subject downstream.
    private void enforceTenantOwnership(HttpServletRequest request, WebhookSubscription sub) {
        if (isAdminAuth(request)) return;
        String tenantId = getAuthenticatedTenantId(request);
        if (!tenantId.equals(sub.getTenantId())) {
            throw GovernanceException.webhookNotFound(sub.getSubscriptionId());
        }
    }

    /**
     * Tenant can only subscribe to budget.*, reservation.*, tenant.* event types.
     */
    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(attr(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE))
            .traceId(attr(request, TraceContextFilter.TRACE_ID_ATTRIBUTE))
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return safe(v);
    }

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
