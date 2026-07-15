package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.model.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private WebhookDispatchService webhookDispatchService;
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @InjectMocks private EventService eventService;

    @Test
    void emit_savesEventAndDispatches() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .source("test")
            .build();

        eventService.emit(event);

        verify(eventRepository).save(event);
        verify(webhookDispatchService).dispatchWithSummary(event);
    }

    @Test
    void emit_autoGeneratesEventIdIfNull() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();

        eventService.emit(event);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getEventId()).startsWith("evt_");
    }

    @Test
    void emit_autoSetsTimestampIfNull() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();

        eventService.emit(event);

        assertThat(event.getTimestamp()).isNotNull();
    }

    @Test
    void emit_autoSetsCategoryFromEventType() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();

        eventService.emit(event);

        assertThat(event.getCategory()).isEqualTo(EventCategory.BUDGET);
    }

    @Test
    void emit_doesNotOverrideExistingEventId() {
        Event event = Event.builder()
            .eventId("evt_existing")
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();

        eventService.emit(event);

        assertThat(event.getEventId()).isEqualTo("evt_existing");
    }

    @Test
    void emit_doesNotThrowOnRepositoryFailure() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();
        doThrow(new RuntimeException("DB down")).when(eventRepository).save(any());

        // Should not throw
        eventService.emit(event);

        verify(eventRepository).save(any());
    }

    @Test
    void emit_doesNotThrowOnDispatchFailure() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();
        doThrow(new RuntimeException("Dispatch failed"))
            .when(webhookDispatchService).dispatchWithSummary(any());

        // Should not throw
        eventService.emit(event);

        verify(webhookDispatchService).dispatchWithSummary(any());
    }

    @Test
    void findById_delegatesToRepository() {
        Event expected = Event.builder().eventId("evt_1").build();
        when(eventRepository.findById("evt_1")).thenReturn(expected);

        Event result = eventService.findById("evt_1");

        assertThat(result).isEqualTo(expected);
        verify(eventRepository).findById("evt_1");
    }

    @Test
    void list_clampsLimitToMax100() {
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(101), any(), any(), any(), any()))
            .thenReturn(List.of());

        eventService.list(null, null, null, null, null, null, null, null, 500);

        verify(eventRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), eq(101), any(), any(), any(), any());
    }

    @Test
    void list_clampsLimitToMin1() {
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(2), any(), any(), any(), any()))
            .thenReturn(List.of());

        eventService.list(null, null, null, null, null, null, null, null, 0);

        verify(eventRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), eq(2), any(), any(), any(), any());
    }

    @Test
    void list_returnsHasMoreTrueWhenResultCountExceedsLimit() {
        Event e1 = Event.builder().eventId("evt_1").build();
        Event e2 = Event.builder().eventId("evt_2").build();
        Event e3 = Event.builder().eventId("evt_3").build();
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(3), any(), any(), any(), any()))
            .thenReturn(List.of(e1, e2, e3));

        EventListResponse response = eventService.list(null, null, null, null, null, null, null, null, 2);

        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getNextCursor()).isEqualTo("evt_2");
    }

    @Test
    void list_returnsHasMoreFalseWhenResultCountLessThanLimit() {
        Event e1 = Event.builder().eventId("evt_1").build();
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(51), any(), any(), any(), any()))
            .thenReturn(List.of(e1));

        EventListResponse response = eventService.list(null, null, null, null, null, null, null, null, 50);

        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    void list_emptyResult_returnsNoEvents() {
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
            .thenReturn(List.of());

        EventListResponse response = eventService.list(null, null, null, null, null, null, null, null, 50);

        assertThat(response.getEvents()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    void emit_convenienceOverload_buildsAndEmitsEvent() {
        eventService.emit(EventType.TENANT_CREATED, "tenant-1", "scope1", "admin",
            Actor.builder().build(), Map.of("key", "val"), "corr1", "req1");

        verify(eventRepository).save(argThat(event ->
            event.getEventType() == EventType.TENANT_CREATED &&
            "tenant-1".equals(event.getTenantId()) &&
            EventCategory.TENANT == event.getCategory()));
        verify(webhookDispatchService).dispatchWithSummary(any());
    }

    @Test
    void emit_success_incrementsEmittedCounterWithSuccessTag() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();

        eventService.emit(event);

        double count = meterRegistry.counter("cycles_admin_events_emitted_total",
            "type", EventType.BUDGET_CREATED.getValue(), "result", "success").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void emit_failure_incrementsEmittedCounterWithFailureTag() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();
        doThrow(new RuntimeException("DB down")).when(eventRepository).save(any());

        eventService.emit(event);

        double count = meterRegistry.counter("cycles_admin_events_emitted_total",
            "type", EventType.BUDGET_CREATED.getValue(), "result", "failure").count();
        assertThat(count).isEqualTo(1.0);
    }

    // ---- Runtime payload-shape validation (PR-J) ----

    @Test
    void emit_validPayload_doesNotIncrementInvalidCounter() {
        // Well-formed BUDGET_CREATED payload — matches EventPayloadTypeMapping entry
        // EventDataBudgetLifecycle.
        Map<String, Object> goodPayload = Map.of(
            "ledger_id", "lg_1",
            "scope", "tenant:tenant-1",
            "unit", "USD_MICROCENTS",
            "operation", "CREATE");
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .source("test")
            .data(goodPayload)
            .build();

        eventService.emit(event);

        double invalidCount = meterRegistry.counter("cycles_admin_events_payload_invalid_total",
            "type", EventType.BUDGET_CREATED.getValue(),
            "expected_class", "EventDataBudgetLifecycle").count();
        assertThat(invalidCount).isEqualTo(0.0);
        verify(eventRepository).save(event);
        verify(webhookDispatchService).dispatchWithSummary(event);
    }

    @Test
    void emit_malformedPayload_incrementsInvalidCounter_butStillDelivers() {
        // Extra field 'bogus_field' that EventDataBudgetLifecycle doesn't declare.
        // With @JsonIgnoreProperties(ignoreUnknown=false) the round-trip fails.
        Map<String, Object> badPayload = Map.of(
            "ledger_id", "lg_1",
            "scope", "tenant:tenant-1",
            "unit", "USD_MICROCENTS",
            "operation", "CREATE",
            "bogus_field", "should-not-be-here");
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .source("test")
            .data(badPayload)
            .build();

        eventService.emit(event);

        // Validation failure logged + metric incremented
        double invalidCount = meterRegistry.counter("cycles_admin_events_payload_invalid_total",
            "type", EventType.BUDGET_CREATED.getValue(),
            "expected_class", "EventDataBudgetLifecycle").count();
        assertThat(invalidCount).isEqualTo(1.0);

        // Event still delivered — observability, not enforcement
        verify(eventRepository).save(event);
        verify(webhookDispatchService).dispatchWithSummary(event);
        double emittedCount = meterRegistry.counter("cycles_admin_events_emitted_total",
            "type", EventType.BUDGET_CREATED.getValue(), "result", "success").count();
        assertThat(emittedCount).isEqualTo(1.0);
    }

    @Test
    void emit_dispatchEnqueueFailureIncrementsFailureMetric() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .build();
        when(webhookDispatchService.dispatchWithSummary(event))
            .thenReturn(new WebhookDispatchService.DispatchSummary(0, 0, 1));

        eventService.emitRequired(event);

        assertThat(meterRegistry.counter("cycles_admin_events_emitted_total",
            "type", EventType.BUDGET_CREATED.getValue(), "result", "failure").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.counter("cycles_admin_events_emitted_total",
            "type", EventType.BUDGET_CREATED.getValue(), "result", "success").count())
            .isZero();
    }

    @Test
    void emit_nullData_skipsPayloadValidation() {
        // Some event types don't carry a data payload, or the producer didn't build one.
        // Validation should be a no-op, not a warning.
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .source("test")
            .data(null)
            .build();

        eventService.emit(event);

        double invalidCount = meterRegistry.counter("cycles_admin_events_payload_invalid_total",
            "type", EventType.BUDGET_CREATED.getValue(),
            "expected_class", "EventDataBudgetLifecycle").count();
        assertThat(invalidCount).isEqualTo(0.0);
    }

    @Test
    void emit_wrongEnumValue_incrementsInvalidCounter() {
        // "operation" not in spec enum — EventDataBudgetLifecycle.operation is BudgetOperation.
        Map<String, Object> badPayload = Map.of(
            "ledger_id", "lg_1",
            "scope", "tenant:tenant-1",
            "unit", "USD_MICROCENTS",
            "operation", "WOMBAT"); // not a valid BudgetOperation
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .source("test")
            .data(badPayload)
            .build();

        eventService.emit(event);

        double invalidCount = meterRegistry.counter("cycles_admin_events_payload_invalid_total",
            "type", EventType.BUDGET_CREATED.getValue(),
            "expected_class", "EventDataBudgetLifecycle").count();
        assertThat(invalidCount).isEqualTo(1.0);
    }

    @Test
    void emitFailureDiagnosticsHandleNullEventAndNullEventType() {
        eventService.emit((Event) null);

        Event untyped = Event.builder()
            .tenantId("tenant-1").scope("tenant:tenant-1")
            .correlationId("corr").requestId("req").traceId("trace").source("test")
            .build();
        doThrow(new IllegalStateException("storage failed")).when(eventRepository).save(untyped);
        eventService.emit(untyped);

        assertThat(meterRegistry.counter("cycles_admin_events_emitted_total",
            "type", "unknown", "result", "failure").count()).isEqualTo(2.0);
    }

    @Test
    void traceContextIsCopiedWhenPresentAndRemainsNullWhenAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            Event absent = Event.builder().eventType(EventType.BUDGET_CREATED).build();
            eventService.emitRequired(absent);
            assertThat(absent.getTraceId()).isNull();

            request.setAttribute("traceId", "trace-from-request");
            Event present = Event.builder().eventType(EventType.BUDGET_CREATED).build();
            eventService.emitRequired(present);
            assertThat(present.getTraceId()).isEqualTo("trace-from-request");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void emitRequired_preservesExistingTimestamp() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .timestamp(timestamp)
            .build();

        eventService.emitRequired(event);

        assertThat(event.getTimestamp()).isEqualTo(timestamp);
    }
}
