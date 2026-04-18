package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventListResponse;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Set;

@RestController @RequestMapping("/v1/admin/events") @Tag(name = "Events")
public class EventAdminController {
    // Per spec v0.1.25.20. timestamp is default — operators reading the
    // event stream expect newest-first by default.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "event_type", "category", "scope", "tenant_id", "timestamp");
    private static final String DEFAULT_SORT_FIELD = "timestamp";
    @Autowired private EventService eventService;

    @GetMapping @Operation(operationId = "listEvents")
    public ResponseEntity<EventListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String event_type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String correlation_id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_dir,
            @Parameter(description = "Filter by W3C trace id (32 lowercase hex chars)",
                schema = @Schema(pattern = "^[0-9a-f]{32}$"))
            @RequestParam(required = false) String trace_id,
            @RequestParam(required = false) String request_id) {
        limit = Math.max(1, Math.min(limit, 100));
        SortSpec sortSpec = parseSortSpec(sort_by, sort_dir);
        String searchNorm = parseSearch(search);
        return ResponseEntity.ok(eventService.list(tenant_id, event_type, category, scope,
            correlation_id, from, to, cursor, limit, sortSpec, searchNorm, trace_id, request_id));
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

    private String parseSearch(String raw) {
        try {
            return SearchSpec.resolve(raw);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
    }

    @GetMapping("/{event_id}") @Operation(operationId = "getEvent")
    public ResponseEntity<Event> get(@PathVariable("event_id") String eventId) {
        return ResponseEntity.ok(eventService.findById(eventId));
    }
}
