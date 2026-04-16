package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetListFilters;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.UnitEnum;
import io.runcycles.admin.api.config.ScopeFilterUtil;
import io.runcycles.admin.api.config.ScopeValidator;
import io.runcycles.admin.api.service.EventService;
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
import java.util.List;
import java.util.Map;
@RestController @RequestMapping("/v1/admin/budgets") @Tag(name = "Budgets")
public class BudgetController {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetController.class);
    @Autowired private BudgetRepository repository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
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
            LOG.warn("Failed to emit event: {}", e.getMessage());
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
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope_prefix);
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
        BudgetListFilters filters = new BudgetListFilters(
            scope_prefix, unit, status, over_limit, has_debt, utilization_min, utilization_max);
        List<BudgetLedger> ledgers;
        boolean crossTenant;
        if (authTenantId != null) {
            crossTenant = false;
            ledgers = repository.list(authTenantId, filters, cursor, effectiveLimit);
        } else if (tenant_id != null && !tenant_id.isBlank()) {
            crossTenant = false;
            ledgers = repository.list(tenant_id, filters, cursor, effectiveLimit);
        } else {
            crossTenant = true;
            ledgers = repository.listAllTenants(filters, cursor, effectiveLimit);
        }
        String nextCursor = null;
        if (ledgers.size() >= effectiveLimit) {
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
            .hasMore(ledgers.size() >= effectiveLimit)
            .nextCursor(nextCursor)
            .build();
        return ResponseEntity.ok(response);
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
        try {
            // Derive tenant from the budget's stored tenant_id for event emission
            String eventTenantId = tenantId != null ? tenantId : ledger.getTenantId();
            ActorType actorType = tenantId != null ? ActorType.API_KEY : ActorType.ADMIN;
            String keyId = (String) httpRequest.getAttribute("authenticated_key_id");
            eventService.emit(EventType.BUDGET_UPDATED, eventTenantId, scope, "cycles-admin",
                Actor.builder().type(actorType).keyId(keyId).build(),
                objectMapper.convertValue(EventDataBudgetLifecycle.builder()
                    .ledgerId(ledger.getLedgerId()).scope(scope)
                    .unit(unit).operation(BudgetOperation.UPDATE).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
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
        BudgetFundingResponse response = repository.fund(tenantId, scope, unit, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("budget").resourceId(scope + ":" + unit.name())
            .operation("fundBudget")
            .status(200)
            .metadata(buildFundMetadata(scope, unit, request, response))
            .build());
        try {
            EventType fundEventType;
            switch (request.getOperation()) {
                case CREDIT: fundEventType = EventType.BUDGET_FUNDED; break;
                case DEBIT: fundEventType = EventType.BUDGET_DEBITED; break;
                case RESET: fundEventType = EventType.BUDGET_RESET; break;
                case RESET_SPENT: fundEventType = EventType.BUDGET_RESET_SPENT; break;
                case REPAY_DEBT: fundEventType = EventType.BUDGET_DEBT_REPAID; break;
                default: fundEventType = EventType.BUDGET_FUNDED; break;
            }
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
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    // ========== POST /v1/admin/budgets/freeze ==========

    @PostMapping("/freeze") @Operation(operationId = "freezeBudget")
    public ResponseEntity<BudgetLedger> freeze(@RequestParam String scope, @RequestParam UnitEnum unit,
            @RequestBody(required = false) @Valid BudgetStatusTransitionRequest request,
            HttpServletRequest httpRequest) {
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
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.ok(ledger);
    }

    // ========== POST /v1/admin/budgets/unfreeze ==========

    @PostMapping("/unfreeze") @Operation(operationId = "unfreezeBudget")
    public ResponseEntity<BudgetLedger> unfreeze(@RequestParam String scope, @RequestParam UnitEnum unit,
            @RequestBody(required = false) @Valid BudgetStatusTransitionRequest request,
            HttpServletRequest httpRequest) {
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
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.ok(ledger);
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
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
