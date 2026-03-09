package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogListResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
@RestController @RequestMapping("/v1/admin/audit") @Tag(name = "Audit")
public class AuditController {
    @Autowired private AuditRepository repository;
    @GetMapping("/logs") @Operation(operationId = "listAuditLogs")
    public ResponseEntity<AuditLogListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String key_id,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var logs = repository.list(tenant_id, key_id, operation, status, from, to, cursor, effectiveLimit);
        AuditLogListResponse response = AuditLogListResponse.builder()
            .logs(logs)
            .hasMore(logs.size() >= effectiveLimit)
            .nextCursor(logs.size() >= effectiveLimit ? logs.get(logs.size() - 1).getLogId() : null)
            .build();
        return ResponseEntity.ok(response);
    }
}
