package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.AdminOverviewResponse;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.UnitEnum;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOverviewServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private WebhookRepository webhookRepository;
    @Mock private EventRepository eventRepository;
    @InjectMocks private AdminOverviewService overviewService;

    @Test
    void buildOverview_aggregatesTenantCounts() {
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(
                        Tenant.builder().tenantId("t1").status(TenantStatus.ACTIVE).build(),
                        Tenant.builder().tenantId("t2").status(TenantStatus.SUSPENDED).build(),
                        Tenant.builder().tenantId("t3").status(TenantStatus.CLOSED).build()
                ));
        when(budgetRepository.list(anyString())).thenReturn(List.of());
        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.denied"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.expired"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());

        AdminOverviewResponse result = overviewService.buildOverview();

        assertThat(result.getTenantCounts().getTotal()).isEqualTo(3);
        assertThat(result.getTenantCounts().getActive()).isEqualTo(1);
        assertThat(result.getTenantCounts().getSuspended()).isEqualTo(1);
        assertThat(result.getTenantCounts().getClosed()).isEqualTo(1);
        assertThat(result.getEventWindowSeconds()).isEqualTo(3600);
    }

    @Test
    void buildOverview_aggregatesBudgetCountsAndTopOffenders() {
        Tenant tenant = Tenant.builder().tenantId("t1").status(TenantStatus.ACTIVE).build();
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of(tenant));

        BudgetLedger overLimit = BudgetLedger.builder()
                .ledgerId("b1").scope("tenant:acme/agent:bot").unit(UnitEnum.USD_MICROCENTS)
                .status(BudgetStatus.ACTIVE).isOverLimit(true)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 10000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, -500L))
                .debt(new Amount(UnitEnum.USD_MICROCENTS, 1500L))
                .overdraftLimit(new Amount(UnitEnum.USD_MICROCENTS, 5000L))
                .build();
        BudgetLedger normal = BudgetLedger.builder()
                .ledgerId("b2").scope("tenant:acme/agent:chat").unit(UnitEnum.TOKENS)
                .status(BudgetStatus.ACTIVE).isOverLimit(false)
                .allocated(new Amount(UnitEnum.TOKENS, 5000L))
                .remaining(new Amount(UnitEnum.TOKENS, 3000L))
                .debt(new Amount(UnitEnum.TOKENS, 0L))
                .build();
        when(budgetRepository.list("t1")).thenReturn(List.of(overLimit, normal));

        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.denied"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.expired"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());

        AdminOverviewResponse result = overviewService.buildOverview();

        assertThat(result.getBudgetCounts().getTotal()).isEqualTo(2);
        assertThat(result.getBudgetCounts().getActive()).isEqualTo(2);
        assertThat(result.getBudgetCounts().getOverLimit()).isEqualTo(1);
        assertThat(result.getBudgetCounts().getWithDebt()).isEqualTo(1);
        assertThat(result.getBudgetCounts().getByUnit()).containsEntry("USD_MICROCENTS", 1);
        assertThat(result.getBudgetCounts().getByUnit()).containsEntry("TOKENS", 1);
        assertThat(result.getOverLimitScopes()).hasSize(1);
        assertThat(result.getOverLimitScopes().get(0).getScope()).isEqualTo("tenant:acme/agent:bot");
        assertThat(result.getDebtScopes()).hasSize(1);
        assertThat(result.getDebtScopes().get(0).getDebt()).isEqualTo(1500);
    }

    @Test
    void buildOverview_aggregatesWebhooksAndFailures() {
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(
                        WebhookSubscription.builder().subscriptionId("wh1").url("https://a.com/hook").status(WebhookStatus.ACTIVE).consecutiveFailures(5).lastFailureAt(Instant.now()).build(),
                        WebhookSubscription.builder().subscriptionId("wh2").url("https://b.com/hook").status(WebhookStatus.DISABLED).consecutiveFailures(0).build()
                ));
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.denied"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.expired"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());

        AdminOverviewResponse result = overviewService.buildOverview();

        assertThat(result.getWebhookCounts().getTotal()).isEqualTo(2);
        assertThat(result.getWebhookCounts().getActive()).isEqualTo(1);
        assertThat(result.getWebhookCounts().getDisabled()).isEqualTo(1);
        assertThat(result.getWebhookCounts().getWithFailures()).isEqualTo(1);
        assertThat(result.getFailingWebhooks()).hasSize(1);
        assertThat(result.getFailingWebhooks().get(0).getConsecutiveFailures()).isEqualTo(5);
    }

    @Test
    void buildOverview_countsEventsByCategory() {
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());

        List<Event> windowEvents = List.of(
                Event.builder().eventId("e1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).timestamp(Instant.now()).build(),
                Event.builder().eventId("e2").eventType(EventType.BUDGET_FUNDED).category(EventCategory.BUDGET).timestamp(Instant.now()).build(),
                Event.builder().eventId("e3").eventType(EventType.RESERVATION_DENIED).category(EventCategory.RESERVATION).timestamp(Instant.now()).build()
        );
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(100))).thenReturn(windowEvents);

        Event denial = Event.builder().eventId("e3").eventType(EventType.RESERVATION_DENIED).category(EventCategory.RESERVATION).timestamp(Instant.now()).build();
        when(eventRepository.list(isNull(), eq("reservation.denied"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of(denial));
        when(eventRepository.list(isNull(), eq("reservation.expired"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());

        AdminOverviewResponse result = overviewService.buildOverview();

        assertThat(result.getEventCounts().getTotalRecent()).isEqualTo(3);
        assertThat(result.getEventCounts().getByCategory()).containsEntry("budget", 2);
        assertThat(result.getEventCounts().getByCategory()).containsEntry("reservation", 1);
        assertThat(result.getRecentDenials()).hasSize(1);
        assertThat(result.getRecentExpiries()).isEmpty();
    }
}
