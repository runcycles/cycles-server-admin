package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.model.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @InjectMocks private EventService eventService;

    @Test
    void emit_savesEventAndDispatches() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("t1")
            .source("test")
            .build();

        eventService.emit(event);

        verify(eventRepository).save(event);
        verify(webhookDispatchService).dispatch(event);
    }

    @Test
    void emit_autoGeneratesEventIdIfNull() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("t1")
            .build();

        eventService.emit(event);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getEventId()).startsWith("evt_");
    }

    @Test
    void emit_autoSetsTimestampIfNull() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("t1")
            .build();

        eventService.emit(event);

        assertThat(event.getTimestamp()).isNotNull();
    }

    @Test
    void emit_autoSetsCategoryFromEventType() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("t1")
            .build();

        eventService.emit(event);

        assertThat(event.getCategory()).isEqualTo(EventCategory.BUDGET);
    }

    @Test
    void emit_doesNotOverrideExistingEventId() {
        Event event = Event.builder()
            .eventId("evt_existing")
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("t1")
            .build();

        eventService.emit(event);

        assertThat(event.getEventId()).isEqualTo("evt_existing");
    }

    @Test
    void emit_doesNotThrowOnRepositoryFailure() {
        Event event = Event.builder()
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("t1")
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
            .tenantId("t1")
            .build();
        doThrow(new RuntimeException("Dispatch failed")).when(webhookDispatchService).dispatch(any());

        // Should not throw
        eventService.emit(event);

        verify(webhookDispatchService).dispatch(any());
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
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(100)))
            .thenReturn(List.of());

        eventService.list(null, null, null, null, null, null, null, null, 500);

        verify(eventRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), eq(100));
    }

    @Test
    void list_clampsLimitToMin1() {
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(1)))
            .thenReturn(List.of());

        eventService.list(null, null, null, null, null, null, null, null, 0);

        verify(eventRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), eq(1));
    }

    @Test
    void list_returnsHasMoreTrueWhenResultCountEqualsLimit() {
        Event e1 = Event.builder().eventId("evt_1").build();
        Event e2 = Event.builder().eventId("evt_2").build();
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(2)))
            .thenReturn(List.of(e1, e2));

        EventListResponse response = eventService.list(null, null, null, null, null, null, null, null, 2);

        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getNextCursor()).isEqualTo("evt_2");
    }

    @Test
    void list_returnsHasMoreFalseWhenResultCountLessThanLimit() {
        Event e1 = Event.builder().eventId("evt_1").build();
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(50)))
            .thenReturn(List.of(e1));

        EventListResponse response = eventService.list(null, null, null, null, null, null, null, null, 50);

        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    void list_emptyResult_returnsNoEvents() {
        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of());

        EventListResponse response = eventService.list(null, null, null, null, null, null, null, null, 50);

        assertThat(response.getEvents()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    void emit_convenienceOverload_buildsAndEmitsEvent() {
        eventService.emit(EventType.TENANT_CREATED, "t1", "scope1", "admin",
            Actor.builder().build(), Map.of("key", "val"), "corr1", "req1");

        verify(eventRepository).save(argThat(event ->
            event.getEventType() == EventType.TENANT_CREATED &&
            "t1".equals(event.getTenantId()) &&
            EventCategory.TENANT == event.getCategory()));
        verify(webhookDispatchService).dispatch(any());
    }
}
