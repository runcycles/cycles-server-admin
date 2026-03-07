package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/balances") @Tag(name = "Balances")
public class BalanceController {
    @Autowired private BudgetRepository repository;
    @GetMapping @Operation(operationId = "queryBalances")
    public ResponseEntity<Map<String, Object>> query(@RequestParam(required = true) String tenant_id) {
        return ResponseEntity.ok(Map.of("balances", repository.list(tenant_id), "has_more", false));
    }
}
