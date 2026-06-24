package io.runcycles.admin.api.controller;

import static io.runcycles.admin.api.logging.LogSanitizer.safe;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.api.service.BulkActionAuditMetadataBuilder;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.idempotency.IdempotencyStore;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.event.Actor;
import io.runcycles.admin.model.event.ActorType;
import io.runcycles.admin.model.event.EventDataWebhookLifecycle;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.webhook.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    @Autowired private TerminalOwnerMutationGuard mutationGuard;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;

    @PostMapping @Operation(operationId = "createWebhookSubscription")
    public ResponseEntity<WebhookCreateResponse> create(
            @RequestParam(required = false) String tenant_id,
            @Valid @RequestBody WebhookCreateRequest request, HttpServletRequest httpRequest) {
        String tenantId = tenant_id != null ? tenant_id : "__system__";
        // Rule 2: refuse creating a subscription under a CLOSED tenant. Guard
        // no-ops on the "__system__" sentinel (no tenant record exists for it).
        if (tenant_id != null) mutationGuard.assertTenantOpen(tenantId);
        WebhookCreateResponse response = webhookService.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .resourceType("webhook").resourceId(response.getSubscription().getSubscriptionId())
            .operation("createWebhookSubscription").status(201)
            .metadata(java.util.Map.of("url", request.getUrl()))
            .build());
        emitWebhookLifecycleEvent(EventType.WEBHOOK_CREATED,
            response.getSubscription().getSubscriptionId(), tenantId,
            null, response.getSubscription().getStatus(), List.of(), null,
            "webhook_create:" + response.getSubscription().getSubscriptionId(),
            httpRequest);
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
        mutationGuard.assertOpenForWebhook(subscriptionId);
        // Capture prior status BEFORE mutating so we can classify the emit
        // type (WEBHOOK_PAUSED / WEBHOOK_RESUMED vs plain WEBHOOK_UPDATED).
        WebhookSubscription prior = webhookService.get(subscriptionId);
        WebhookStatus previousStatus = prior.getStatus();
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
        // Spec v0.1.25.33: status-flip PATCH emits WEBHOOK_PAUSED/RESUMED;
        // property-only PATCH emits WEBHOOK_UPDATED. WEBHOOK_DISABLED is
        // reserved for dispatcher auto-disable (cycles-server-events) and
        // is not produced on this operator path. A no-op PATCH (zero fields
        // mutated AND no status change) MUST NOT emit an Event.
        // Spec v0.1.25.33 §6281: changed_fields lists fields *modified* — a
        // PATCH that resends an existing value is not a mutation. Diff each
        // request-provided field against the prior snapshot to keep the emit
        // (and the no-op guard below) in sync with actual state change.
        // signing_secret is @JsonIgnore on the subscription model, so its prior
        // (possibly encrypted) value isn't safely comparable to the plaintext
        // request value — treat any presence as rotation intent.
        EventType updateEventType = EventType.WEBHOOK_UPDATED;
        List<String> changedFields = new ArrayList<>();
        if (request.getName() != null && !Objects.equals(request.getName(), prior.getName())) changedFields.add("name");
        if (request.getDescription() != null && !Objects.equals(request.getDescription(), prior.getDescription())) changedFields.add("description");
        if (request.getUrl() != null && !Objects.equals(request.getUrl(), prior.getUrl())) changedFields.add("url");
        if (request.getEventTypes() != null && !Objects.equals(request.getEventTypes(), prior.getEventTypes())) changedFields.add("event_types");
        if (request.getEventCategories() != null && !Objects.equals(request.getEventCategories(), prior.getEventCategories())) changedFields.add("event_categories");
        if (request.getScopeFilter() != null && !Objects.equals(request.getScopeFilter(), prior.getScopeFilter())) changedFields.add("scope_filter");
        if (request.getThresholds() != null && !Objects.equals(request.getThresholds(), prior.getThresholds())) changedFields.add("thresholds");
        if (request.getSigningSecret() != null) changedFields.add("signing_secret");
        if (request.getHeaders() != null && !Objects.equals(request.getHeaders(), prior.getHeaders())) changedFields.add("headers");
        if (request.getRetryPolicy() != null && !Objects.equals(request.getRetryPolicy(), prior.getRetryPolicy())) changedFields.add("retry_policy");
        if (request.getDisableAfterFailures() != null && !Objects.equals(request.getDisableAfterFailures(), prior.getDisableAfterFailures())) changedFields.add("disable_after_failures");
        if (request.getMetadata() != null && !Objects.equals(request.getMetadata(), prior.getMetadata())) changedFields.add("metadata");
        boolean statusFlipped = request.getStatus() != null && request.getStatus() != previousStatus;
        if (statusFlipped) {
            if (request.getStatus() == WebhookStatus.PAUSED) {
                updateEventType = EventType.WEBHOOK_PAUSED;
            } else if (request.getStatus() == WebhookStatus.ACTIVE) {
                // Both PAUSED → ACTIVE and DISABLED → ACTIVE (operator
                // re-enable of an auto-disabled subscription) emit
                // webhook.resumed per spec v0.1.25.33 §EVENTS.
                updateEventType = EventType.WEBHOOK_RESUMED;
            }
        }
        // No-op PATCH guard (spec v0.1.25.33: zero fields mutated AND no
        // status change MUST NOT emit). changedFields enumerates every
        // non-status field the request touched; statusFlipped covers the
        // status axis.
        if (!changedFields.isEmpty() || statusFlipped) {
            String requestId = resolveRequestId(httpRequest);
            emitWebhookLifecycleEvent(updateEventType, subscriptionId, updated.getTenantId(),
                previousStatus, updated.getStatus(), changedFields, null,
                "webhook_update:" + subscriptionId + ":" + requestId,
                httpRequest);
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{subscription_id}") @Operation(operationId = "deleteWebhookSubscription")
    public ResponseEntity<Void> delete(@PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        WebhookSubscription sub = webhookService.get(subscriptionId);
        mutationGuard.assertTenantOpen(sub.getTenantId());
        webhookService.delete(subscriptionId);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(sub.getTenantId())
            .resourceType("webhook").resourceId(subscriptionId)
            .operation("deleteWebhookSubscription").status(204)
            .metadata(java.util.Map.of("url", sub.getUrl()))
            .build());
        emitWebhookLifecycleEvent(EventType.WEBHOOK_DELETED, subscriptionId, sub.getTenantId(),
            sub.getStatus(), null, List.of(), null,
            "webhook_delete:" + subscriptionId, httpRequest);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subscription_id}/test") @Operation(operationId = "testWebhookSubscription")
    public ResponseEntity<WebhookTestResponse> test(
            @PathVariable("subscription_id") String subscriptionId, HttpServletRequest httpRequest) {
        mutationGuard.assertOpenForWebhook(subscriptionId);
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
        mutationGuard.assertOpenForWebhook(subscriptionId);
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
     * Resolve the caller's request_id with a UUID fallback so correlation_id
     * stays unique across concurrent requests even if RequestIdFilter didn't
     * populate the attribute (defensive — should not happen in production).
     */
    private static String resolveRequestId(HttpServletRequest request) {
        String requestId = attr(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId != null ? requestId : "req_" + UUID.randomUUID();
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
        // Spec v0.1.25.33: per-invocation correlation_id stamped on every
        // per-row Event emitted below. Operators retrieve the full fan-out
        // via GET /v1/admin/events?correlation_id=…; the shared request_id
        // also ties every row to the invocation's single AuditLogEntry.
        String requestId = resolveRequestId(httpRequest);
        String correlationId = "webhook_bulk_action:"
            + request.getAction().name().toLowerCase() + ":"
            + requestId;
        for (WebhookSubscription sub : matched) {
            applyWebhookAction(sub, request.getAction(), succeeded, failed, skipped,
                httpRequest, correlationId, requestId);
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
                                     List<BulkActionRowOutcome> skipped,
                                     HttpServletRequest httpRequest,
                                     String correlationId, String requestId) {
        String id = matched.getSubscriptionId();
        try {
            // Rule 2 (v0.1.25.29): reject mutation on subscriptions whose
            // owning tenant is CLOSED. Bucketed into failed[] via the outer
            // catch so one closed-owner row doesn't poison the whole batch.
            mutationGuard.assertTenantOpen(matched.getTenantId());
            if (action == WebhookBulkAction.DELETE) {
                // Fresh live read for previous_status parity with PAUSE/RESUME
                // so the emitted Event carries the subscription's status at
                // time of deletion (spec §6358-6360), not the pre-match
                // snapshot, which can drift if the dispatcher auto-disables
                // between match and apply.
                WebhookSubscription deleteLive;
                try {
                    deleteLive = webhookRepository.findById(id);
                } catch (GovernanceException e) {
                    skipped.add(BulkActionRowOutcome.builder()
                        .id(id).reason("ALREADY_DELETED").build());
                    return;
                }
                try {
                    webhookService.delete(id);
                    succeeded.add(BulkActionRowOutcome.builder().id(id).build());
                    emitBulkWebhookEvent(id, deleteLive.getTenantId(), action,
                        deleteLive.getStatus(), httpRequest, correlationId, requestId);
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
                emitBulkWebhookEvent(id, live.getTenantId(), action, current,
                    httpRequest, correlationId, requestId);
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
            emitBulkWebhookEvent(id, live.getTenantId(), action, current,
                httpRequest, correlationId, requestId);
        } catch (GovernanceException e) {
            failed.add(BulkActionRowOutcome.builder()
                .id(id)
                .errorCode(classifyFailureCode(e))
                .message(e.getMessage()).build());
        } catch (Exception e) {
            LOG.warn("Webhook bulk-action row failed: action={} subscription_id={} tenant_id={} status={} correlation_id={} request_id={} trace_id={} exception_class={} error={}",
                action, safe(id), safe(matched.getTenantId()), matched.getStatus(), safe(correlationId), requestId,
                attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE),
                e.getClass().getSimpleName(), safe(e.getMessage()), e);
            failed.add(BulkActionRowOutcome.builder()
                .id(id).errorCode("INTERNAL_ERROR").message("Internal error").build());
        }
    }

    /**
     * Single-op webhook lifecycle Event emission (spec v0.1.25.33). Wraps
     * EventService.emit in a try/catch so emit-layer failures never break
     * the operator-facing response. Called from create / update / delete.
     */
    private void emitWebhookLifecycleEvent(EventType eventType, String subscriptionId,
                                            String tenantId,
                                            WebhookStatus previousStatus,
                                            WebhookStatus newStatus,
                                            List<String> changedFields,
                                            String disableReason,
                                            String correlationId,
                                            HttpServletRequest httpRequest) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventData = objectMapper.convertValue(
                EventDataWebhookLifecycle.builder()
                    .subscriptionId(subscriptionId)
                    .tenantId(tenantId)
                    .previousStatus(previousStatus)
                    .newStatus(newStatus)
                    .changedFields(changedFields)
                    .disableReason(disableReason)
                    .build(), Map.class);
            // v0.1.25.40 B1 fix: attribute operator-driven single-op events
            // to the authenticated API key, matching the bulk path's actor
            // shape so audit consumers see consistent attribution across
            // single-op and bulk webhook lifecycle emits.
            eventService.emit(eventType, tenantId, null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                eventData,
                correlationId,
                attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        } catch (Exception e) {
            LOG.warn("Failed to emit admin webhook event: event_type={} subscription_id={} tenant_id={} previous_status={} new_status={} changed_field_count={} correlation_id={} request_id={} trace_id={} exception_class={} error={}",
                eventType, safe(subscriptionId), safe(tenantId), previousStatus, newStatus,
                changedFields != null ? changedFields.size() : 0, safe(correlationId),
                attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE),
                e.getClass().getSimpleName(), safe(e.getMessage()), e);
        }
    }

    /**
     * Per-row Event emission for bulkActionWebhooks (spec v0.1.25.33). Maps
     * WebhookBulkAction → EventType (PAUSE → WEBHOOK_PAUSED, RESUME →
     * WEBHOOK_RESUMED, DELETE → WEBHOOK_DELETED). Skipped/failed rows never
     * reach this method — emission is bound to actual state transition.
     * WEBHOOK_DISABLED is intentionally not produced here; it's reserved
     * for dispatcher auto-disable (cycles-server-events).
     */
    private void emitBulkWebhookEvent(String subscriptionId, String tenantId,
                                       WebhookBulkAction action,
                                       WebhookStatus previousStatus,
                                       HttpServletRequest httpRequest,
                                       String correlationId, String requestId) {
        EventType eventType = eventTypeForWebhookAction(action);
        WebhookStatus newStatus = newStatusForWebhookAction(action);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventData = objectMapper.convertValue(
                EventDataWebhookLifecycle.builder()
                    .subscriptionId(subscriptionId)
                    .tenantId(tenantId)
                    .previousStatus(previousStatus)
                    .newStatus(newStatus)
                    .changedFields(List.of())
                    .build(), Map.class);
            eventService.emit(eventType, tenantId, null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                eventData,
                correlationId, requestId);
        } catch (Exception e) {
            LOG.warn("Failed to emit admin webhook bulk event: action={} event_type={} subscription_id={} tenant_id={} previous_status={} new_status={} correlation_id={} request_id={} trace_id={} exception_class={} error={}",
                action, eventType, safe(subscriptionId), safe(tenantId), previousStatus, newStatus,
                safe(correlationId), requestId, attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE),
                e.getClass().getSimpleName(), safe(e.getMessage()), e);
        }
    }

    private static EventType eventTypeForWebhookAction(WebhookBulkAction action) {
        switch (action) {
            case PAUSE: return EventType.WEBHOOK_PAUSED;
            case RESUME: return EventType.WEBHOOK_RESUMED;
            case DELETE: return EventType.WEBHOOK_DELETED;
            default: throw new IllegalStateException("Unreachable action: " + action);
        }
    }

    private static WebhookStatus newStatusForWebhookAction(WebhookBulkAction action) {
        switch (action) {
            case PAUSE: return WebhookStatus.PAUSED;
            case RESUME: return WebhookStatus.ACTIVE;
            case DELETE: return null;
            default: throw new IllegalStateException("Unreachable action: " + action);
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
            case TENANT_CLOSED:
                return "TENANT_CLOSED";
            default:
                return "INTERNAL_ERROR";
        }
    }
}
