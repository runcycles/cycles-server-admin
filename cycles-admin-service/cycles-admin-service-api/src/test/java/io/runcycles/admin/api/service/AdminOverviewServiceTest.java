package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.AdminOverviewResponse;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.ErrorCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                        Tenant.builder().tenantId("tenant-1").status(TenantStatus.ACTIVE).build(),
                        Tenant.builder().tenantId("tenant-2").status(TenantStatus.SUSPENDED).build(),
                        Tenant.builder().tenantId("tenant-3").status(TenantStatus.CLOSED).build()
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
        Tenant tenant = Tenant.builder().tenantId("tenant-1").status(TenantStatus.ACTIVE).build();
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
        when(budgetRepository.list("tenant-1")).thenReturn(List.of(overLimit, normal));

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

    // v0.1.25.8: recent_denials_by_reason aggregation

    @Test
    void buildOverview_aggregatesRecentDenialsByReason() {
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(100))).thenReturn(List.of());

        // Three denials: two BUDGET_EXCEEDED, one ACTION_QUOTA_EXCEEDED (v0.1.26 extension value)
        Event denial1 = Event.builder().eventId("e1").eventType(EventType.RESERVATION_DENIED).category(EventCategory.RESERVATION).timestamp(Instant.now())
                .data(java.util.Map.of("reason_code", "BUDGET_EXCEEDED", "scope", "tenant:acme")).build();
        Event denial2 = Event.builder().eventId("e2").eventType(EventType.RESERVATION_DENIED).category(EventCategory.RESERVATION).timestamp(Instant.now())
                .data(java.util.Map.of("reason_code", "BUDGET_EXCEEDED", "scope", "tenant:acme")).build();
        Event denial3 = Event.builder().eventId("e3").eventType(EventType.RESERVATION_DENIED).category(EventCategory.RESERVATION).timestamp(Instant.now())
                .data(java.util.Map.of("reason_code", "ACTION_QUOTA_EXCEEDED", "scope", "tenant:acme")).build();
        when(eventRepository.list(isNull(), eq("reservation.denied"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10)))
                .thenReturn(List.of(denial1, denial2, denial3));
        when(eventRepository.list(isNull(), eq("reservation.expired"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());

        AdminOverviewResponse result = overviewService.buildOverview();

        assertThat(result.getRecentDenialsByReason()).isNotNull();
        assertThat(result.getRecentDenialsByReason()).containsEntry("BUDGET_EXCEEDED", 2);
        assertThat(result.getRecentDenialsByReason()).containsEntry("ACTION_QUOTA_EXCEEDED", 1);
    }

    @Test
    void buildOverview_noDenials_leavesRecentDenialsByReasonNull() {
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(100))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.denied"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());
        when(eventRepository.list(isNull(), eq("reservation.expired"), isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(List.of());

        AdminOverviewResponse result = overviewService.buildOverview();

        // Empty denials -> null (kept out of response via @JsonInclude(NON_NULL))
        assertThat(result.getRecentDenialsByReason()).isNull();
        // v0.1.26-only fields always null on v0.1.25.x
        assertThat(result.getQuotaHealth()).isNull();
        assertThat(result.getAccessControlStats()).isNull();
        assertThat(result.getTenantCounts().getInObserveMode()).isNull();
    }

    @Test
    void buildOverviewCoversPaginationNullSafeAggregatesAndReasonFiltering() {
        List<Tenant> tenants = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tenants.add(Tenant.builder().tenantId("tenant-" + i)
                .status(i == 0 ? null : TenantStatus.ACTIVE).build());
        }
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(tenants);
        when(tenantRepository.list(isNull(), isNull(), eq("tenant-99"), eq(100))).thenReturn(List.of());

        List<BudgetLedger> budgets = List.of(
            BudgetLedger.builder().ledgerId("nulls").unit(UnitEnum.TOKENS).status(null)
                .isOverLimit(true).build(),
            BudgetLedger.builder().ledgerId("frozen").unit(UnitEnum.TOKENS).status(BudgetStatus.FROZEN)
                .debt(new Amount(UnitEnum.TOKENS, 5L)).build(),
            BudgetLedger.builder().ledgerId("closed").unit(UnitEnum.TOKENS).status(BudgetStatus.CLOSED)
                .debt(new Amount(UnitEnum.TOKENS, 0L)).build());
        when(budgetRepository.list(anyString())).thenReturn(budgets);

        List<WebhookSubscription> webhooks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            webhooks.add(WebhookSubscription.builder().subscriptionId("wh-" + i)
                .status(i == 0 ? null : i == 1 ? WebhookStatus.DISABLED : WebhookStatus.ACTIVE)
                .consecutiveFailures(i == 0 ? null : i == 1 ? 0 : 2).build());
        }
        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(100))).thenReturn(webhooks);
        when(webhookRepository.listAll(isNull(), isNull(), eq("wh-99"), eq(100))).thenReturn(List.of());

        List<Event> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(Event.builder().eventId("evt-" + i)
                .category(i == 0 ? null : EventCategory.BUDGET).build());
        }
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(),
            any(Instant.class), any(Instant.class), isNull(), eq(100))).thenReturn(events);
        when(eventRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(),
            any(Instant.class), any(Instant.class), eq("evt-99"), eq(100))).thenReturn(List.of());
        List<Event> denials = List.of(
            Event.builder().data(null).build(),
            Event.builder().data(Map.of("reason_code", 42)).build(),
            Event.builder().data(Map.of("reason_code", " ")).build(),
            Event.builder().data(Map.of("reason_code", "QUOTA_EXCEEDED")).build());
        when(eventRepository.list(isNull(), eq("reservation.denied"), isNull(), isNull(), isNull(),
            any(Instant.class), any(Instant.class), isNull(), eq(10))).thenReturn(denials);

        AdminOverviewResponse response = overviewService.buildOverview();

        assertThat(response.getRecentDenialsByReason()).containsEntry("QUOTA_EXCEEDED", 1);
        assertThat(response.getOverLimitScopes().get(0).getAllocated()).isZero();
        assertThat(response.getDebtScopes().get(0).getOverdraftLimit()).isZero();
    }

    @Test
    void buildOverview_stopsWhenAnInternalRepositoryRepeatsTheSameFullPage() {
        List<Tenant> tenants = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tenants.add(Tenant.builder().tenantId("tenant-" + i)
                .status(TenantStatus.ACTIVE).build());
        }
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(tenants);
        when(tenantRepository.list(isNull(), isNull(), eq("tenant-99"), eq(100))).thenReturn(tenants);

        AdminOverviewResponse response = overviewService.buildOverview();

        assertThat(response.getTenantCounts().getTotal()).isEqualTo(100);
    }

    @Test
    void buildOverview_stopsWhenAFullPageHasNoUsableBoundaryId() {
        List<Tenant> tenants = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            tenants.add(Tenant.builder().tenantId("tenant-" + i)
                .status(TenantStatus.ACTIVE).build());
        }
        tenants.add(Tenant.builder().tenantId(null).status(TenantStatus.ACTIVE).build());
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(tenants);

        AdminOverviewResponse response = overviewService.buildOverview();

        assertThat(response.getTenantCounts().getTotal()).isEqualTo(100);
    }

    @Test
    void buildOverview_stopsWhenAFullPageHasABlankBoundaryId() {
        List<Tenant> tenants = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            tenants.add(Tenant.builder().tenantId("tenant-" + i)
                .status(TenantStatus.ACTIVE).build());
        }
        tenants.add(Tenant.builder().tenantId(" ").status(TenantStatus.ACTIVE).build());
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenReturn(tenants);

        AdminOverviewResponse response = overviewService.buildOverview();

        assertThat(response.getTenantCounts().getTotal()).isEqualTo(100);
    }

    @Test
    void buildOverview_restartsAnInternallyStaleCursor() {
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100)))
            .thenThrow(new GovernanceException(ErrorCode.INVALID_REQUEST, "stale", 400))
            .thenReturn(List.of());

        AdminOverviewResponse response = overviewService.buildOverview();

        assertThat(response.getTenantCounts().getTotal()).isZero();
    }

    @Test
    void buildOverview_returnsPartialSnapshotAfterRepeatedStaleCursors() {
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100)))
            .thenThrow(new GovernanceException(ErrorCode.INVALID_REQUEST, "stale", 400));

        AdminOverviewResponse response = overviewService.buildOverview();

        assertThat(response.getTenantCounts().getTotal()).isZero();
    }

    @Test
    void buildOverview_doesNotSwallowUnrelatedRepositoryErrors() {
        GovernanceException failure = GovernanceException.tenantNotFound("broken");
        when(tenantRepository.list(isNull(), isNull(), isNull(), eq(100))).thenThrow(failure);

        assertThatThrownBy(overviewService::buildOverview).isSameAs(failure);
    }
}
