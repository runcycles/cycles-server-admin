package io.runcycles.admin.api.service;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.data.repository.TenantCloseWorkRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.support.CascadeMutationResult;
import io.runcycles.admin.data.repository.support.TenantCloseOutboxItem;
import io.runcycles.admin.model.auth.ApiKeyStatus;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.UnitEnum;
import io.runcycles.admin.model.webhook.WebhookStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec v0.1.25.29 Rule 1 orchestration and durable-observability coverage. */
@ExtendWith(MockitoExtension.class)
class TenantCloseCascadeServiceTest {

    @Mock private BudgetRepository budgetRepository;
    @Mock private WebhookRepository webhookRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private AuditRepository auditRepository;
    @Mock private EventService eventService;
    @Mock private TenantCloseWorkRepository workRepository;

    private TenantCloseCascadeService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = new TenantCloseCascadeService();
        ReflectionTestUtils.setField(service, "budgetRepository", budgetRepository);
        ReflectionTestUtils.setField(service, "webhookRepository", webhookRepository);
        ReflectionTestUtils.setField(service, "apiKeyRepository", apiKeyRepository);
        ReflectionTestUtils.setField(service, "auditRepository", auditRepository);
        ReflectionTestUtils.setField(service, "eventService", eventService);
        ReflectionTestUtils.setField(service, "workRepository", workRepository);
        request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req_abc");
        request.setAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE, "trace_xyz");
    }

    @Test
    void cascade_noOwnedObjects_returnsZeroCountsAndCompletesWork() {
        readyWorkQueue(intent("req_abc", "trace_xyz"), List.of());
        when(budgetRepository.cascadeClose("t1")).thenReturn(complete(List.of()));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(complete(List.of()));
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(complete(List.of()));

        TenantCloseCascadeService.CascadeResult result = service.cascade("t1", request);

        assertThat(result.budgetsClosed()).isZero();
        assertThat(result.webhooksDisabled()).isZero();
        assertThat(result.apiKeysRevoked()).isZero();
        assertThat(result.reservationsReleased()).isZero();
        assertThat(result.complete()).isTrue();
        verify(auditRepository, never()).logRequired(any());
        verify(eventService, never()).emitRequired(any());
        verify(workRepository).completeIfDrained("t1");
        verify(workRepository).releaseLease("t1", "lease-token");
    }

    @Test
    void cascade_mixedChildren_persistsPerChildAuditAndEventBeforeAcknowledging() {
        List<TenantCloseOutboxItem> outbox = List.of(
            budgetItem("item-b1", "ldg_frozen", "tenant:t1/app:a", 250L),
            budgetItem("item-b2", "ldg_active", "tenant:t1/app:b", 0L),
            new TenantCloseOutboxItem("item-w1", "t1", "webhook_subscription",
                "sub_1", "primary", null, null, "ACTIVE", 0L),
            new TenantCloseOutboxItem("item-k1", "t1", "api_key",
                "key_1", "ci", null, null, "ACTIVE", 0L));
        readyWorkQueue(intent("req_abc", "trace_xyz"), outbox);
        when(budgetRepository.cascadeClose("t1")).thenReturn(complete(List.of(
            new BudgetRepository.CascadeCloseBudgetOutcome(
                "ldg_frozen", "tenant:t1/app:a", UnitEnum.USD_MICROCENTS,
                BudgetStatus.FROZEN, 250L),
            new BudgetRepository.CascadeCloseBudgetOutcome(
                "ldg_active", "tenant:t1/app:b", UnitEnum.USD_MICROCENTS,
                BudgetStatus.ACTIVE, 0L))));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(complete(List.of(
            new WebhookRepository.CascadeDisableOutcome("sub_1", "primary", WebhookStatus.ACTIVE))));
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(complete(List.of(
            new ApiKeyRepository.CascadeRevokeOutcome("key_1", "ci", ApiKeyStatus.ACTIVE))));

        TenantCloseCascadeService.CascadeResult result = service.cascade("t1", request);

        assertThat(result.budgetsClosed()).isEqualTo(2);
        assertThat(result.webhooksDisabled()).isEqualTo(1);
        assertThat(result.apiKeysRevoked()).isEqualTo(1);
        assertThat(result.reservationsReleased()).isEqualTo(250L);

        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository, times(4)).logRequired(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).allSatisfy(entry -> {
            assertThat(entry.getLogId()).startsWith("log_");
            assertThat(entry.getOperation()).isEqualTo("tenant_close_cascade");
            assertThat(entry.getRequestId()).isEqualTo("req_abc");
            assertThat(entry.getTraceId()).isEqualTo("trace_xyz");
            assertThat(entry.getMetadata()).containsEntry("cascade", "tenant_close");
        });

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventService, times(5)).emitRequired(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).allSatisfy(event -> {
            assertThat(event.getEventId()).startsWith("evt_");
            assertThat(event.getCorrelationId()).isEqualTo("tenant_close_cascade:t1:req_abc");
            assertThat(event.getRequestId()).isEqualTo("req_abc");
            assertThat(event.getTraceId()).isEqualTo("trace_xyz");
        });
        assertThat(eventCaptor.getAllValues()).extracting(Event::getEventType)
            .containsExactlyInAnyOrder(
                EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE,
                EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE,
                EventType.RESERVATION_RELEASED_VIA_TENANT_CASCADE,
                EventType.WEBHOOK_DISABLED_VIA_TENANT_CASCADE,
                EventType.API_KEY_REVOKED_VIA_TENANT_CASCADE);
        outbox.forEach(item -> verify(workRepository).acknowledge("t1", item.itemId()));
    }

    @Test
    void cascade_onlyBudgetWithZeroReserved_skipsReservationReleaseEvent() {
        readyWorkQueue(intent("req_abc", "trace_xyz"),
            List.of(budgetItem("item-b1", "ldg_1", "tenant:t1", 0L)));
        when(budgetRepository.cascadeClose("t1")).thenReturn(complete(List.of(
            new BudgetRepository.CascadeCloseBudgetOutcome(
                "ldg_1", "tenant:t1", UnitEnum.USD_MICROCENTS,
                BudgetStatus.ACTIVE, 0L))));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(complete(List.of()));
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(complete(List.of()));

        service.cascade("t1", request);

        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(eventService).emitRequired(events.capture());
        assertThat(events.getValue().getEventType())
            .isEqualTo(EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE);
    }

    @Test
    void cascade_partialFailures_areReturnedAndSuccessfulOutboxRowsStillPersist() {
        readyWorkQueue(intent("req_abc", "trace_xyz"), List.of(
            new TenantCloseOutboxItem("item-k1", "t1", "api_key",
                "key_ok", "working", null, null, "ACTIVE", 0L)));
        var budgetResult = new CascadeMutationResult<BudgetRepository.CascadeCloseBudgetOutcome>(
            List.of(), List.of(new CascadeMutationResult.RowFailure(
                "budget:tenant:t1:USD_MICROCENTS", "JedisException")));
        var webhookResult = new CascadeMutationResult<WebhookRepository.CascadeDisableOutcome>(
            List.of(), List.of(new CascadeMutationResult.RowFailure("sub_1", "JsonParseException")));
        var keyResult = new CascadeMutationResult<ApiKeyRepository.CascadeRevokeOutcome>(
            List.of(new ApiKeyRepository.CascadeRevokeOutcome(
                "key_ok", "working", ApiKeyStatus.ACTIVE)),
            List.of(new CascadeMutationResult.RowFailure("key_bad", "JedisException")));
        when(budgetRepository.cascadeClose("t1")).thenReturn(budgetResult);
        when(webhookRepository.cascadeDisable("t1")).thenReturn(webhookResult);
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(keyResult);

        TenantCloseCascadeService.CascadeResult result = service.cascade("t1", request);

        assertThat(result.complete()).isFalse();
        assertThat(result.failedResources()).containsExactly(
            "budget:tenant:t1:USD_MICROCENTS", "webhook:sub_1", "api_key:key_bad");
        assertThat(result.apiKeysRevoked()).isOne();
        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        verify(eventService).emitRequired(event.capture());
        assertThat(event.getValue().getEventType())
            .isEqualTo(EventType.API_KEY_REVOKED_VIA_TENANT_CASCADE);
        verify(workRepository).reschedule("t1", 30_000L);
        verify(workRepository, never()).completeIfDrained("t1");
    }

    @Test
    void cascade_failedEmission_keepsOutboxUnacknowledgedAndReschedules() {
        readyWorkQueue(intent("req_abc", "trace_xyz"),
            List.of(budgetItem("item-b1", "ldg_1", "tenant:t1", 0L)));
        when(budgetRepository.cascadeClose("t1")).thenReturn(complete(List.of()));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(complete(List.of()));
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(complete(List.of()));
        doThrow(new IllegalStateException("event store unavailable"))
            .when(eventService).emitRequired(any());

        TenantCloseCascadeService.CascadeResult result = service.cascade("t1", request);

        assertThat(result.failedResources()).containsExactly("outbox:item-b1");
        verify(workRepository, never()).acknowledge(eq("t1"), eq("item-b1"));
        verify(workRepository).reschedule("t1", 30_000L);
    }

    @Test
    void cascade_missingRequestId_usesPersistedSentinelCorrelationId() {
        MockHttpServletRequest bareReq = new MockHttpServletRequest();
        readyWorkQueue(intent(null, null),
            List.of(budgetItem("item-b1", "ldg_1", "tenant:t1", 0L)));
        when(budgetRepository.cascadeClose("t1")).thenReturn(complete(List.of()));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(complete(List.of()));
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(complete(List.of()));

        service.cascade("t1", bareReq);

        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        verify(eventService).emitRequired(event.capture());
        assertThat(event.getValue().getCorrelationId())
            .isEqualTo("tenant_close_cascade:t1:no-req");
    }

    @Test
    void cascade_heldLease_doesNotMutateChildrenAndReschedulesQuickly() {
        when(workRepository.tryAcquireLease("t1")).thenReturn(null);

        TenantCloseCascadeService.CascadeResult result = service.cascade("t1", request);

        assertThat(result.failedResources()).containsExactly("cascade:lease:in_progress");
        verify(workRepository).reschedule("t1", 1_000L);
        verify(budgetRepository, never()).cascadeClose(any());
        verify(webhookRepository, never()).cascadeDisable(any());
        verify(apiKeyRepository, never()).cascadeRevoke(any(), any());
    }

    @Test
    void cascadeResult_emptyIsZeroed() {
        TenantCloseCascadeService.CascadeResult empty = TenantCloseCascadeService.CascadeResult.empty();
        assertThat(empty.budgetsClosed()).isZero();
        assertThat(empty.webhooksDisabled()).isZero();
        assertThat(empty.apiKeysRevoked()).isZero();
        assertThat(empty.reservationsReleased()).isZero();
    }

    private void readyWorkQueue(TenantCloseWorkRepository.Intent intent,
                                List<TenantCloseOutboxItem> outbox) {
        when(workRepository.tryAcquireLease("t1")).thenReturn("lease-token");
        when(workRepository.findIntent("t1")).thenReturn(Optional.of(intent));
        when(workRepository.listOutbox("t1")).thenReturn(outbox);
        lenient().when(workRepository.completeIfDrained("t1")).thenReturn(true);
    }

    private static TenantCloseWorkRepository.Intent intent(String requestId, String traceId) {
        return new TenantCloseWorkRepository.Intent("t1", requestId, traceId,
            "tenant_close_cascade:t1:" + (requestId == null ? "no-req" : requestId),
            "127.0.0.1", "test-agent", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static TenantCloseOutboxItem budgetItem(String itemId, String ledgerId,
                                                     String scope, long released) {
        return new TenantCloseOutboxItem(itemId, "t1", "budget", ledgerId, null,
            scope, UnitEnum.USD_MICROCENTS.name(), "ACTIVE", released);
    }

    private static <T> CascadeMutationResult<T> complete(List<T> succeeded) {
        return new CascadeMutationResult<>(succeeded, List.of());
    }
}
