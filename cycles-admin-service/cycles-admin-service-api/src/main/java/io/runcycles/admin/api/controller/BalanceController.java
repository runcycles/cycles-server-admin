package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.budget.BalanceQueryResponse;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.shared.UnitEnum;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/v1/balances") @Tag(name = "Balances") @Validated
public class BalanceController {
    private static final Logger LOG = LoggerFactory.getLogger(BalanceController.class);
    @Autowired private BudgetRepository repository;
    @GetMapping @Operation(operationId = "getBalances", summary = "Query budget balances")
    public ResponseEntity<BalanceQueryResponse> query(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String scope_prefix,
            @RequestParam(required = false) UnitEnum unit,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest httpRequest) {
        // Enforce tenant scoping: always use authenticated tenant, ignore user-supplied tenant_id
        String effectiveTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        LOG.info("GET /v1/balances - tenant: {}", effectiveTenantId);
        var ledgers = repository.list(effectiveTenantId, scope_prefix, unit, BudgetStatus.ACTIVE, cursor, limit);
        boolean hasMore = ledgers.size() >= limit;
        String nextCursor = hasMore && !ledgers.isEmpty() ? ledgers.get(ledgers.size() - 1).getLedgerId() : null;
        BalanceQueryResponse response = BalanceQueryResponse.builder()
            .balances(ledgers)
            .hasMore(hasMore)
            .nextCursor(nextCursor)
            .build();
        return ResponseEntity.ok(response);
    }
}
