package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogListResponse;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Set;
@RestController @RequestMapping("/v1/admin/audit") @Tag(name = "Audit")
public class AuditController {
    // Per spec v0.1.25.20. timestamp is default — auditors expect
    // newest-first by default, matching the existing cursor chain.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "timestamp", "operation", "resource_type", "tenant_id", "key_id", "status");
    private static final String DEFAULT_SORT_FIELD = "timestamp";
    @Autowired private AuditRepository repository;
    @GetMapping("/logs") @Operation(operationId = "listAuditLogs")
    public ResponseEntity<AuditLogListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String key_id,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String resource_type,
            @RequestParam(required = false) String resource_id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_dir) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        SortSpec sortSpec = parseSortSpec(sort_by, sort_dir);
        var logs = repository.list(tenant_id, key_id, operation, status, resource_type, resource_id,
            from, to, cursor, effectiveLimit, sortSpec);
        AuditLogListResponse response = AuditLogListResponse.builder()
            .logs(logs)
            .hasMore(logs.size() >= effectiveLimit)
            .nextCursor(logs.size() >= effectiveLimit ? logs.get(logs.size() - 1).getLogId() : null)
            .build();
        return ResponseEntity.ok(response);
    }

    /**
     * Parse sort_by / sort_dir query params into a validated SortSpec.
     * See TenantController.parseSortSpec for the shared rationale.
     */
    private SortSpec parseSortSpec(String sortBy, String sortDir) {
        SortDirection direction;
        try {
            direction = SortDirection.fromWire(sortDir);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
        try {
            return SortSpec.resolve(sortBy, direction, ALLOWED_SORT_FIELDS, DEFAULT_SORT_FIELD);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
    }
}
