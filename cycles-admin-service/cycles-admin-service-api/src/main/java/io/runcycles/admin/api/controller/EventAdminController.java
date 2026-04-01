package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController @RequestMapping("/v1/admin/events") @Tag(name = "Events")
public class EventAdminController {
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
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        limit = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(eventService.list(tenant_id, event_type, category, scope,
            correlation_id, from, to, cursor, limit));
    }

    @GetMapping("/{event_id}") @Operation(operationId = "getEvent")
    public ResponseEntity<Event> get(@PathVariable("event_id") String eventId) {
        return ResponseEntity.ok(eventService.findById(eventId));
    }
}
