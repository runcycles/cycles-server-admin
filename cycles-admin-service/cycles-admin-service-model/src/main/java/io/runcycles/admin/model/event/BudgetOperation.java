package io.runcycles.admin.model.event;

/**
 * Operation classifier for {@code EventDataBudgetLifecycle.operation}, per spec
 * {@code cycles-governance-admin-v0.1.25.yaml} EventDataBudgetLifecycle.operation enum.
 */
public enum BudgetOperation {
    CREDIT,
    DEBIT,
    RESET,
    REPAY_DEBT,
    STATUS_CHANGE,
    CREATE,
    UPDATE
}
