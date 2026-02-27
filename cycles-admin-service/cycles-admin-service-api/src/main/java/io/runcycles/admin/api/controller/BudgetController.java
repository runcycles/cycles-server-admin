package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.budget.BudgetCreateRequest;
import io.runcycles.admin.model.budget.BudgetFundingRequest;
import io.runcycles.admin.model.budget.BudgetFundingResponse;
import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.shared.UnitEnum;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/admin/budgets") @Tag(name = "Budgets")
public class BudgetController {
    @Autowired private BudgetRepository repository;
    @PostMapping @Operation(operationId = "createBudget")
    public ResponseEntity<BudgetLedger> create(@Valid @RequestBody BudgetCreateRequest request) {
        return ResponseEntity.status(201).body(repository.create(request));
    }
    @GetMapping @Operation(operationId = "listBudgets")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = true) String tenant_id) {
        return ResponseEntity.ok(Map.of("ledgers", repository.list(tenant_id), "has_more", false));
    }
    @PostMapping("/{scope}/{unit}/fund") @Operation(operationId = "fundBudget")
    public ResponseEntity<BudgetFundingResponse> fund(@PathVariable String scope, @PathVariable UnitEnum unit, @Valid @RequestBody BudgetFundingRequest request) {
        return ResponseEntity.ok(repository.fund(scope, unit, request));
    }
}
