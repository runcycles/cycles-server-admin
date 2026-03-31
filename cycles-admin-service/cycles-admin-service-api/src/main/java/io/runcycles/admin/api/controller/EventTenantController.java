package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.model.event.EventListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController @RequestMapping("/v1/events") @Tag(name = "Events")
public class EventTenantController {
    @Autowired private EventService eventService;

    @GetMapping @Operation(operationId = "listTenantEvents")
    public ResponseEntity<EventListResponse> list(
            @RequestParam(required = false) String event_type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String correlation_id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        // Auto-scope to authenticated tenant, filter to tenant-accessible types only
        return ResponseEntity.ok(eventService.list(tenantId, event_type, category, scope,
            correlation_id, from, to, cursor, limit));
    }
}
