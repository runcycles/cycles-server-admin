package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.budget.BalanceQueryResponse;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.shared.UnitEnum;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/v1/balances") @Tag(name = "Balances")
public class BalanceController {
    @Autowired private BudgetRepository repository;
    @GetMapping @Operation(operationId = "queryBalances")
    public ResponseEntity<BalanceQueryResponse> query(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String scope_prefix,
            @RequestParam(required = false) UnitEnum unit,
            HttpServletRequest httpRequest) {
        // Enforce tenant scoping: always use authenticated tenant, ignore user-supplied tenant_id
        String effectiveTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        var ledgers = repository.list(effectiveTenantId, scope_prefix, unit, BudgetStatus.ACTIVE, null, 1000);
        BalanceQueryResponse response = BalanceQueryResponse.builder()
            .balances(ledgers)
            .hasMore(false)
            .build();
        return ResponseEntity.ok(response);
    }
}
