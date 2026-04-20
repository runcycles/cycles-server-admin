package io.runcycles.admin.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.auth.ApiKeyStatus;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.budget.BudgetStatus;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec v0.1.25.29 Rule 1 unit coverage for {@link TenantCloseCascadeService}.
 *
 * <p>Pins the orchestration contract: per-child audit + event fan-out
 * under a shared correlation_id; the aggregate
 * {@code RESERVATION_RELEASED_VIA_TENANT_CASCADE} event only fires when a
 * closed budget had {@code reserved > 0}; and a zero-owned-objects
 * cascade is cleanly a no-op (idempotency invariant — re-issuing close
 * on an already-closed tenant emits no extra writes).
 */
@ExtendWith(MockitoExtension.class)
class TenantCloseCascadeServiceTest {

    @Mock private BudgetRepository budgetRepository;
    @Mock private WebhookRepository webhookRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private AuditRepository auditRepository;
    @Mock private EventService eventService;

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
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req_abc");
        request.setAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE, "trace_xyz");
    }

    @Test
    void cascade_noOwnedObjects_returnsZeroCountsAndEmitsNothing() {
        when(budgetRepository.cascadeClose("t1")).thenReturn(List.of());
        when(webhookRepository.cascadeDisable("t1")).thenReturn(List.of());
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(List.of());

        TenantCloseCascadeService.CascadeResult result = service.cascade("t1", request);

        assertThat(result.budgetsClosed()).isZero();
        assertThat(result.webhooksDisabled()).isZero();
        assertThat(result.apiKeysRevoked()).isZero();
        assertThat(result.reservationsReleased()).isZero();
        verify(auditRepository, never()).log(any());
        verify(eventService, never()).emit(any(), anyString(), any(), anyString(),
            any(), any(), anyString(), anyString());
    }

    @Test
    void cascade_mixedChildren_emitsPerChildAuditAndEventUnderCorrelationId() {
        when(budgetRepository.cascadeClose("t1")).thenReturn(List.of(
            new BudgetRepository.CascadeCloseBudgetOutcome(
                "ldg_frozen", "tenant:t1/app:a", UnitEnum.USD_MICROCENTS,
                BudgetStatus.FROZEN, 250L),
            new BudgetRepository.CascadeCloseBudgetOutcome(
                "ldg_active", "tenant:t1/app:b", UnitEnum.USD_MICROCENTS,
                BudgetStatus.ACTIVE, 0L)));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(List.of(
            new WebhookRepository.CascadeDisableOutcome("sub_1", "primary", WebhookStatus.ACTIVE)));
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(List.of(
            new ApiKeyRepository.CascadeRevokeOutcome("key_1", "ci", ApiKeyStatus.ACTIVE)));

        TenantCloseCascadeService.CascadeResult result = service.cascade("t1", request);

        assertThat(result.budgetsClosed()).isEqualTo(2);
        assertThat(result.webhooksDisabled()).isEqualTo(1);
        assertThat(result.apiKeysRevoked()).isEqualTo(1);
        // Only the FROZEN budget had reserved>0 at close time.
        assertThat(result.reservationsReleased()).isEqualTo(250L);

        // Per-child audit: 2 budgets + 1 webhook + 1 api key = 4 entries, all
        // sharing the originating request_id + trace_id so operators can JOIN
        // by either — the admin-plane realisation of the spec's
        // "same correlation_id" requirement.
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository, times(4)).log(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
            .allSatisfy(e -> {
                assertThat(e.getOperation()).isEqualTo("tenant_close_cascade");
                assertThat(e.getRequestId()).isEqualTo("req_abc");
                assertThat(e.getTraceId()).isEqualTo("trace_xyz");
                assertThat(e.getMetadata()).containsEntry("cascade", "tenant_close");
            });

        // Cascade-event fan-out + the aggregate reservation_released event
        // for the one budget with reserved > 0 ⇒ 2 budget + 1 reservation
        // + 1 webhook + 1 api key = 5 total events, all under the same
        // correlation_id string.
        ArgumentCaptor<EventType> typeCaptor = ArgumentCaptor.forClass(EventType.class);
        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService, times(5)).emit(typeCaptor.capture(), eq("t1"), any(),
            eq("cycles-admin"), any(), any(), correlationCaptor.capture(), eq("req_abc"));
        assertThat(correlationCaptor.getAllValues())
            .allMatch(cid -> "tenant_close_cascade:t1:req_abc".equals(cid));
        assertThat(typeCaptor.getAllValues()).containsExactlyInAnyOrder(
            EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE,
            EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE,
            EventType.RESERVATION_RELEASED_VIA_TENANT_CASCADE,
            EventType.WEBHOOK_DISABLED_VIA_TENANT_CASCADE,
            EventType.API_KEY_REVOKED_VIA_TENANT_CASCADE);
    }

    @Test
    void cascade_onlyBudgetWithZeroReserved_skipsReservationReleaseEvent() {
        when(budgetRepository.cascadeClose("t1")).thenReturn(List.of(
            new BudgetRepository.CascadeCloseBudgetOutcome(
                "ldg_1", "tenant:t1", UnitEnum.USD_MICROCENTS,
                BudgetStatus.ACTIVE, 0L)));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(List.of());
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(List.of());

        service.cascade("t1", request);

        verify(eventService, never()).emit(eq(EventType.RESERVATION_RELEASED_VIA_TENANT_CASCADE),
            anyString(), any(), anyString(), any(), any(), anyString(), anyString());
        verify(eventService, times(1)).emit(eq(EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE),
            anyString(), any(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void cascade_missingRequestId_fallsBackToSentinelInCorrelationId() {
        MockHttpServletRequest bareReq = new MockHttpServletRequest();
        when(budgetRepository.cascadeClose("t1")).thenReturn(List.of(
            new BudgetRepository.CascadeCloseBudgetOutcome(
                "ldg_1", "tenant:t1", UnitEnum.USD_MICROCENTS,
                BudgetStatus.ACTIVE, 0L)));
        when(webhookRepository.cascadeDisable("t1")).thenReturn(List.of());
        when(apiKeyRepository.cascadeRevoke("t1", "tenant_closed")).thenReturn(List.of());

        service.cascade("t1", bareReq);

        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(any(), anyString(), any(), anyString(),
            any(), any(), correlation.capture(), any());
        assertThat(correlation.getValue()).isEqualTo("tenant_close_cascade:t1:no-req");
    }

    @Test
    void cascadeResult_emptyIsZeroed() {
        TenantCloseCascadeService.CascadeResult empty = TenantCloseCascadeService.CascadeResult.empty();
        assertThat(empty.budgetsClosed()).isZero();
        assertThat(empty.webhooksDisabled()).isZero();
        assertThat(empty.apiKeysRevoked()).isZero();
        assertThat(empty.reservationsReleased()).isZero();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }
}
