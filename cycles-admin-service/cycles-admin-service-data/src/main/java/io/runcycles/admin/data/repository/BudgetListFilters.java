package io.runcycles.admin.data.repository;

import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.UnitEnum;

/**
 * Composable filter set for listBudgets, introduced in governance spec
 * v0.1.25.18 and extended in v0.1.25.21 with {@code search}. All fields
 * are optional; null = no filter on that dimension. Filter semantics per
 * spec:
 *
 *   - AND combination across fields.
 *   - Applied before cursor traversal so pagination is stable.
 *   - Budgets with allocated == 0 are treated as utilization == 0
 *     for the utilization_min / utilization_max bounds.
 *   - Search matches {@code tenant_id} or {@code scope} as a case-
 *     insensitive substring (OR within the search filter, AND with
 *     other filter params).
 *
 * The utilization_min > utilization_max cross-parameter constraint
 * is validated at the controller boundary so the 400 is symmetrical
 * under ApiKeyAuth and AdminKeyAuth callers.
 */
public record BudgetListFilters(
        String scopePrefix,
        UnitEnum unit,
        BudgetStatus status,
        Boolean overLimit,
        Boolean hasDebt,
        Double utilizationMin,
        Double utilizationMax,
        String search) {

    public static BudgetListFilters empty() {
        return new BudgetListFilters(null, null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor without {@code search} — preserved
     * so existing call sites and tests compile without mass rewrites.
     */
    public BudgetListFilters(String scopePrefix, UnitEnum unit, BudgetStatus status,
                              Boolean overLimit, Boolean hasDebt,
                              Double utilizationMin, Double utilizationMax) {
        this(scopePrefix, unit, status, overLimit, hasDebt, utilizationMin, utilizationMax, null);
    }

    public boolean matches(BudgetLedger ledger) {
        if (scopePrefix != null && !ledger.getScope().startsWith(scopePrefix)) return false;
        if (unit != null && ledger.getUnit() != unit) return false;
        if (status != null && ledger.getStatus() != status) return false;
        if (overLimit != null) {
            boolean actual = Boolean.TRUE.equals(ledger.getIsOverLimit());
            if (actual != overLimit.booleanValue()) return false;
        }
        if (hasDebt != null) {
            long debtAmount = ledger.getDebt() != null ? ledger.getDebt().getAmount() : 0L;
            boolean actual = debtAmount > 0L;
            if (actual != hasDebt.booleanValue()) return false;
        }
        if (utilizationMin != null || utilizationMax != null) {
            double util = computeUtilization(ledger);
            if (utilizationMin != null && util < utilizationMin) return false;
            if (utilizationMax != null && util > utilizationMax) return false;
        }
        if (search != null) {
            if (!SearchSpec.matches(ledger.getTenantId(), search)
                    && !SearchSpec.matches(ledger.getScope(), search)) {
                return false;
            }
        }
        return true;
    }

    private static double computeUtilization(BudgetLedger ledger) {
        if (ledger.getAllocated() == null) return 0.0;
        long allocated = ledger.getAllocated().getAmount();
        if (allocated == 0L) return 0.0;
        long spent = ledger.getSpent() != null ? ledger.getSpent().getAmount() : 0L;
        return (double) spent / (double) allocated;
    }
}
