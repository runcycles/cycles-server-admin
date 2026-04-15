package io.runcycles.admin.model.budget;

/**
 * Funding operation kinds for {@link BudgetFundingRequest}, per spec
 * {@code cycles-governance-admin-v0.1.25.yaml} {@code BudgetFundingRequest.operation} enum.
 *
 * <ul>
 *   <li>{@link #CREDIT}, {@link #DEBIT}: adjust allocated + remaining together.
 *       Preserve spent/reserved/debt.</li>
 *   <li>{@link #RESET}: resize the allocated ceiling to an exact amount.
 *       Preserves spent/reserved/debt. NOT for billing-period boundaries.</li>
 *   <li>{@link #RESET_SPENT} (v0.1.25.17+): start a new billing period.
 *       Sets allocated to the amount and sets spent to the optional
 *       {@code spent} field (defaults to 0). Preserves reserved and debt.</li>
 *   <li>{@link #REPAY_DEBT}: reduce debt by amount (uses remaining if debt &lt; amount).</li>
 * </ul>
 */
public enum FundingOperation { CREDIT, DEBIT, RESET, REPAY_DEBT, RESET_SPENT }
