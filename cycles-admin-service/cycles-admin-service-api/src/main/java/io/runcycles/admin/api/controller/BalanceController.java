package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.shared.UnitEnum;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/balances") @Tag(name = "Balances")
public class BalanceController {
    @Autowired private BudgetRepository repository;
    @GetMapping @Operation(operationId = "queryBalances")
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String scope_prefix,
            @RequestParam(required = false) UnitEnum unit,
            HttpServletRequest httpRequest) {
        // Derive tenant_id from auth if not provided
        String effectiveTenantId = tenant_id;
        if (effectiveTenantId == null || effectiveTenantId.isBlank()) {
            effectiveTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        }
        var ledgers = repository.list(effectiveTenantId, scope_prefix, unit, BudgetStatus.ACTIVE, null, 1000);
        return ResponseEntity.ok(Map.of("balances", ledgers, "has_more", false));
    }
}
