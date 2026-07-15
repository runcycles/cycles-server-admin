package io.runcycles.admin.api.controller;
import static io.runcycles.admin.api.logging.LogSanitizer.safe;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.idempotency.IdempotencyStore;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetListFilters;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.shared.UnitEnum;
import io.runcycles.admin.api.config.ScopeFilterUtil;
import io.runcycles.admin.api.config.ScopeValidator;
import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.api.service.BulkActionAuditMetadataBuilder;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
import io.runcycles.admin.api.support.PageSlice;
import io.runcycles.admin.model.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
@RestController @RequestMapping("/v1/admin/budgets") @Tag(name = "Budgets")
public class BudgetController {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetController.class);
    // Per spec v0.1.25.20. "utilization" is a computed field (spent /
    // allocated, with allocated==0 treated as utilization==0 — see
    // BudgetListFilters). The repo applies the same formula for sort
    // so sort/filter are consistent. "debt" sorts on the raw ledger
    // debt amount, a single long per budget.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "tenant_id", "scope", "unit", "status", "commit_overage_policy",
        "utilization", "debt");
    private static final String DEFAULT_SORT_FIELD = "utilization";
    // Bulk-action (spec v0.1.25.26): 500-row hard cap per invocation and
    // 15-minute idempotency replay window. The idempotency endpoint tag
    // "budgets-bulk" partitions this store from tenant / webhook bulk-ops
    // so operator-supplied keys cannot collide across endpoints.
    private static final int BULK_ACTION_LIMIT = 500;
    private static final String BULK_IDEMPOTENCY_ENDPOINT = "budgets-bulk";

    @Autowired private BudgetRepository repository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IdempotencyStore idempotencyStore;
    @Autowired private TerminalOwnerMutationGuard mutationGuard;
    @PostMapping @Operation(operationId = "createBudget")
    public ResponseEntity<BudgetLedger> create(@Valid @RequestBody BudgetCreateRequest request, HttpServletRequest httpRequest) {
        validateCreateUnits(request);
        // v0.1.25.15: enforce canonical scope grammar (tenant:<id>[/<kind>:<id>]*
        // with kinds drawn from tenant/workspace/app/workflow/agent/toolset in
        // order). Prior to this, garbage like "workspace:eng" (no tenant prefix)
        // or "tenant:acme/florb:blerp" (nonsense kind) was accepted, creating
        // ledgers that silently fail to match during enforcement.
        ScopeValidator.validateBudgetScope(request.getScope());
        ScopeFilterUtil.enforceScopeFilter(httpRequest, request.getScope());
        // v0.1.25.14 dual-auth (spec v0.1.25.13). For ApiKeyAuth the tenant
        // is implicit from the authenticated key; for AdminKeyAuth it must
        // come from the request body. Enforce the conditional contract:
        // tenant_id present iff admin-auth.
        String authTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        boolean isAdminAuth = authTenantId == null;
        if (isAdminAuth) {
            if (request.getTenantId() == null || request.getTenantId().isBlank()) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "tenant_id is required in the request body when using admin key authentication", 400);
            }
        } else if (request.getTenantId() != null) {
            throw new GovernanceException(
                io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                "tenant_id MUST NOT be set when using API key authentication (tenant is inferred from the key)", 400);
        }
        String tenantId = isAdminAuth ? request.getTenantId() : authTenantId;
        // Cross-check: scope's tenant prefix must match the routing tenant.
        // Prevents "body says tenant=acme, scope says tenant:corp" from
        // silently creating a ledger under the wrong tenant.
        ScopeValidator.validateScopeMatchesTenant(request.getScope(), tenantId);
        mutationGuard.assertTenantOpen(tenantId);
        BudgetLedger ledger = repository.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("budget").resourceId(ledger.getLedgerId())
            .operation("createBudget")
            .status(201)
            .metadata(Map.of("scope", request.getScope(), "unit", request.getUnit().name(),
                "allocated", request.getAllocated().getAmount(),
                // Surfaces the auth path used so audit reviewers can
                // distinguish admin-on-behalf-of from tenant self-service.
                // Source the wire format from the enum's @JsonValue rather
                // than a hardcoded string so an enum rename doesn't drift the
                // audit-log format silently. The wire string is "admin_on_behalf_of"
                // / "api_key" — same as ActorType.getValue().
                "actor_type", (isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY).getValue()))
            .build());
        try {
            ActorType actorType = isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY;
            eventService.emit(EventType.BUDGET_CREATED, tenantId, request.getScope(), "cycles-admin",
                Actor.builder().type(actorType)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                objectMapper.convertValue(EventDataBudgetLifecycle.builder()
                    .ledgerId(ledger.getLedgerId()).scope(request.getScope())
                    .unit(request.getUnit()).operation(BudgetOperation.CREATE).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            logEventEmissionFailure(EventType.BUDGET_CREATED, tenantId, ledger.getLedgerId(),
                request.getScope(), request.getUnit(), null, httpRequest, e);
        }
        return ResponseEntity.status(201).body(ledger);
    }
    @GetMapping @Operation(operationId = "listBudgets")
    public ResponseEntity<BudgetListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String scope_prefix,
            @RequestParam(required = false) UnitEnum unit,
            @RequestParam(required = false) BudgetStatus status,
            @RequestParam(required = false) Boolean over_limit,
            @RequestParam(required = false) Boolean has_debt,
            @RequestParam(required = false) Double utilization_min,
            @RequestParam(required = false) Double utilization_max,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_dir,
            HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope_prefix);
        SortSpec sortSpec = parseSortSpec(sort_by, sort_dir);
        // Cross-parameter constraint declared normatively in governance spec
        // v0.1.25.18 FILTER SEMANTICS. OpenAPI can't express
        // "utilization_min <= utilization_max" in-schema, so we enforce it
        // here. Also validate each bound is within [0, 1] — the spec only
        // pins the min/max via `format: double` + `minimum/maximum`, but
        // Spring's @RequestParam binding won't reject out-of-range values
        // on its own, so we re-check to keep behaviour symmetrical under
        // ApiKeyAuth and AdminKeyAuth.
        if (utilization_min != null && (utilization_min < 0.0 || utilization_min > 1.0)) {
            throw new GovernanceException(
                io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                "utilization_min must be in [0, 1]", 400);
        }
        if (utilization_max != null && (utilization_max < 0.0 || utilization_max > 1.0)) {
            throw new GovernanceException(
                io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                "utilization_max must be in [0, 1]", 400);
        }
        if (utilization_min != null && utilization_max != null && utilization_min > utilization_max) {
            throw new GovernanceException(
                io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                "utilization_min must be <= utilization_max", 400);
        }
        String authTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        // Tenant resolution per spec v0.1.25.18:
        //   - ApiKeyAuth: always scoped to the authenticated tenant. A
        //     `tenant_id` query param is ignored silently (no 400) to
        //     match the existing pattern used by other endpoints.
        //   - AdminKeyAuth + tenant_id provided: per-tenant listing.
        //   - AdminKeyAuth + tenant_id absent: cross-tenant listing.
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        String searchNorm = parseSearch(search);
        BudgetListFilters filters = new BudgetListFilters(
            scope_prefix, unit, status, over_limit, has_debt, utilization_min, utilization_max, searchNorm);
        List<BudgetLedger> ledgers;
        boolean crossTenant;
        if (authTenantId != null) {
            crossTenant = false;
            ledgers = repository.list(authTenantId, filters, cursor, effectiveLimit + 1, sortSpec);
        } else if (tenant_id != null && !tenant_id.isBlank()) {
            crossTenant = false;
            ledgers = repository.list(tenant_id, filters, cursor, effectiveLimit + 1, sortSpec);
        } else {
            crossTenant = true;
            ledgers = repository.listAllTenants(filters, cursor, effectiveLimit + 1, sortSpec);
        }
        var page = PageSlice.from(ledgers, effectiveLimit);
        ledgers = page.items();
        String nextCursor = null;
        if (page.hasMore()) {
            BudgetLedger last = ledgers.get(ledgers.size() - 1);
            // Cross-tenant cursor format is "{tenantId}|{ledgerId}" so the
            // next page can resume inside the correct tenant; per-tenant
            // cursor stays as the bare ledger_id for wire-compat with
            // existing clients.
            nextCursor = crossTenant
                ? last.getTenantId() + "|" + last.getLedgerId()
                : last.getLedgerId();
        }
        BudgetListResponse response = BudgetListResponse.builder()
            .ledgers(ledgers)
            .hasMore(page.hasMore())
            .nextCursor(nextCursor)
            .build();
        return ResponseEntity.ok(response);
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

    @GetMapping("/lookup") @Operation(operationId = "lookupBudget")
    public ResponseEntity<BudgetLedger> lookup(
            @RequestParam String scope, @RequestParam UnitEnum unit,
            HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope);
        BudgetLedger ledger = repository.getByExactScope(scope, unit);
        return ResponseEntity.ok(ledger);
    }
    @PatchMapping @Operation(operationId = "updateBudget")
    public ResponseEntity<BudgetLedger> update(@RequestParam String scope, @RequestParam UnitEnum unit,
            @Valid @RequestBody BudgetUpdateRequest request, HttpServletRequest httpRequest) {
        // PATCH /v1/admin/budgets uses AdminKeyAuth per spec v0.1.25 — no tenant scoping
        if (request.getOverdraftLimit() != null && request.getOverdraftLimit().getUnit() != unit) {
            throw GovernanceException.unitMismatch(unit.name(), request.getOverdraftLimit().getUnit().name());
        }
        // Admin auth: tenantId is null, Lua script skips ownership check
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        mutationGuard.assertOpenForScope(scope);
        BudgetLedger ledger = repository.update(tenantId, scope, unit, request);
        String auditTenantId = tenantId != null ? tenantId : ledger.getTenantId();
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(auditTenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("budget").resourceId(ledger.getLedgerId())
            .operation("updateBudget")
            .status(200)
            .metadata(buildUpdateBudgetMeta(scope, unit, request))
            .build());
        // Derive tenant from the budget's stored tenant_id for event emission.
        String eventTenantId = tenantId != null ? tenantId : ledger.getTenantId();
        try {
            ActorType actorType = tenantId != null ? ActorType.API_KEY : ActorType.ADMIN;
            String keyId = (String) httpRequest.getAttribute("authenticated_key_id");
            eventService.emit(EventType.BUDGET_UPDATED, eventTenantId, scope, "cycles-admin",
                Actor.builder().type(actorType).keyId(keyId).build(),
                objectMapper.convertValue(EventDataBudgetLifecycle.builder()
                    .ledgerId(ledger.getLedgerId()).scope(scope)
                    .unit(unit).operation(BudgetOperation.UPDATE).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            logEventEmissionFailure(EventType.BUDGET_UPDATED, eventTenantId, ledger.getLedgerId(),
                scope, unit, null, httpRequest, e);
        }
        return ResponseEntity.ok(ledger);
    }
    @PostMapping("/fund") @Operation(operationId = "fundBudget")
    public ResponseEntity<BudgetFundingResponse> fund(
            @RequestParam(required = false) String tenant_id,
            @RequestParam String scope, @RequestParam UnitEnum unit,
            @Valid @RequestBody BudgetFundingRequest request, HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope);
        if (request.getAmount().getUnit() != unit) {
            throw GovernanceException.unitMismatch(unit.name(), request.getAmount().getUnit().name());
        }
        // Validate the optional `spent` field: only meaningful for RESET_SPENT,
        // must share the same unit as the budget (ledger is single-unit), and
        // must be non-negative. Supplying `spent` on other operations is a
        // client bug worth surfacing early rather than silently ignoring.
        if (request.getSpent() != null) {
            if (request.getOperation() != FundingOperation.RESET_SPENT) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "`spent` field is only honoured for RESET_SPENT operations", 400);
            }
            if (request.getSpent().getUnit() != unit) {
                throw GovernanceException.unitMismatch(unit.name(), request.getSpent().getUnit().name());
            }
            if (request.getSpent().getAmount() < 0) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "`spent` must be >= 0", 400);
            }
        }
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        if (tenantId == null) {
            // Admin key auth — tenant_id query param is required for scoping
            if (tenant_id == null || tenant_id.isBlank()) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "tenant_id query parameter is required when using admin key authentication",
                    400);
            }
            tenantId = tenant_id;
        }
        mutationGuard.assertTenantOpen(tenantId);
        BudgetFundingResponse response = repository.fund(tenantId, scope, unit, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("budget").resourceId(scope + ":" + unit.name())
            .operation("fundBudget")
            .status(200)
            .metadata(buildFundMetadata(scope, unit, request, response))
            .build());
        EventType fundEventType = fundEventType(request.getOperation());
        try {
            ActorType actorType = httpRequest.getAttribute("authenticated_tenant_id") != null ? ActorType.API_KEY : ActorType.ADMIN;

            // Pre/post state snapshots. For RESET_SPENT we populate the new spent
            // and reserved fields so event consumers can see the transition cleanly.
            // For other operations we populate what we have; unchanged fields appear
            // equal on both sides.
            EventDataBudgetLifecycle.BudgetState previousState = EventDataBudgetLifecycle.BudgetState.builder()
                .allocated(response.getPreviousAllocated() != null ? response.getPreviousAllocated().getAmount() : null)
                .remaining(response.getPreviousRemaining() != null ? response.getPreviousRemaining().getAmount() : null)
                .debt(response.getPreviousDebt() != null ? response.getPreviousDebt().getAmount() : null)
                .spent(response.getPreviousSpent() != null ? response.getPreviousSpent().getAmount() : null)
                .build();
            EventDataBudgetLifecycle.BudgetState newState = EventDataBudgetLifecycle.BudgetState.builder()
                .allocated(response.getNewAllocated() != null ? response.getNewAllocated().getAmount() : null)
                .remaining(response.getNewRemaining() != null ? response.getNewRemaining().getAmount() : null)
                .debt(response.getNewDebt() != null ? response.getNewDebt().getAmount() : null)
                .spent(response.getNewSpent() != null ? response.getNewSpent().getAmount() : null)
                .build();

            EventDataBudgetLifecycle.EventDataBudgetLifecycleBuilder payloadBuilder =
                EventDataBudgetLifecycle.builder()
                    .scope(scope).unit(unit)
                    .operation(BudgetOperation.valueOf(request.getOperation().name()))
                    .previousState(previousState)
                    .newState(newState)
                    .reason(request.getReason());
            if (request.getOperation() == FundingOperation.RESET_SPENT) {
                payloadBuilder.spentOverrideProvided(request.getSpent() != null);
            }

            eventService.emit(fundEventType, tenantId, scope, "cycles-admin",
                Actor.builder().type(actorType)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                objectMapper.convertValue(payloadBuilder.build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            logEventEmissionFailure(fundEventType, tenantId, scope + ":" + unit.name(),
                scope, unit, null, httpRequest, e);
        }
        return ResponseEntity.ok(response);
    }

    // ========== POST /v1/admin/budgets/freeze ==========

    @PostMapping("/freeze") @Operation(operationId = "freezeBudget")
    public ResponseEntity<BudgetLedger> freeze(@RequestParam String scope, @RequestParam UnitEnum unit,
            @RequestBody(required = false) @Valid BudgetStatusTransitionRequest request,
            HttpServletRequest httpRequest) {
        mutationGuard.assertOpenForScope(scope);
        BudgetLedger ledger = repository.freeze(scope, unit);
        String auditTenantId = ledger.getTenantId();
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(auditTenantId)
            .resourceType("budget").resourceId(ledger.getLedgerId())
            .operation("freezeBudget")
            .status(200)
            .metadata(Map.of("scope", scope, "unit", unit.name()))
            .build());
        try {
            eventService.emit(EventType.BUDGET_FROZEN, auditTenantId, scope, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataBudgetLifecycle.builder()
                    .ledgerId(ledger.getLedgerId()).scope(scope).unit(unit)
                    .operation(BudgetOperation.STATUS_CHANGE)
                    .reason(request != null ? request.getReason() : null).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            logEventEmissionFailure(EventType.BUDGET_FROZEN, auditTenantId, ledger.getLedgerId(),
                scope, unit, null, httpRequest, e);
        }
        return ResponseEntity.ok(ledger);
    }

    // ========== POST /v1/admin/budgets/unfreeze ==========

    @PostMapping("/unfreeze") @Operation(operationId = "unfreezeBudget")
    public ResponseEntity<BudgetLedger> unfreeze(@RequestParam String scope, @RequestParam UnitEnum unit,
            @RequestBody(required = false) @Valid BudgetStatusTransitionRequest request,
            HttpServletRequest httpRequest) {
        mutationGuard.assertOpenForScope(scope);
        BudgetLedger ledger = repository.unfreeze(scope, unit);
        String auditTenantId = ledger.getTenantId();
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(auditTenantId)
            .resourceType("budget").resourceId(ledger.getLedgerId())
            .operation("unfreezeBudget")
            .status(200)
            .metadata(Map.of("scope", scope, "unit", unit.name()))
            .build());
        try {
            eventService.emit(EventType.BUDGET_UNFROZEN, auditTenantId, scope, "cycles-admin",
                Actor.builder().type(ActorType.ADMIN).build(),
                objectMapper.convertValue(EventDataBudgetLifecycle.builder()
                    .ledgerId(ledger.getLedgerId()).scope(scope).unit(unit)
                    .operation(BudgetOperation.STATUS_CHANGE)
                    .reason(request != null ? request.getReason() : null).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            logEventEmissionFailure(EventType.BUDGET_UNFROZEN, auditTenantId, ledger.getLedgerId(),
                scope, unit, null, httpRequest, e);
        }
        return ResponseEntity.ok(ledger);
    }

    // ========== POST /v1/admin/budgets/bulk-action ==========

    /**
     * Apply one of the five {@link FundingOperation} actions to every
     * budget ledger matching the filter, in a single synchronous request
     * (spec v0.1.25.26). Resolves cycles-server-admin issue #99 — rolling
     * over a billing period no longer requires the operator to iterate
     * listBudgets + per-row fundBudget; they issue one filtered bulk
     * request.
     *
     * <p>Body shape, envelope semantics, safety gates, and per-row outcome
     * reporting mirror {@code bulkActionTenants} and {@code bulkActionWebhooks}:
     * <ul>
     *   <li>500-row hard cap → LIMIT_EXCEEDED (400).</li>
     *   <li>{@code expected_count} mismatch → COUNT_MISMATCH (409), no writes.</li>
     *   <li>15-minute {@code idempotency_key} replay window.</li>
     *   <li>Overall HTTP 200 even when per-row failures land in {@code failed[]}.</li>
     * </ul>
     * AdminKeyAuth only (tenants cannot bulk-mutate their own budgets
     * — the per-budget {@code fundBudget} endpoint remains available).
     */
    @PostMapping("/bulk-action") @Operation(operationId = "bulkActionBudgets")
    public ResponseEntity<BudgetBulkActionResponse> bulkAction(
            @Valid @RequestBody BudgetBulkActionRequest request, HttpServletRequest httpRequest) {
        long startNanos = System.nanoTime();
        // Action/payload validation runs BEFORE any Redis read so that
        // malformed combos fail fast with 400 and never enter the match /
        // apply path (spec requirement).
        validateBulkActionPayload(request);
        String searchNorm = parseSearch(request.getFilter().getSearch());
        BudgetBulkFilter filter = request.getFilter();
        filter.setSearch(searchNorm);

        IdempotencyStore.Claim<BudgetBulkActionResponse> idempotencyClaim =
            idempotencyStore.begin(BULK_IDEMPOTENCY_ENDPOINT,
                request.getIdempotencyKey(), request, BudgetBulkActionResponse.class);
        if (idempotencyClaim.isReplay()) {
            return ResponseEntity.ok(idempotencyClaim.replayResponse());
        }
        List<BudgetLedger> matched;
        try {
            // Claim before mutable match reads: a retry must replay the original
            // envelope even when the first invocation changed this filter's set.
            matched = repository.matchForBulk(filter.getTenantId(),
                BudgetListFilters.fromBulkFilter(filter), BULK_ACTION_LIMIT);
            if (matched.size() > BULK_ACTION_LIMIT) {
                int totalMatched = Math.max(matched.size(), repository.countForBulk(
                    filter.getTenantId(), BudgetListFilters.fromBulkFilter(filter)));
                throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                    "filter matches more than " + BULK_ACTION_LIMIT
                        + " budgets; narrow the filter and retry",
                    400, Map.of("total_matched", totalMatched));
            }
            if (request.getExpectedCount() != null
                    && request.getExpectedCount() != matched.size()) {
                throw new GovernanceException(ErrorCode.COUNT_MISMATCH,
                    "expected_count " + request.getExpectedCount()
                        + " differs from server-counted matches " + matched.size(),
                    409, Map.of("total_matched", matched.size()));
            }
        } catch (RuntimeException e) {
            idempotencyStore.abandon(idempotencyClaim);
            throw e;
        }

        List<BulkActionRowOutcome> succeeded = new ArrayList<>();
        List<BulkActionRowOutcome> failed = new ArrayList<>();
        List<BulkActionRowOutcome> skipped = new ArrayList<>();
        // Per-invocation correlation_id for every per-row Event emitted below.
        // Spec v0.1.25.32: operators query listEvents?correlation_id=... to see
        // every row a bulk op touched, tying per-row Events back to the single
        // aggregate AuditLogEntry via the shared request_id.
        String requestId = attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String correlationId = "budget_bulk_action:"
            + request.getAction().name().toLowerCase() + ":"
            + (requestId != null ? requestId : "no-req");
        for (BudgetLedger ledger : matched) {
            applyBudgetAction(ledger, request, succeeded, failed, skipped,
                httpRequest, correlationId, requestId);
        }

        BudgetBulkActionResponse response = BudgetBulkActionResponse.builder()
            .action(request.getAction())
            .totalMatched(matched.size())
            .succeeded(succeeded)
            .failed(failed)
            .skipped(skipped)
            .idempotencyKey(request.getIdempotencyKey())
            .build();

        idempotencyStore.complete(idempotencyClaim, response);

        Map<String, Object> auditMeta = BulkActionAuditMetadataBuilder.build(
            request.getAction().name(), matched.size(),
            succeeded, failed, skipped,
            request.getIdempotencyKey(), filter, startNanos);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(filter.getTenantId())
            .resourceType("budget").resourceId("bulk-action")
            .operation("bulkActionBudgets").status(200)
            .metadata(auditMeta)
            .build());
        return ResponseEntity.ok(response);
    }

    /**
     * Envelope-level validation — catches every invalid action/payload
     * combination before the match phase so no Redis work is wasted on a
     * malformed request. Per spec v0.1.25.26: every one of the five
     * actions (CREDIT, DEBIT, RESET, REPAY_DEBT, RESET_SPENT) requires
     * {@code amount}; {@code spent} is honoured only for RESET_SPENT and
     * must be non-negative when present; {@code utilization_min} cannot
     * exceed {@code utilization_max}.
     */
    private void validateBulkActionPayload(BudgetBulkActionRequest request) {
        if (request.getAmount() == null) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "amount is required for bulk action " + request.getAction().name(), 400);
        }
        if (request.getAction() != FundingOperation.RESET_SPENT && request.getSpent() != null) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "spent is only honoured when action is RESET_SPENT", 400);
        }
        if (request.getSpent() != null && request.getSpent().getAmount() < 0L) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "spent must be >= 0", 400);
        }
        BudgetBulkFilter f = request.getFilter();
        if (f.getUtilizationMin() != null && f.getUtilizationMax() != null
                && f.getUtilizationMin() > f.getUtilizationMax()) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "utilization_min must be <= utilization_max", 400);
        }
    }

    /**
     * Apply one row of the bulk-action and bucket the outcome. Per-row
     * failures never abort the loop — each matched ledger is attempted.
     *
     * <p>Pre-checks run before invoking {@link BudgetRepository#fund} so
     * the spec-mandated row-level error codes (INVALID_TRANSITION for
     * unit mismatch, ALREADY_IN_TARGET_STATE for REPAY_DEBT on zero debt)
     * are surfaced without a wasted Lua round-trip.
     *
     * <p>Row-level idempotency: a derived key {@code bulkKey:scope:unit}
     * is passed to fund() so that a network retry which re-enters this
     * loop will short-circuit at the atomic Lua idempotency check in
     * {@code BudgetRepository}, preventing double-application of
     * CREDIT/DEBIT/etc. The outer bulk-op idempotency ({@code IdempotencyStore})
     * handles the envelope-level replay within 15 minutes; the per-row
     * derived key handles the rarer "client narrowed the filter to
     * the previously-failed set and retried after the envelope TTL
     * expired" case.
     */
    private void applyBudgetAction(BudgetLedger ledger, BudgetBulkActionRequest bulk,
                                    List<BulkActionRowOutcome> succeeded,
                                    List<BulkActionRowOutcome> failed,
                                    List<BulkActionRowOutcome> skipped,
                                    HttpServletRequest httpRequest,
                                    String correlationId,
                                    String requestId) {
        // Spec v0.1.25.26 BudgetBulkActionResponse.succeeded: "Per-row `id`
        // is the ledger_id." The ledger_id is the stable unique identifier;
        // scope is the routing/matching key passed to fund().
        String id = ledger.getLedgerId();
        // Rule 2 (v0.1.25.29): any mutation on a budget whose owning tenant
        // is CLOSED returns TENANT_CLOSED. Bucketed per-row so one closed
        // tenant doesn't poison the whole batch; classifyBudgetFailureCode
        // maps the ErrorCode to the spec's known row-level code.
        try {
            mutationGuard.assertTenantOpen(ledger.getTenantId());
        } catch (GovernanceException e) {
            failed.add(BulkActionRowOutcome.builder()
                .id(id)
                .errorCode(classifyBudgetFailureCode(e))
                .message(e.getMessage()).build());
            return;
        }
        if (bulk.getAmount().getUnit() != ledger.getUnit()) {
            failed.add(BulkActionRowOutcome.builder()
                .id(id).errorCode("INVALID_TRANSITION")
                .message("unit mismatch: expected " + ledger.getUnit()
                    + ", got " + bulk.getAmount().getUnit()).build());
            return;
        }
        if (bulk.getAction() == FundingOperation.REPAY_DEBT) {
            long debtAmount = ledger.getDebt() != null ? ledger.getDebt().getAmount() : 0L;
            if (debtAmount == 0L) {
                skipped.add(BulkActionRowOutcome.builder()
                    .id(id).reason("ALREADY_IN_TARGET_STATE").build());
                return;
            }
        }
        BudgetFundingRequest fundingRequest = new BudgetFundingRequest();
        fundingRequest.setOperation(bulk.getAction());
        fundingRequest.setAmount(bulk.getAmount());
        fundingRequest.setSpent(bulk.getSpent());
        fundingRequest.setReason(bulk.getReason());
        fundingRequest.setIdempotencyKey(bulk.getIdempotencyKey()
            + ":" + ledger.getScope() + ":" + ledger.getUnit().name());
        try {
            BudgetFundingResponse fundResponse = repository.fund(
                ledger.getTenantId(), ledger.getScope(), ledger.getUnit(), fundingRequest);
            succeeded.add(BulkActionRowOutcome.builder().id(id).build());
            emitBulkFundEvent(ledger, bulk, fundResponse, httpRequest,
                correlationId, requestId);
        } catch (GovernanceException e) {
            failed.add(BulkActionRowOutcome.builder()
                .id(id)
                .errorCode(classifyBudgetFailureCode(e))
                .message(e.getMessage()).build());
        } catch (Exception e) {
            LOG.warn("Budget bulk-action row failed: action={} budget_id={} tenant_id={} scope={} unit={} correlation_id={} request_id={} trace_id={} exception_class={} error={}",
                bulk.getAction(), safe(id), safe(ledger.getTenantId()), safe(ledger.getScope()), ledger.getUnit(),
                safe(correlationId), requestId, attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE),
                e.getClass().getSimpleName(), safe(e.getMessage()), e);
            failed.add(BulkActionRowOutcome.builder()
                .id(id).errorCode("INTERNAL_ERROR").message("Internal error").build());
        }
    }

    /**
     * Per-row Event emission for bulk-action budgets (spec v0.1.25.32). Mirrors
     * the single-op fund event at {@link #fund}: maps FundingOperation to the
     * matching {@link EventType}, builds the pre/post {@link EventDataBudgetLifecycle}
     * snapshot from the repository response, and stamps the envelope-scoped
     * {@code budget_bulk_action:<action>:<request_id>} correlation_id so
     * operators can query {@code listEvents?correlation_id=...} to see every
     * row this invocation touched. Skipped and failed rows never reach this
     * method. Event emission failure is caught and logged — it must not
     * abort the bulk op (matching single-fund discipline).
     */
    private void emitBulkFundEvent(BudgetLedger ledger, BudgetBulkActionRequest bulk,
                                    BudgetFundingResponse response,
                                    HttpServletRequest httpRequest,
                                    String correlationId, String requestId) {
        if (response == null) return;
        EventType eventType = fundEventType(bulk.getAction());
        try {
            EventDataBudgetLifecycle.BudgetState previousState = EventDataBudgetLifecycle.BudgetState.builder()
                .allocated(response.getPreviousAllocated() != null ? response.getPreviousAllocated().getAmount() : null)
                .remaining(response.getPreviousRemaining() != null ? response.getPreviousRemaining().getAmount() : null)
                .debt(response.getPreviousDebt() != null ? response.getPreviousDebt().getAmount() : null)
                .spent(response.getPreviousSpent() != null ? response.getPreviousSpent().getAmount() : null)
                .build();
            EventDataBudgetLifecycle.BudgetState newState = EventDataBudgetLifecycle.BudgetState.builder()
                .allocated(response.getNewAllocated() != null ? response.getNewAllocated().getAmount() : null)
                .remaining(response.getNewRemaining() != null ? response.getNewRemaining().getAmount() : null)
                .debt(response.getNewDebt() != null ? response.getNewDebt().getAmount() : null)
                .spent(response.getNewSpent() != null ? response.getNewSpent().getAmount() : null)
                .build();
            EventDataBudgetLifecycle.EventDataBudgetLifecycleBuilder payloadBuilder =
                EventDataBudgetLifecycle.builder()
                    .scope(ledger.getScope()).unit(ledger.getUnit())
                    .operation(BudgetOperation.valueOf(bulk.getAction().name()))
                    .previousState(previousState)
                    .newState(newState)
                    .reason(bulk.getReason());
            if (bulk.getAction() == FundingOperation.RESET_SPENT) {
                payloadBuilder.spentOverrideProvided(bulk.getSpent() != null);
            }
            // Actor-type parity with single-op fund path (line ~337): resolves to API_KEY
            // when a tenant key authenticated the call, ADMIN otherwise. Today bulk is
            // AdminKeyAuth-gated so this always yields ADMIN, but mirroring the single-op
            // conditional prevents silent mis-attribution if bulk auth is ever broadened.
            ActorType actorType = httpRequest.getAttribute("authenticated_tenant_id") != null ? ActorType.API_KEY : ActorType.ADMIN;
            eventService.emit(eventType, ledger.getTenantId(), ledger.getScope(),
                "cycles-admin",
                Actor.builder().type(actorType)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                objectMapper.convertValue(payloadBuilder.build(), Map.class),
                correlationId, requestId);
        } catch (Exception e) {
            logEventEmissionFailure(eventType, ledger.getTenantId(), ledger.getLedgerId(),
                ledger.getScope(), ledger.getUnit(), correlationId, httpRequest, e);
        }
    }

    /**
     * Maps request-level {@link ErrorCode} values raised by
     * {@link BudgetRepository#fund} into the row-level known-codes set
     * documented by spec v0.1.25.26 for {@link BulkActionRowOutcome#getErrorCode}.
     * BUDGET_EXCEEDED covers the DEBIT-goes-negative case; INVALID_TRANSITION
     * is the umbrella for ledger-state rejections (frozen / closed / invalid
     * request shape); everything else falls into INTERNAL_ERROR so clients
     * never see an unknown code.
     */
    private static String classifyBudgetFailureCode(GovernanceException e) {
        switch (e.getErrorCode()) {
            case BUDGET_EXCEEDED:
                return "BUDGET_EXCEEDED";
            case BUDGET_FROZEN:
            case BUDGET_CLOSED:
            case INVALID_REQUEST:
                return "INVALID_TRANSITION";
            case BUDGET_NOT_FOUND:
            case NOT_FOUND:
                return "NOT_FOUND";
            case FORBIDDEN:
            case INSUFFICIENT_PERMISSIONS:
                return "PERMISSION_DENIED";
            case TENANT_CLOSED:
                return "TENANT_CLOSED";
            default:
                return "INTERNAL_ERROR";
        }
    }

    private static EventType fundEventType(FundingOperation operation) {
        switch (operation) {
            case CREDIT: return EventType.BUDGET_FUNDED;
            case DEBIT: return EventType.BUDGET_DEBITED;
            case RESET: return EventType.BUDGET_RESET;
            case RESET_SPENT: return EventType.BUDGET_RESET_SPENT;
            case REPAY_DEBT: return EventType.BUDGET_DEBT_REPAID;
            default: return EventType.BUDGET_FUNDED;
        }
    }

    private void logEventEmissionFailure(EventType eventType, String tenantId, String budgetId,
                                          String scope, UnitEnum unit, String correlationId,
                                          HttpServletRequest request, Exception e) {
        LOG.warn("Failed to emit admin budget event: event_type={} tenant_id={} budget_id={} scope={} unit={} correlation_id={} request_id={} trace_id={} exception_class={} error={}",
            eventType, safe(tenantId), safe(budgetId), safe(scope), unit, safe(correlationId),
            attr(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE),
            attr(request, TraceContextFilter.TRACE_ID_ATTRIBUTE),
            e.getClass().getSimpleName(), safe(e.getMessage()), e);
    }

    private void validateCreateUnits(BudgetCreateRequest request) {
        if (request.getAllocated() != null && request.getAllocated().getUnit() != request.getUnit()) {
            throw GovernanceException.unitMismatch(request.getUnit().name(), request.getAllocated().getUnit().name());
        }
        if (request.getOverdraftLimit() != null && request.getOverdraftLimit().getUnit() != request.getUnit()) {
            throw GovernanceException.unitMismatch(request.getUnit().name(), request.getOverdraftLimit().getUnit().name());
        }
    }

    private Map<String, Object> buildUpdateBudgetMeta(String scope, UnitEnum unit, BudgetUpdateRequest request) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("scope", scope);
        meta.put("unit", unit.name());
        if (request.getOverdraftLimit() != null) meta.put("overdraft_limit", request.getOverdraftLimit().getAmount());
        if (request.getCommitOveragePolicy() != null) meta.put("commit_overage_policy", request.getCommitOveragePolicy().name());
        return meta;
    }

    private Map<String, Object> buildFundMetadata(String scope, UnitEnum unit, BudgetFundingRequest request,
                                                   BudgetFundingResponse response) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("scope", scope);
        meta.put("unit", unit.name());
        meta.put("funding_operation", request.getOperation().name());
        meta.put("amount", request.getAmount().getAmount());
        if (request.getReason() != null) meta.put("reason", request.getReason());
        if (request.getIdempotencyKey() != null) meta.put("idempotency_key", request.getIdempotencyKey());
        // Spent-change audit trail. Only meaningful for RESET_SPENT today; populated
        // for all operations since the values are cheap and give reviewers a
        // before/after snapshot without joining to event logs. For preserve-spent
        // operations prev and new are equal — a visual no-op.
        if (response != null && response.getPreviousSpent() != null && response.getNewSpent() != null) {
            meta.put("previous_spent", response.getPreviousSpent().getAmount());
            meta.put("new_spent", response.getNewSpent().getAmount());
        }
        // Flag whether the caller explicitly supplied `spent` vs relied on the
        // default — important for RESET_SPENT compliance review because explicit
        // spent values fall under the "operator adjusted consumption" bucket that
        // usually requires higher scrutiny than routine rollovers.
        if (request.getOperation() == FundingOperation.RESET_SPENT) {
            meta.put("spent_override_provided", request.getSpent() != null);
        }
        return meta;
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
}
