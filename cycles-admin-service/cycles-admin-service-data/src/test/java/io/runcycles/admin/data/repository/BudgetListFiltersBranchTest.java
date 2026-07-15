package io.runcycles.admin.data.repository;

import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.UnitEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetListFiltersBranchTest {

    @Test
    void nullableAmountsAndEachUtilizationBoundHaveDefinedSemantics() {
        BudgetLedger noAmounts = ledger("tenant-acme", "tenant:acme/workspace:eng", null, null, null);
        assertThat(new BudgetListFilters(null, null, null, null, false, null, null)
                .matches(noAmounts)).isTrue();
        assertThat(new BudgetListFilters(null, null, null, null, null, null, 0.0)
                .matches(noAmounts)).isTrue();

        BudgetLedger noSpent = ledger("tenant-acme", "tenant:acme/workspace:eng", 100L, null, 0L);
        assertThat(new BudgetListFilters(null, null, null, null, null, 0.0, 0.0)
                .matches(noSpent)).isTrue();
        assertThat(new BudgetListFilters(null, null, null, null, null, null, -0.1)
                .matches(noSpent)).isFalse();
    }

    @Test
    void searchChecksTenantThenScopeAndRejectsWhenNeitherMatches() {
        BudgetLedger ledger = ledger("tenant-acme", "tenant:acme/workspace:engineering", 100L, 50L, 0L);

        assertThat(new BudgetListFilters(null, null, null, null, null, null, null, "ACME")
                .matches(ledger)).isTrue();
        assertThat(new BudgetListFilters(null, null, null, null, null, null, null, "engineering")
                .matches(ledger)).isTrue();
        assertThat(new BudgetListFilters(null, null, null, null, null, null, null, "finance")
                .matches(ledger)).isFalse();
    }

    private static BudgetLedger ledger(String tenantId, String scope, Long allocated, Long spent, Long debt) {
        return BudgetLedger.builder()
                .ledgerId("ledger-1")
                .tenantId(tenantId)
                .scope(scope)
                .unit(UnitEnum.TOKENS)
                .allocated(allocated == null ? null : new Amount(UnitEnum.TOKENS, allocated))
                .spent(spent == null ? null : new Amount(UnitEnum.TOKENS, spent))
                .debt(debt == null ? null : new Amount(UnitEnum.TOKENS, debt))
                .isOverLimit(false)
                .status(BudgetStatus.ACTIVE)
                .build();
    }
}
