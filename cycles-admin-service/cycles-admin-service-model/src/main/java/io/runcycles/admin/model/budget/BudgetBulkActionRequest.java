package io.runcycles.admin.model.budget;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.Amount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
/**
 * Request envelope for POST /v1/admin/budgets/bulk-action (spec v0.1.25.26).
 * Operation-discriminated: the {@code action} enum names which
 * balance-mutation to apply uniformly across the matched set. The action
 * determines which payload fields are required — see per-action notes on
 * each property.
 *
 * <p>Server MUST validate the action/payload combination BEFORE running
 * the filter or counting matches. An invalid combination (e.g. CREDIT
 * without {@code amount}, RESET_SPENT with negative {@code spent}) MUST
 * return HTTP 400 with no writes.
 *
 * <p>Safety semantics mirror {@code TenantBulkActionRequest}: 500-row
 * hard cap (LIMIT_EXCEEDED → 400), {@code expectedCount} mismatch →
 * COUNT_MISMATCH → 409, 15-minute {@code idempotencyKey} replay window,
 * overall HTTP 200 even with per-row failures.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class BudgetBulkActionRequest {
    @NotNull @Valid @JsonProperty("filter") private BudgetBulkFilter filter;
    @NotNull @JsonProperty("action") private FundingOperation action;
    @Valid @JsonProperty("amount") private Amount amount;
    @Valid @JsonProperty("spent") private Amount spent;
    @Size(max = 512) @JsonProperty("reason") private String reason;
    @Min(0) @JsonProperty("expected_count") private Integer expectedCount;
    @NotBlank @Size(min = 1, max = 128) @JsonProperty("idempotency_key") private String idempotencyKey;
}
