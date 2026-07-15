package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.shared.AdminOverviewResponse;
import io.runcycles.admin.model.shared.AdminOverviewResponse.*;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class AdminOverviewService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminOverviewService.class);

    private static final int PAGE_SIZE = 100;
    private static final int TOP_OFFENDER_CAP = 10;
    private static final int EVENT_WINDOW_SECONDS = 3600;

    private final TenantRepository tenantRepository;
    private final BudgetRepository budgetRepository;
    private final WebhookRepository webhookRepository;
    private final EventRepository eventRepository;

    public AdminOverviewService(TenantRepository tenantRepository, BudgetRepository budgetRepository,
                                WebhookRepository webhookRepository, EventRepository eventRepository) {
        this.tenantRepository = tenantRepository;
        this.budgetRepository = budgetRepository;
        this.webhookRepository = webhookRepository;
        this.eventRepository = eventRepository;
    }

    public AdminOverviewResponse buildOverview() {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(EVENT_WINDOW_SECONDS);

        // Tenants
        List<Tenant> allTenants = listAllTenants();
        TenantCounts tenantCounts = countTenants(allTenants);

        // Budgets (per-tenant iteration required — BudgetRepository uses per-tenant SET index)
        List<BudgetLedger> allBudgets = listAllBudgets(allTenants);
        BudgetCounts budgetCounts = countBudgets(allBudgets);
        List<OverLimitScope> overLimitScopes = collectOverLimitScopes(allBudgets);
        List<DebtScope> debtScopes = collectDebtScopes(allBudgets);

        // Webhooks (global index: webhooks:_all)
        List<WebhookSubscription> allWebhooks = listAllWebhooks();
        WebhookCounts webhookCounts = countWebhooks(allWebhooks);
        List<FailingWebhook> failingWebhooks = collectFailingWebhooks(allWebhooks);

        // Events in window
        List<Event> windowEvents = listEventsInWindow(windowStart, now);
        EventCounts eventCounts = countEvents(windowEvents);

        // Recent denials and expiries (separate queries, capped at 10)
        List<Event> recentDenials = eventRepository.list(null, "reservation.denied", null, null, null, windowStart, now, null, TOP_OFFENDER_CAP);
        List<Event> recentExpiries = eventRepository.list(null, "reservation.expired", null, null, null, windowStart, now, null, TOP_OFFENDER_CAP);

        // v0.1.25.8: denial count breakdown by reason_code (over the displayed sample)
        Map<String, Integer> denialsByReason = countDenialsByReason(recentDenials);

        return AdminOverviewResponse.builder()
                .asOf(now)
                .eventWindowSeconds(EVENT_WINDOW_SECONDS)
                .tenantCounts(tenantCounts)
                .budgetCounts(budgetCounts)
                .overLimitScopes(overLimitScopes)
                .debtScopes(debtScopes)
                .webhookCounts(webhookCounts)
                .failingWebhooks(failingWebhooks)
                .eventCounts(eventCounts)
                .recentDenials(recentDenials)
                .recentExpiries(recentExpiries)
                .recentDenialsByReason(denialsByReason.isEmpty() ? null : denialsByReason)
                // v0.1.25.8 fields that require v0.1.26 extensions — left null in v0.1.25.x:
                //   tenantCounts.inObserveMode, quotaHealth, accessControlStats
                .build();
    }

    /**
     * Count denials by reason_code from the recent denials sample.
     * Returns an empty map if no denials have reason_code populated.
     */
    private Map<String, Integer> countDenialsByReason(List<Event> denials) {
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (Event e : denials) {
            Map<String, Object> data = e.getData();
            if (data == null) continue;
            Object reasonObj = data.get("reason_code");
            if (reasonObj instanceof String reason && !reason.isBlank()) {
                byReason.merge(reason, 1, Integer::sum);
            }
        }
        return byReason;
    }

    private List<Tenant> listAllTenants() {
        return collectAllPages(
            cursor -> tenantRepository.list(null, null, cursor, PAGE_SIZE),
            Tenant::getTenantId, "tenant");
    }

    private List<BudgetLedger> listAllBudgets(List<Tenant> tenants) {
        List<BudgetLedger> all = new ArrayList<>();
        for (Tenant tenant : tenants) {
            all.addAll(budgetRepository.list(tenant.getTenantId()));
        }
        return all;
    }

    private List<WebhookSubscription> listAllWebhooks() {
        return collectAllPages(
            cursor -> webhookRepository.listAll(null, null, cursor, PAGE_SIZE),
            WebhookSubscription::getSubscriptionId, "webhook");
    }

    private List<Event> listEventsInWindow(Instant from, Instant to) {
        return collectAllPages(
            cursor -> eventRepository.list(
                null, null, null, null, null, from, to, cursor, PAGE_SIZE),
            Event::getEventId, "event");
    }

    /**
     * Internal overview walks tolerate a concurrently expired boundary by
     * restarting from the head and de-duplicating rows. External list APIs
     * still return a clear 400 for a stale cursor, but a caller that supplied
     * no cursor never receives that internal race as its own bad request.
     */
    private <T> List<T> collectAllPages(Function<String, List<T>> fetchPage,
                                        Function<T, String> idExtractor,
                                        String surface) {
        LinkedHashMap<String, T> rows = new LinkedHashMap<>();
        String cursor = null;
        int restarts = 0;
        while (true) {
            List<T> page;
            try {
                page = fetchPage.apply(cursor);
            } catch (GovernanceException e) {
                if (e.getErrorCode() != ErrorCode.INVALID_REQUEST || restarts++ >= 3) {
                    if (e.getErrorCode() == ErrorCode.INVALID_REQUEST) {
                        LOG.warn("Overview {} pagination remained unstable after cursor restarts; returning a de-duplicated partial snapshot",
                            surface);
                        break;
                    }
                    throw e;
                }
                cursor = null;
                continue;
            }
            page.forEach(row -> rows.put(idExtractor.apply(row), row));
            if (page.size() < PAGE_SIZE) break;
            String nextCursor = idExtractor.apply(page.get(page.size() - 1));
            if (nextCursor == null || nextCursor.isBlank() || nextCursor.equals(cursor)) {
                LOG.warn("Overview {} pagination made no forward progress; returning a de-duplicated partial snapshot",
                    surface);
                break;
            }
            cursor = nextCursor;
        }
        return new ArrayList<>(rows.values());
    }

    private TenantCounts countTenants(List<Tenant> tenants) {
        int active = 0, suspended = 0, closed = 0;
        for (Tenant t : tenants) {
            if (t.getStatus() == TenantStatus.ACTIVE) active++;
            else if (t.getStatus() == TenantStatus.SUSPENDED) suspended++;
            else if (t.getStatus() == TenantStatus.CLOSED) closed++;
        }
        return TenantCounts.builder().total(tenants.size()).active(active).suspended(suspended).closed(closed).build();
    }

    private BudgetCounts countBudgets(List<BudgetLedger> budgets) {
        int active = 0, frozen = 0, closed = 0, overLimit = 0, withDebt = 0;
        Map<String, Integer> byUnit = new HashMap<>();
        for (BudgetLedger b : budgets) {
            if (b.getStatus() == BudgetStatus.ACTIVE) active++;
            else if (b.getStatus() == BudgetStatus.FROZEN) frozen++;
            else if (b.getStatus() == BudgetStatus.CLOSED) closed++;
            if (Boolean.TRUE.equals(b.getIsOverLimit())) overLimit++;
            if (b.getDebt() != null && b.getDebt().getAmount() > 0) withDebt++;
            byUnit.merge(b.getUnit().name(), 1, Integer::sum);
        }
        return BudgetCounts.builder().total(budgets.size()).active(active).frozen(frozen).closed(closed)
                .overLimit(overLimit).withDebt(withDebt).byUnit(byUnit).build();
    }

    private List<OverLimitScope> collectOverLimitScopes(List<BudgetLedger> budgets) {
        List<OverLimitScope> result = new ArrayList<>();
        for (BudgetLedger b : budgets) {
            if (Boolean.TRUE.equals(b.getIsOverLimit()) && result.size() < TOP_OFFENDER_CAP) {
                result.add(OverLimitScope.builder()
                        .scope(b.getScope()).unit(b.getUnit())
                        .allocated(b.getAllocated() != null ? b.getAllocated().getAmount() : 0)
                        .remaining(b.getRemaining() != null ? b.getRemaining().getAmount() : 0)
                        .debt(b.getDebt() != null ? b.getDebt().getAmount() : 0)
                        .build());
            }
        }
        return result;
    }

    private List<DebtScope> collectDebtScopes(List<BudgetLedger> budgets) {
        List<DebtScope> result = new ArrayList<>();
        for (BudgetLedger b : budgets) {
            if (b.getDebt() != null && b.getDebt().getAmount() > 0 && result.size() < TOP_OFFENDER_CAP) {
                result.add(DebtScope.builder()
                        .scope(b.getScope()).unit(b.getUnit())
                        .debt(b.getDebt().getAmount())
                        .overdraftLimit(b.getOverdraftLimit() != null ? b.getOverdraftLimit().getAmount() : 0)
                        .build());
            }
        }
        return result;
    }

    private WebhookCounts countWebhooks(List<WebhookSubscription> webhooks) {
        int active = 0, disabled = 0, withFailures = 0;
        for (WebhookSubscription w : webhooks) {
            if (w.getStatus() == WebhookStatus.ACTIVE) active++;
            else if (w.getStatus() == WebhookStatus.DISABLED) disabled++;
            if (w.getConsecutiveFailures() != null && w.getConsecutiveFailures() > 0) withFailures++;
        }
        return WebhookCounts.builder().total(webhooks.size()).active(active).disabled(disabled).withFailures(withFailures).build();
    }

    private List<FailingWebhook> collectFailingWebhooks(List<WebhookSubscription> webhooks) {
        List<FailingWebhook> result = new ArrayList<>();
        for (WebhookSubscription w : webhooks) {
            if (w.getConsecutiveFailures() != null && w.getConsecutiveFailures() > 0 && result.size() < TOP_OFFENDER_CAP) {
                result.add(FailingWebhook.builder()
                        .subscriptionId(w.getSubscriptionId()).url(w.getUrl())
                        .consecutiveFailures(w.getConsecutiveFailures())
                        .lastFailureAt(w.getLastFailureAt())
                        .build());
            }
        }
        return result;
    }

    private EventCounts countEvents(List<Event> events) {
        Map<String, Integer> byCategory = new HashMap<>();
        for (Event e : events) {
            if (e.getCategory() != null) {
                byCategory.merge(e.getCategory().getValue(), 1, Integer::sum);
            }
        }
        return EventCounts.builder().totalRecent(events.size()).byCategory(byCategory).build();
    }
}
