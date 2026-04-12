package io.runcycles.admin.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meterRegistry;
    // Dedicated ObjectMapper for payload round-trip validation. Independent of
    // any Spring-configured mapper so producer-vs-spec drift is evaluated
    // against the default Jackson semantics that govern the wire format.
    private final ObjectMapper payloadMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public EventService(EventRepository eventRepository,
                        WebhookDispatchService webhookDispatchService,
                        MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.webhookDispatchService = webhookDispatchService;
        this.meterRegistry = meterRegistry;
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
            validatePayloadShape(event);
            eventRepository.save(event);
            webhookDispatchService.dispatch(event);
            recordEmitted(event.getEventType(), "success");
        } catch (Exception e) {
            LOG.error("Failed to emit event {}: {}", event.getEventType(), e.getMessage(), e);
            recordEmitted(event.getEventType(), "failure");
        }
    }

    /**
     * Observability: validate that {@code event.data} round-trips through the
     * Java class the spec assigns to {@code event.eventType}, per
     * {@link EventPayloadTypeMapping}. Emits a WARN log and increments a
     * metric on mismatch — does NOT throw. The event still gets delivered to
     * webhooks because downstream consumers should stay resilient to producer
     * bugs; the signal is for operators to investigate via alerts on the
     * {@code cycles_admin_events_payload_invalid_total} counter.
     *
     * <p>This is the runtime complement to {@code EventPayloadContractTest},
     * closing the "producer path exists but isn't unit-tested" hole.
     */
    private void validatePayloadShape(Event event) {
        if (event.getEventType() == null || event.getData() == null) return;
        Class<?> expected = EventPayloadTypeMapping.payloadClass(event.getEventType()).orElse(null);
        if (expected == null) return;
        try {
            payloadMapper.convertValue(event.getData(), expected);
        } catch (IllegalArgumentException e) {
            LOG.warn("Event payload shape mismatch for {} (event_id={}): payload does not "
                    + "round-trip through {}. Producer bug — event will still be persisted "
                    + "and dispatched. Cause: {}",
                    event.getEventType().getValue(),
                    event.getEventId(),
                    expected.getSimpleName(),
                    e.getMessage());
            Counter.builder("cycles_admin_events_payload_invalid_total")
                    .description("Count of event emissions where the data payload did not "
                            + "round-trip through the EventPayloadTypeMapping-assigned class.")
                    .tag("type", event.getEventType().getValue())
                    .tag("expected_class", expected.getSimpleName())
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void recordEmitted(EventType type, String result) {
        Counter.builder("cycles_admin_events_emitted_total")
            .description("Count of domain events emitted, labelled by event type and result")
            .tag("type", type != null ? type.getValue() : "unknown")
            .tag("result", result)
            .register(meterRegistry)
            .increment();
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
