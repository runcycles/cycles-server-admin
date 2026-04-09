package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.UnitEnum;
import io.runcycles.admin.api.config.ScopeFilterUtil;
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
        ScopeFilterUtil.enforceScopeFilter(httpRequest, request.getScope());
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        BudgetLedger ledger = repository.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("budget").resourceId(ledger.getLedgerId())
            .operation("createBudget")
            .status(201)
            .metadata(Map.of("scope", request.getScope(), "unit", request.getUnit().name(),
                "allocated", request.getAllocated().getAmount()))
            .build());
        try {
            eventService.emit(EventType.BUDGET_CREATED, tenantId, request.getScope(), "cycles-admin",
                Actor.builder().type(ActorType.API_KEY)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                objectMapper.convertValue(EventDataBudgetLifecycle.builder()
                    .ledgerId(ledger.getLedgerId()).scope(request.getScope())
                    .unit(request.getUnit()).operation("create").build(), Map.class),
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
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope_prefix);
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
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var ledgers = repository.list(tenantId, scope_prefix, unit, status, cursor, effectiveLimit);
        BudgetListResponse response = BudgetListResponse.builder()
            .ledgers(ledgers)
            .hasMore(ledgers.size() >= effectiveLimit)
            .nextCursor(ledgers.size() >= effectiveLimit ? ledgers.get(ledgers.size() - 1).getLedgerId() : null)
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
            .metadata(Map.of("scope", scope, "unit", unit.name()))
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
                    .unit(unit).operation("update").build(), Map.class),
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
            .metadata(buildFundMetadata(scope, unit, request))
            .build());
        try {
            EventType fundEventType;
            switch (request.getOperation()) {
                case CREDIT: fundEventType = EventType.BUDGET_FUNDED; break;
                case DEBIT: fundEventType = EventType.BUDGET_DEBITED; break;
                case RESET: fundEventType = EventType.BUDGET_RESET; break;
                case REPAY_DEBT: fundEventType = EventType.BUDGET_DEBT_REPAID; break;
                default: fundEventType = EventType.BUDGET_FUNDED; break;
            }
            ActorType actorType = httpRequest.getAttribute("authenticated_tenant_id") != null ? ActorType.API_KEY : ActorType.ADMIN;
            eventService.emit(fundEventType, tenantId, scope, "cycles-admin",
                Actor.builder().type(actorType)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                objectMapper.convertValue(EventDataBudgetLifecycle.builder()
                    .scope(scope).unit(unit).operation(request.getOperation().name().toLowerCase())
                    .reason(request.getReason()).build(), Map.class),
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
                    .operation("STATUS_CHANGE")
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
                    .operation("STATUS_CHANGE")
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

    private Map<String, Object> buildFundMetadata(String scope, UnitEnum unit, BudgetFundingRequest request) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("scope", scope);
        meta.put("unit", unit.name());
        meta.put("funding_operation", request.getOperation().name());
        meta.put("amount", request.getAmount().getAmount());
        if (request.getReason() != null) meta.put("reason", request.getReason());
        if (request.getIdempotencyKey() != null) meta.put("idempotency_key", request.getIdempotencyKey());
        return meta;
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
