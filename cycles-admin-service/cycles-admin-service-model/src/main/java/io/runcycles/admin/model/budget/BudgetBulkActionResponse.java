package io.runcycles.admin.model.budget;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import lombok.*;
import java.util.List;
/**
 * Response envelope for POST /v1/admin/budgets/bulk-action (spec v0.1.25.26).
 * Splits the affected ledgers into succeeded / failed / skipped with
 * per-row detail, echoes the action and idempotency_key so callers can
 * confirm they observed the intended effect. Mirrors
 * {@code TenantBulkActionResponse} and {@code WebhookBulkActionResponse}.
 *
 * <p>{@code totalMatched} is the server-counted match — the invariant
 * {@code succeeded.size() + failed.size() + skipped.size() ==
 * totalMatched} holds. Overall HTTP status is 200 even when some rows
 * land in failed[]. Common per-row failure codes: BUDGET_EXCEEDED (DEBIT
 * would take remaining negative), INVALID_TRANSITION (unit mismatch,
 * ledger FROZEN/CLOSED).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BudgetBulkActionResponse {
    @JsonProperty("action") private FundingOperation action;
    @JsonProperty("total_matched") private int totalMatched;
    @JsonProperty("succeeded") private List<BulkActionRowOutcome> succeeded;
    @JsonProperty("failed") private List<BulkActionRowOutcome> failed;
    @JsonProperty("skipped") private List<BulkActionRowOutcome> skipped;
    @JsonProperty("idempotency_key") private String idempotencyKey;
}
