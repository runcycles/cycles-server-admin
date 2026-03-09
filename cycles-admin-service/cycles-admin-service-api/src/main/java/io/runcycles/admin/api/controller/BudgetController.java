package io.runcycles.admin.api.controller;
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
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        BudgetLedger ledger = repository.create(tenantId, request);
        auditRepository.log(AuditLogEntry.builder()
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("createBudget")
            .status(201)
            .build());
        return ResponseEntity.status(201).body(ledger);
    }
    @GetMapping @Operation(operationId = "listBudgets")
    public ResponseEntity<BudgetListResponse> list(
            @RequestParam(required = true) String tenant_id,
            @RequestParam(required = false) String scope_prefix,
            @RequestParam(required = false) UnitEnum unit,
            @RequestParam(required = false) BudgetStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var ledgers = repository.list(tenant_id, scope_prefix, unit, status, cursor, effectiveLimit);
        BudgetListResponse response = BudgetListResponse.builder()
            .ledgers(ledgers)
            .hasMore(ledgers.size() >= effectiveLimit)
            .nextCursor(ledgers.size() >= effectiveLimit ? ledgers.get(ledgers.size() - 1).getLedgerId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
    @PostMapping("/{scope}/{unit}/fund") @Operation(operationId = "fundBudget")
    public ResponseEntity<BudgetFundingResponse> fund(@PathVariable String scope, @PathVariable UnitEnum unit,
            @Valid @RequestBody BudgetFundingRequest request, HttpServletRequest httpRequest) {
        BudgetFundingResponse response = repository.fund(scope, unit, request);
        auditRepository.log(AuditLogEntry.builder()
            .tenantId((String) httpRequest.getAttribute("authenticated_tenant_id"))
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .operation("fundBudget")
            .status(200)
            .build());
        return ResponseEntity.ok(response);
    }
}
