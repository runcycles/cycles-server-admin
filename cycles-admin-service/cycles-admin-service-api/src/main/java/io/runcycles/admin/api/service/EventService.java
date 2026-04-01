package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {
    private static final Logger LOG = LoggerFactory.getLogger(EventService.class);
    private final EventRepository eventRepository;
    private final WebhookDispatchService webhookDispatchService;

    public EventService(EventRepository eventRepository, WebhookDispatchService webhookDispatchService) {
        this.eventRepository = eventRepository;
        this.webhookDispatchService = webhookDispatchService;
    }

    /**
     * Emit an event. Saves to store and dispatches to matching webhooks.
     * Non-blocking: failures are logged but do not propagate to caller.
     */
    public void emit(Event event) {
        try {
            if (event.getEventId() == null) {
                event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }
            if (event.getTimestamp() == null) {
                event.setTimestamp(Instant.now());
            }
            if (event.getCategory() == null && event.getEventType() != null) {
                event.setCategory(event.getEventType().getCategory());
            }
            eventRepository.save(event);
            webhookDispatchService.dispatch(event);
        } catch (Exception e) {
            LOG.error("Failed to emit event {}: {}", event.getEventType(), e.getMessage(), e);
        }
    }

    /**
     * Convenience: build and emit an event in one call.
     */
    public void emit(EventType type, String tenantId, String scope, String source,
                     Actor actor, Map<String, Object> data, String correlationId, String requestId) {
        Event event = Event.builder()
            .eventType(type)
            .category(type.getCategory())
            .tenantId(tenantId)
            .scope(scope)
            .source(source)
            .actor(actor)
            .data(data)
            .correlationId(correlationId)
            .requestId(requestId)
            .build();
        emit(event);
    }

    public Event findById(String eventId) {
        return eventRepository.findById(eventId);
    }

    public EventListResponse list(String tenantId, String eventType, String category,
                                   String scope, String correlationId, Instant from, Instant to,
                                   String cursor, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        List<Event> events = eventRepository.list(tenantId, eventType, category, scope,
            correlationId, from, to, cursor, effectiveLimit);
        return EventListResponse.builder()
            .events(events)
            .hasMore(events.size() >= effectiveLimit)
            .nextCursor(events.size() >= effectiveLimit ? events.get(events.size() - 1).getEventId() : null)
            .build();
    }
}
