package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventListResponse;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Set;

@RestController @RequestMapping("/v1/events") @Tag(name = "Events")
public class EventTenantController {
    @Autowired private EventService eventService;

    private static final Set<String> TENANT_ACCESSIBLE_CATEGORIES = Set.of("budget", "reservation", "tenant");

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
        // Validate tenant-accessible event types
        if (event_type != null) {
            try {
                EventType type = EventType.fromValue(event_type);
                if (!type.isTenantAccessible()) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        "Event type " + event_type + " is admin-only; tenants can query budget.*, reservation.*, tenant.* only", 400);
                }
            } catch (IllegalArgumentException e) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST, "Unknown event type: " + event_type, 400);
            }
        }
        if (category != null && !TENANT_ACCESSIBLE_CATEGORIES.contains(category.toLowerCase())) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "Category " + category + " is admin-only; tenants can query budget, reservation, tenant only", 400);
        }
        return ResponseEntity.ok(eventService.list(tenantId, event_type, category, scope,
            correlation_id, from, to, cursor, limit));
    }
}
