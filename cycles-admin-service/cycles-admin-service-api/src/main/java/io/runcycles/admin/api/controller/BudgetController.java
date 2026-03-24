package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.UnitEnum;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/v1/admin/budgets") @Tag(name = "Budgets")
public class BudgetController {
    @Autowired private BudgetRepository repository;
    @Autowired private AuditRepository auditRepository;
    @PostMapping @Operation(operationId = "createBudget")
    public ResponseEntity<BudgetLedger> create(@Valid @RequestBody BudgetCreateRequest request, HttpServletRequest httpRequest) {
        validateCreateUnits(request);
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        BudgetLedger ledger = repository.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("createBudget")
            .status(201)
            .build());
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
        if (request.getOverdraftLimit() != null && request.getOverdraftLimit().getUnit() != unit) {
            throw GovernanceException.unitMismatch(unit.name(), request.getOverdraftLimit().getUnit().name());
        }
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        BudgetLedger ledger = repository.update(tenantId, scope, unit, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("updateBudget")
            .status(200)
            .build());
        return ResponseEntity.ok(ledger);
    }
    @PostMapping("/fund") @Operation(operationId = "fundBudget")
    public ResponseEntity<BudgetFundingResponse> fund(@RequestParam String scope, @RequestParam UnitEnum unit,
            @Valid @RequestBody BudgetFundingRequest request, HttpServletRequest httpRequest) {
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
