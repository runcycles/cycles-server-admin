package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.api.service.BulkActionAuditMetadataBuilder;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.idempotency.IdempotencyStore;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.webhook.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController @RequestMapping("/v1/admin/webhooks") @Tag(name = "Webhooks")
public class WebhookAdminController {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookAdminController.class);
    // Per spec v0.1.25.20. consecutive_failures is the default — operators
    // monitoring webhook health want flaky subscriptions surfaced first.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "url", "tenant_id", "status", "consecutive_failures");
    private static final String DEFAULT_SORT_FIELD = "consecutive_failures";
    // Bulk-action (spec v0.1.25.21): same 500-row cap + 15-minute idempotency
    // replay window as the tenants bulk-action. Separate endpoint tag keeps
    // the idempotency namespace partitioned from tenants-bulk.
    private static final int BULK_ACTION_LIMIT = 500;
    private static final String BULK_IDEMPOTENCY_ENDPOINT = "webhooks-bulk";
    @Autowired private WebhookService webhookService;
    @Autowired private WebhookRepository webhookRepository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private IdempotencyStore idempotencyStore;

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
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_dir) {
        limit = Math.max(1, Math.min(limit, 100));
        SortSpec sortSpec = parseSortSpec(sort_by, sort_dir);
        String searchNorm = parseSearch(search);
        return ResponseEntity.ok(webhookService.listAll(tenant_id, status, event_type, cursor, limit, sortSpec, searchNorm));
    }

    /**
     * Parse sort_by / sort_dir query params into a validated SortSpec.
     * See TenantController.parseSortSpec for the shared rationale.
     */
    private SortSpec parseSortSpec(String sortBy, String sortDir) {
        SortDirection direction;
        try {
            direction = SortDirection.fromWire(sortDir);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
        try {
            return SortSpec.resolve(sortBy, direction, ALLOWED_SORT_FIELDS, DEFAULT_SORT_FIELD);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
    }

    private String parseSearch(String raw) {
        try {
            return SearchSpec.resolve(raw);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
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
            .requestId(attr(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE))
            .traceId(attr(request, TraceContextFilter.TRACE_ID_ATTRIBUTE))
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v != null ? v.toString() : null;
    }

    /**
     * Bulk webhook lifecycle action (spec v0.1.25.21). PAUSE / RESUME /
     * DELETE across every subscription matching the filter, same safety
     * gates as {@link TenantController#bulkAction}: empty filter → 400,
     * >{@value #BULK_ACTION_LIMIT} matches → 400 LIMIT_EXCEEDED with
     * {@code total_matched}, expected_count mismatch → 409 COUNT_MISMATCH,
     * 15-min idempotency replay. AdminKeyAuth only.
     */
    @PostMapping("/bulk-action") @Operation(operationId = "bulkActionWebhooks")
    public ResponseEntity<WebhookBulkActionResponse> bulkAction(
            @Valid @RequestBody WebhookBulkActionRequest request, HttpServletRequest httpRequest) {
        long startNanos = System.nanoTime();
        if (request.getFilter() == null || request.getFilter().isEmpty()) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "filter must contain at least one property", 400);
        }
        String searchNorm = parseSearch(request.getFilter().getSearch());

        var cached = idempotencyStore.lookup(
            BULK_IDEMPOTENCY_ENDPOINT, request.getIdempotencyKey(),
            WebhookBulkActionResponse.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        List<WebhookSubscription> matched = webhookRepository.matchForBulk(
            request.getFilter().getTenantId(),
            request.getFilter().getStatus(),
            request.getFilter().getEventType(),
            searchNorm,
            BULK_ACTION_LIMIT);
        if (matched.size() > BULK_ACTION_LIMIT) {
            throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                "filter matches more than " + BULK_ACTION_LIMIT
                    + " subscriptions; narrow the filter and retry",
                400,
                Map.of("total_matched", matched.size()));
        }
        if (request.getExpectedCount() != null && request.getExpectedCount() != matched.size()) {
            throw new GovernanceException(ErrorCode.COUNT_MISMATCH,
                "expected_count " + request.getExpectedCount()
                    + " differs from server-counted matches " + matched.size(),
                409,
                Map.of("total_matched", matched.size()));
        }

        List<BulkActionRowOutcome> succeeded = new ArrayList<>();
        List<BulkActionRowOutcome> failed = new ArrayList<>();
        List<BulkActionRowOutcome> skipped = new ArrayList<>();
        for (WebhookSubscription sub : matched) {
            applyWebhookAction(sub, request.getAction(), succeeded, failed, skipped);
        }

        WebhookBulkActionResponse response = WebhookBulkActionResponse.builder()
            .action(request.getAction())
            .totalMatched(matched.size())
            .succeeded(succeeded)
            .failed(failed)
            .skipped(skipped)
            .idempotencyKey(request.getIdempotencyKey())
            .build();

        idempotencyStore.store(BULK_IDEMPOTENCY_ENDPOINT, request.getIdempotencyKey(), response);

        Map<String, Object> auditMeta = BulkActionAuditMetadataBuilder.build(
            request.getAction().name(), matched.size(),
            succeeded, failed, skipped,
            request.getIdempotencyKey(), request.getFilter(), startNanos);
        auditRepository.log(buildAuditEntry(httpRequest)
            .resourceType("webhook").resourceId("bulk-action")
            .operation("bulkActionWebhooks").status(200)
            .metadata(auditMeta)
            .build());
        return ResponseEntity.ok(response);
    }

    /**
     * Apply one row of the webhook bulk-action and bucket the outcome.
     * Reads live status at apply time to stay correct under concurrent
     * mutation; a row deleted between the match and the apply lands in
     * {@code skipped[]} with ALREADY_DELETED rather than a lost-update
     * error.
     */
    private void applyWebhookAction(WebhookSubscription matched, WebhookBulkAction action,
                                     List<BulkActionRowOutcome> succeeded,
                                     List<BulkActionRowOutcome> failed,
                                     List<BulkActionRowOutcome> skipped) {
        String id = matched.getSubscriptionId();
        try {
            if (action == WebhookBulkAction.DELETE) {
                try {
                    webhookService.delete(id);
                    succeeded.add(BulkActionRowOutcome.builder().id(id).build());
                } catch (GovernanceException e) {
                    if (e.getErrorCode() == ErrorCode.WEBHOOK_NOT_FOUND
                            || e.getErrorCode() == ErrorCode.NOT_FOUND) {
                        skipped.add(BulkActionRowOutcome.builder()
                            .id(id).reason("ALREADY_DELETED").build());
                    } else {
                        throw e;
                    }
                }
                return;
            }
            WebhookSubscription live;
            try {
                live = webhookRepository.findById(id);
            } catch (GovernanceException e) {
                // Concurrent delete between match and apply.
                skipped.add(BulkActionRowOutcome.builder()
                    .id(id).reason("ALREADY_DELETED").build());
                return;
            }
            WebhookStatus current = live.getStatus() != null ? live.getStatus() : WebhookStatus.ACTIVE;
            if (action == WebhookBulkAction.PAUSE) {
                if (current == WebhookStatus.PAUSED || current == WebhookStatus.DISABLED) {
                    skipped.add(BulkActionRowOutcome.builder()
                        .id(id).reason("ALREADY_IN_TARGET_STATE").build());
                    return;
                }
                WebhookUpdateRequest update = WebhookUpdateRequest.builder()
                    .status(WebhookStatus.PAUSED).build();
                webhookService.update(id, update);
                succeeded.add(BulkActionRowOutcome.builder().id(id).build());
                return;
            }
            // action == RESUME
            if (current == WebhookStatus.ACTIVE) {
                skipped.add(BulkActionRowOutcome.builder()
                    .id(id).reason("ALREADY_IN_TARGET_STATE").build());
                return;
            }
            if (current == WebhookStatus.DISABLED) {
                failed.add(BulkActionRowOutcome.builder()
                    .id(id).errorCode("INVALID_TRANSITION")
                    .message("Cannot resume DISABLED subscription").build());
                return;
            }
            WebhookUpdateRequest update = WebhookUpdateRequest.builder()
                .status(WebhookStatus.ACTIVE).build();
            webhookService.update(id, update);
            succeeded.add(BulkActionRowOutcome.builder().id(id).build());
        } catch (GovernanceException e) {
            failed.add(BulkActionRowOutcome.builder()
                .id(id)
                .errorCode(classifyFailureCode(e))
                .message(e.getMessage()).build());
        } catch (Exception e) {
            LOG.warn("Bulk-action row failed for subscription {}: {}", id, e.getMessage());
            failed.add(BulkActionRowOutcome.builder()
                .id(id).errorCode("INTERNAL_ERROR").message("Internal error").build());
        }
    }

    private static String classifyFailureCode(GovernanceException e) {
        switch (e.getErrorCode()) {
            case WEBHOOK_NOT_FOUND:
            case NOT_FOUND:
                return "NOT_FOUND";
            case FORBIDDEN:
            case INSUFFICIENT_PERMISSIONS:
                return "PERMISSION_DENIED";
            case INVALID_REQUEST:
                return "INVALID_TRANSITION";
            default:
                return "INTERNAL_ERROR";
        }
    }
}
