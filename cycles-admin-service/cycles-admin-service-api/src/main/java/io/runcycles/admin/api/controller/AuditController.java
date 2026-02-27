package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.AuditRepository;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/admin/audit") @Tag(name = "Audit")
public class AuditController {
    @Autowired private AuditRepository repository;
    @GetMapping("/logs") @Operation(operationId = "listAuditLogs")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String tenant_id, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(Map.of("logs", repository.list(tenant_id != null ? tenant_id : "SYSTEM", limit), "has_more", false));
    }
}
