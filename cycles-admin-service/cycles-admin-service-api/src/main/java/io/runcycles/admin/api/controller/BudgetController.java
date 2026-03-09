package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.BudgetRepository;
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
    @PostMapping @Operation(operationId = "createBudget")
    public ResponseEntity<BudgetLedger> create(@Valid @RequestBody BudgetCreateRequest request, HttpServletRequest httpRequest) {
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            String authTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
            if (authTenantId != null) {
                request.setTenantId(authTenantId);
            }
        }
        return ResponseEntity.status(201).body(repository.create(request));
    }
    @GetMapping @Operation(operationId = "listBudgets")
    public ResponseEntity<BudgetListResponse> list(
            @RequestParam(required = true) String tenant_id,
            @RequestParam(required = false) String scope_prefix,
            @RequestParam(required = false) UnitEnum unit,
            @RequestParam(required = false) BudgetStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int effectiveLimit = Math.min(limit, 100);
        var ledgers = repository.list(tenant_id, scope_prefix, unit, status, cursor, effectiveLimit);
        BudgetListResponse response = BudgetListResponse.builder()
            .ledgers(ledgers)
            .hasMore(ledgers.size() >= effectiveLimit)
            .nextCursor(ledgers.size() >= effectiveLimit ? ledgers.get(ledgers.size() - 1).getLedgerId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
    @PostMapping("/{scope}/{unit}/fund") @Operation(operationId = "fundBudget")
    public ResponseEntity<BudgetFundingResponse> fund(@PathVariable String scope, @PathVariable UnitEnum unit, @Valid @RequestBody BudgetFundingRequest request) {
        return ResponseEntity.ok(repository.fund(scope, unit, request));
    }
}
