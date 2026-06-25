package io.runcycles.admin.api.controller;
import static io.runcycles.admin.api.logging.LogSanitizer.safe;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.idempotency.IdempotencyStore;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantBulkAction;
import io.runcycles.admin.model.tenant.TenantBulkActionRequest;
import io.runcycles.admin.model.tenant.TenantBulkActionResponse;
import io.runcycles.admin.model.tenant.TenantBulkFilter;
import io.runcycles.admin.model.tenant.TenantCreateRequest;
import io.runcycles.admin.model.tenant.TenantListResponse;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.tenant.TenantUpdateRequest;
import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.api.service.BulkActionAuditMetadataBuilder;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.TenantCloseCascadeService;
import io.runcycles.admin.model.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
@RestController @RequestMapping("/v1/admin/tenants") @Tag(name = "Tenants")
public class TenantController {
    private static final Logger LOG = LoggerFactory.getLogger(TenantController.class);
    // Per-endpoint sort_by whitelist + default. Pins the spec v0.1.25.20
    // enum at compile time — any drift between code and spec becomes a
    // unit-test failure, not a silent runtime 500.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "tenant_id", "name", "status", "created_at");
    private static final String DEFAULT_SORT_FIELD = "created_at";
    // Bulk-action (spec v0.1.25.21): 500-row hard cap per invocation and
    // 15-minute idempotency replay window. The idempotency endpoint tag
    // "tenants-bulk" partitions this store from any other future caller
    // so admin-supplied keys cannot collide across endpoints.
    private static final int BULK_ACTION_LIMIT = 500;
    private static final String BULK_IDEMPOTENCY_ENDPOINT = "tenants-bulk";

