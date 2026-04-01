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
            .operation("createBudget")
            .status(201)
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
        // Enforce tenant scoping: always use authenticated tenant, ignore user-supplied tenant_id
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope_prefix);
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var ledgers = repository.list(tenantId, scope_prefix, unit, status, cursor, effectiveLimit);
        BudgetListResponse response = BudgetListResponse.builder()
            .ledgers(ledgers)
            .hasMore(ledgers.size() >= effectiveLimit)
            .nextCursor(ledgers.size() >= effectiveLimit ? ledgers.get(ledgers.size() - 1).getLedgerId() : null)
            .build();
        return ResponseEntity.ok(response);
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
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("updateBudget")
            .status(200)
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
    public ResponseEntity<BudgetFundingResponse> fund(@RequestParam String scope, @RequestParam UnitEnum unit,
            @Valid @RequestBody BudgetFundingRequest request, HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope);
        if (request.getAmount().getUnit() != unit) {
            throw GovernanceException.unitMismatch(unit.name(), request.getAmount().getUnit().name());
        }
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        BudgetFundingResponse response = repository.fund(tenantId, scope, unit, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId((String) httpRequest.getAttribute("authenticated_tenant_id"))
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("fundBudget")
            .status(200)
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
            eventService.emit(fundEventType, tenantId, scope, "cycles-admin",
                Actor.builder().type(ActorType.API_KEY)
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

    private void validateCreateUnits(BudgetCreateRequest request) {
        if (request.getAllocated() != null && request.getAllocated().getUnit() != request.getUnit()) {
            throw GovernanceException.unitMismatch(request.getUnit().name(), request.getAllocated().getUnit().name());
        }
        if (request.getOverdraftLimit() != null && request.getOverdraftLimit().getUnit() != request.getUnit()) {
            throw GovernanceException.unitMismatch(request.getUnit().name(), request.getOverdraftLimit().getUnit().name());
        }
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
