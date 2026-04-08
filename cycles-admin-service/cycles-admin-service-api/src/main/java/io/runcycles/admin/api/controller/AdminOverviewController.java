package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.AdminOverviewService;
import io.runcycles.admin.model.shared.AdminOverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/overview")
@Tag(name = "Dashboard")
public class AdminOverviewController {

    private final AdminOverviewService overviewService;

    public AdminOverviewController(AdminOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping
    @Operation(operationId = "getAdminOverview")
    public ResponseEntity<AdminOverviewResponse> getOverview() {
        return ResponseEntity.ok(overviewService.buildOverview());
    }
}