    @Autowired private TenantRepository repository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IdempotencyStore idempotencyStore;
    @Autowired private TenantCloseCascadeService tenantCloseCascadeService;
    @PostMapping @Operation(operationId = "createTenant")
    public ResponseEntity<Tenant> create(@Valid @RequestBody TenantCreateRequest request, HttpServletRequest httpRequest) {
        var result = repository.create(request);
        int httpStatus = result.created() ? 201 : 200;
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(request.getTenantId())
            .resourceType("tenant").resourceId(request.getTenantId())
            .operation("createTenant")
            .status(httpStatus)
            .metadata(Map.of("name", request.getName()))
            .build());
        try {
            eventService.emit(EventType.TENANT_CREATED, request.getTenantId(), null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataTenantLifecycle.builder()
                    .tenantId(request.getTenantId()).newStatus(io.runcycles.admin.model.tenant.TenantStatus.ACTIVE).changedFields(List.of()).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            logEventEmissionFailure(EventType.TENANT_CREATED, request.getTenantId(),
                null, httpRequest, e);
        }
        return ResponseEntity.status(httpStatus).body(result.tenant());
    }
    @GetMapping @Operation(operationId = "listTenants")
    public ResponseEntity<TenantListResponse> list(
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(required = false) String parent_tenant_id,
            // v0.1.25.8: accepted and ignored. v0.1.26+ servers with observe_mode extension will wire this up.
            @SuppressWarnings("unused") @RequestParam(required = false) String observe_mode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_dir) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        SortSpec sortSpec = parseSortSpec(sort_by, sort_dir);
        String searchNorm = parseSearch(search);
        var tenants = repository.list(status, parent_tenant_id, searchNorm, cursor, effectiveLimit, sortSpec);
        TenantListResponse response = TenantListResponse.builder()
            .tenants(tenants)
            .hasMore(tenants.size() >= effectiveLimit)
            .nextCursor(tenants.size() >= effectiveLimit ? tenants.get(tenants.size() - 1).getTenantId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
    @GetMapping("/{tenant_id}") @Operation(operationId = "getTenant")
    public ResponseEntity<Tenant> get(@PathVariable("tenant_id") String tenantId) {
        return ResponseEntity.ok(repository.get(tenantId));
    }
    @PatchMapping("/{tenant_id}") @Operation(operationId = "updateTenant")
    public ResponseEntity<Tenant> update(@PathVariable("tenant_id") String tenantId, @Valid @RequestBody TenantUpdateRequest request, HttpServletRequest httpRequest) {
        // Spec v0.1.25.29 Rule 1 + v0.1.25.31 Rule 1(c): flip the tenant to
        // CLOSED BEFORE cascading. This activates Rule 2's mutation guard
        // during the cascade window so a concurrent admin PATCH on an owned
        // budget/webhook/api-key 409s rather than racing against us to
        // resurrect a just-terminated child.
        //
        // Convergence (Rule 1(c)): cascade runs on EVERY `status=CLOSED`
        // request, not only the freshwire transition. Cascade steps are
        // idempotent (already-terminal children are skipped in the
        // repository cascade queries), so a re-issued close against an
        // already-CLOSED tenant is a tenant-level no-op but picks up any
        // children left non-terminal by a prior partial failure. This is
        // how this server satisfies spec v0.1.25.31 Rule 1(c)
        // bounded-convergence for Mode B — operator-issued re-close is the
        // documented recovery path in OPERATIONS.md.
        boolean isFreshClose = false;
        boolean isCloseRequest = request.getStatus() == TenantStatus.CLOSED;
        if (isCloseRequest) {
            Tenant prior = repository.get(tenantId);
            isFreshClose = prior.getStatus() != TenantStatus.CLOSED;
        }
        Tenant updated = repository.update(tenantId, request);
        TenantCloseCascadeService.CascadeResult cascadeResult = null;
        String cascadeCorrelationId = null;
        if (isCloseRequest) {
            cascadeCorrelationId = TenantCloseCascadeService.correlationIdFor(tenantId, httpRequest);
            cascadeResult = tenantCloseCascadeService.cascade(tenantId, httpRequest);
        }
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .resourceType("tenant").resourceId(tenantId)
            .operation("updateTenant")
            .status(200)
            .metadata(enrichWithCascade(buildUpdateTenantMeta(request), cascadeResult))
            .build());
        EventType eventType = tenantUpdateEventType(request, isFreshClose);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventData = objectMapper.convertValue(EventDataTenantLifecycle.builder()
                .tenantId(tenantId)
                .newStatus(updated.getStatus())
                .changedFields(List.of()).build(), Map.class);
            if (cascadeResult != null) {
                eventData.put("cascade", cascadeSummaryMap(cascadeResult));
            }
            // TODO actor-parity: hardcoded ADMIN with no keyId. Bulk path (emitBulkTenantEvent)
            // populates keyId from authenticated_key_id; single-op here does not. Aligning
            // would change event wire shape, so deferred — tracked as hygiene follow-up.
            eventService.emit(eventType, tenantId, null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                eventData,
                cascadeCorrelationId,
                httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            logEventEmissionFailure(eventType, tenantId, cascadeCorrelationId, httpRequest, e);
        }
        return ResponseEntity.ok(updated);
    }

    private static Map<String, Object> enrichWithCascade(Map<String, Object> meta,
                                                          TenantCloseCascadeService.CascadeResult result) {
        if (result == null) return meta;
        java.util.LinkedHashMap<String, Object> out =
            meta != null ? new java.util.LinkedHashMap<>(meta) : new java.util.LinkedHashMap<>();
        out.put("cascade", cascadeSummaryMap(result));
        return out;
    }

    private static Map<String, Object> cascadeSummaryMap(TenantCloseCascadeService.CascadeResult r) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("budgets_closed", r.budgetsClosed());
        m.put("webhooks_disabled", r.webhooksDisabled());
        m.put("api_keys_revoked", r.apiKeysRevoked());
        m.put("reservations_released", r.reservationsReleased());
        return m;
    }

    private Map<String, Object> buildUpdateTenantMeta(TenantUpdateRequest request) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        if (request.getStatus() != null) meta.put("new_status", request.getStatus().name());
        if (request.getName() != null) meta.put("name", request.getName());
        if (request.getDefaultCommitOveragePolicy() != null) meta.put("default_commit_overage_policy", request.getDefaultCommitOveragePolicy().name());
        if (request.getDefaultReservationTtlMs() != null) meta.put("default_reservation_ttl_ms", request.getDefaultReservationTtlMs());
        if (request.getMaxReservationTtlMs() != null) meta.put("max_reservation_ttl_ms", request.getMaxReservationTtlMs());
        if (request.getMaxReservationExtensions() != null) meta.put("max_reservation_extensions", request.getMaxReservationExtensions());
        return meta.isEmpty() ? null : meta;
    }

    /**
     * Parse sort_by / sort_dir query params into a validated SortSpec.
     * Per spec v0.1.25.20: unknown sort_by → 400; unknown sort_dir → 400;
     * missing sort_by defaults to the endpoint's canonical default;
     * missing sort_dir defaults to DESC. A single 400 for all parse/
     * validation failures keeps the client contract uniform.
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

    /**
     * Bulk tenant lifecycle action (spec v0.1.25.21). Applies SUSPEND /
     * REACTIVATE / CLOSE across every tenant matching the request filter,
     * with safety gates:
     * <ul>
     *   <li>Empty filter → 400 INVALID_REQUEST (anti-footgun).</li>
     *   <li>Matches > {@value #BULK_ACTION_LIMIT} → 400 LIMIT_EXCEEDED
     *       with {@code total_matched} in the error details; no writes.</li>
     *   <li>{@code expected_count} mismatch → 409 COUNT_MISMATCH; no writes.</li>
     *   <li>Repeat submit with same {@code idempotency_key} within 15 min
     *       returns the original response without re-applying.</li>
     * </ul>
     * Per-row failures do NOT abort the batch — each row is classified into
     * {@code succeeded / failed / skipped} and the overall HTTP status is 200.
     * AdminKeyAuth only (AuthInterceptor already gates {@code /v1/admin/tenants/*}).
     */
    @PostMapping("/bulk-action") @Operation(operationId = "bulkActionTenants")
    public ResponseEntity<TenantBulkActionResponse> bulkAction(
            @Valid @RequestBody TenantBulkActionRequest request, HttpServletRequest httpRequest) {
        long startNanos = System.nanoTime();
        if (request.getFilter() == null || request.getFilter().isEmpty()) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "filter must contain at least one property", 400);
        }
        String searchNorm = parseSearch(request.getFilter().getSearch());

        // Idempotency short-circuit: a cached envelope under the same
        // idempotency_key is returned verbatim. Cache miss falls through to
        // the apply path; success is re-cached at the end with 15-min TTL.
        var cached = idempotencyStore.lookup(
            BULK_IDEMPOTENCY_ENDPOINT, request.getIdempotencyKey(),
            TenantBulkActionResponse.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        // Fetch up to cap+1 so size > cap is detectable without hydrating
        // the remainder. The +1 is the "this filter is too wide" sentinel.
        List<Tenant> matched = repository.matchForBulk(
            request.getFilter().getStatus(),
            request.getFilter().getParentTenantId(),
            searchNorm,
            BULK_ACTION_LIMIT);
        if (matched.size() > BULK_ACTION_LIMIT) {
            throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                "filter matches more than " + BULK_ACTION_LIMIT
                    + " tenants; narrow the filter and retry",
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
        TenantStatus target = targetStatus(request.getAction());
        // Per-invocation correlation_id for every per-row Event emitted below.
        // Spec v0.1.25.32: parent tenant event stamped here; the cascade fan-out
        // (action=CLOSE) keeps its own `tenant_close_cascade:<tenant_id>:<request_id>`
        // correlation_id unchanged so operators can trace the bulk invocation
        // (this correlation_id) OR a specific tenant's cascade (cascade id).
        String requestId = attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String correlationId = "tenant_bulk_action:"
            + request.getAction().name().toLowerCase() + ":"
            + (requestId != null ? requestId : "no-req");
        for (Tenant t : matched) {
            applyTenantAction(t, request.getAction(), target, succeeded, failed, skipped,
                httpRequest, correlationId, requestId);
        }

        TenantBulkActionResponse response = TenantBulkActionResponse.builder()
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
            .resourceType("tenant").resourceId("bulk-action")
            .operation("bulkActionTenants").status(200)
            .metadata(auditMeta)
            .build());
        return ResponseEntity.ok(response);
    }

    private static TenantStatus targetStatus(TenantBulkAction action) {
        switch (action) {
            case SUSPEND: return TenantStatus.SUSPENDED;
            case REACTIVATE: return TenantStatus.ACTIVE;
            case CLOSE: return TenantStatus.CLOSED;
            default: throw new IllegalStateException("Unreachable action: " + action);
        }
    }

    /**
     * Apply one row of the bulk-action and bucket the outcome. Reads the
     * tenant's live status at apply time (not the matched-set snapshot) so
     * a concurrent mutation between count-phase and apply-phase produces
     * the correct per-row classification rather than a lost update.
     */
    private void applyTenantAction(Tenant matched, TenantBulkAction action, TenantStatus target,
                                    List<BulkActionRowOutcome> succeeded,
                                    List<BulkActionRowOutcome> failed,
                                    List<BulkActionRowOutcome> skipped,
                                    HttpServletRequest httpRequest,
                                    String correlationId,
                                    String requestId) {
        String id = matched.getTenantId();
        try {
            Tenant live = repository.get(id);
            TenantStatus current = live.getStatus() != null ? live.getStatus() : TenantStatus.ACTIVE;
            // For non-CLOSE actions, already-in-target is a clean skip.
            // CLOSE is special: re-issuing CLOSE against an already-CLOSED
            // tenant must re-run the cascade (Rule 1(c) bounded-convergence)
            // and classify by whether any stragglers were picked up.
            if (current == target && action != TenantBulkAction.CLOSE) {
                skipped.add(BulkActionRowOutcome.builder()
                    .id(id).reason("ALREADY_IN_TARGET_STATE").build());
                return;
            }
            if (current == TenantStatus.CLOSED && action != TenantBulkAction.CLOSE) {
                failed.add(BulkActionRowOutcome.builder()
                    .id(id).errorCode("INVALID_TRANSITION")
                    .message("Cannot transition from CLOSED").build());
                return;
            }
            // Spec v0.1.25.29 Rule 1 + v0.1.25.31 Rule 1(c): flip the tenant
            // to CLOSED FIRST, then cascade. Flipping first activates Rule 2's
            // mutation guard so races during cascade can't resurrect a
            // just-terminated child. Cascade failure propagates to the catch
            // below, which buckets this row as failed; the tenant is already
            // CLOSED and a retry against the same row picks up any remaining
            // non-terminal children (cascade is idempotent). Skip the
            // redundant repo.update when the tenant is already in target
            // state (retry path).
            //
            // HAZARD (mirrors single-op updateTenant, lines 134-183): if
            // cascade throws after the flip, the tenant is CLOSED on disk
            // but NO parent `tenant.closed` Event is emitted for this row
            // (emit happens after cascade returns, line 428). The row
            // surfaces in `failed[]` so operators see the cascade failure;
            // the documented recovery is re-issuing CLOSE (cascade is
            // idempotent; retry converges). The flip-first ordering is
            // spec-mandated by Rule 2 and cannot be reversed to avoid this.
            if (current != target) {
                TenantUpdateRequest update = new TenantUpdateRequest();
                update.setStatus(target);
                repository.update(id, update);
            }
            TenantCloseCascadeService.CascadeResult cascadeResult = null;
            if (action == TenantBulkAction.CLOSE) {
                cascadeResult = tenantCloseCascadeService.cascade(id, httpRequest);
                if (current == target
                        && cascadeResult.budgetsClosed() == 0
                        && cascadeResult.webhooksDisabled() == 0
                        && cascadeResult.apiKeysRevoked() == 0) {
                    // Already-CLOSED with nothing left to cascade: retry
                    // converged to a full no-op. Report as skipped to keep
                    // the bulk-action contract honest (no state change).
                    skipped.add(BulkActionRowOutcome.builder()
                        .id(id).reason("ALREADY_IN_TARGET_STATE").build());
                    return;
                }
            }
            succeeded.add(BulkActionRowOutcome.builder().id(id).build());
            emitBulkTenantEvent(id, action, cascadeResult, httpRequest,
                correlationId, requestId);
        } catch (GovernanceException e) {
            failed.add(BulkActionRowOutcome.builder()
                .id(id)
                .errorCode(classifyFailureCode(e))
                .message(e.getMessage()).build());
        } catch (Exception e) {
            LOG.warn("Tenant bulk-action row failed: action={} tenant_id={} target_status={} correlation_id={} request_id={} trace_id={} exception_class={} error={}",
                action, safe(id), target, safe(correlationId), requestId,
                attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE),
                e.getClass().getSimpleName(), safe(e.getMessage()), e);
            failed.add(BulkActionRowOutcome.builder()
                .id(id).errorCode("INTERNAL_ERROR").message("Internal error").build());
        }
    }

    /**
     * Per-row Event emission for bulk-action tenants (spec v0.1.25.32). Maps
     * TenantBulkAction to the matching {@link EventType} for the *parent*
     * tenant lifecycle transition (SUSPEND → TENANT_SUSPENDED, REACTIVATE →
     * TENANT_REACTIVATED, CLOSE → TENANT_CLOSED). The cascade fan-out events
     * (action=CLOSE) retain their own {@code tenant_close_cascade:...}
     * correlation_id and are emitted separately by {@link TenantCloseCascadeService}.
     * Skipped and failed rows never reach this method.
     */
    private void emitBulkTenantEvent(String tenantId, TenantBulkAction action,
                                      TenantCloseCascadeService.CascadeResult cascadeResult,
                                      HttpServletRequest httpRequest,
                                      String correlationId, String requestId) {
        EventType eventType = eventTypeForTenantAction(action);
        TenantStatus newStatus = targetStatus(action);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventData = objectMapper.convertValue(
                EventDataTenantLifecycle.builder()
                    .tenantId(tenantId)
                    .newStatus(newStatus)
                    .changedFields(List.of()).build(), Map.class);
            if (cascadeResult != null) {
                eventData.put("cascade", cascadeSummaryMap(cascadeResult));
            }
            // TODO actor-parity: hardcoded ADMIN; bulk endpoint is AdminKeyAuth-gated today
            // so the attribution is correct. If auth is ever broadened (admin-on-behalf-of),
            // mirror the BudgetController#emitBulkFundEvent dual-auth conditional here.
            eventService.emit(eventType, tenantId, null, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                eventData,
                correlationId, requestId);
        } catch (Exception e) {
            logEventEmissionFailure(eventType, tenantId, correlationId, httpRequest, e);
        }
    }

    private static EventType tenantUpdateEventType(TenantUpdateRequest request, boolean isFreshClose) {
        if (request.getStatus() == null) {
            return EventType.TENANT_UPDATED;
        }
        switch (request.getStatus()) {
            case SUSPENDED: return EventType.TENANT_SUSPENDED;
            case ACTIVE: return EventType.TENANT_REACTIVATED;
            case CLOSED:
                // Rule 1(c) convergence: emit TENANT_CLOSED only on the
                // freshwire transition. Retry PATCHes still run cascade but
                // fall through to TENANT_UPDATED at the parent level.
                return isFreshClose ? EventType.TENANT_CLOSED : EventType.TENANT_UPDATED;
            default: return EventType.TENANT_UPDATED;
        }
    }

    private static EventType eventTypeForTenantAction(TenantBulkAction action) {
        switch (action) {
            case SUSPEND: return EventType.TENANT_SUSPENDED;
            case REACTIVATE: return EventType.TENANT_REACTIVATED;
            case CLOSE: return EventType.TENANT_CLOSED;
            default: throw new IllegalStateException("Unreachable action: " + action);
        }
    }

    private void logEventEmissionFailure(EventType eventType, String tenantId, String correlationId,
                                          HttpServletRequest request, Exception e) {
        LOG.warn("Failed to emit admin tenant event: event_type={} tenant_id={} correlation_id={} request_id={} trace_id={} exception_class={} error={}",
            eventType, safe(tenantId), safe(correlationId),
            attr(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE),
            attr(request, TraceContextFilter.TRACE_ID_ATTRIBUTE),
            e.getClass().getSimpleName(), safe(e.getMessage()), e);
    }

    private static String classifyFailureCode(GovernanceException e) {
        switch (e.getErrorCode()) {
            case TENANT_NOT_FOUND:
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
