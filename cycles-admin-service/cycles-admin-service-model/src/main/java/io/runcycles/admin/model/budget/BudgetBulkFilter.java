package io.runcycles.admin.model.budget;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.UnitEnum;
import jakarta.validation.constraints.*;
import lombok.*;
/**
 * Filter selecting which budget ledgers a bulk-action applies to (spec
 * v0.1.25.26). Shape mirrors the query params of {@code listBudgets} so
 * operators can preview the match set via {@code GET /v1/admin/budgets}
 * and then submit the same filter to {@code POST /v1/admin/budgets/bulk-action}.
 *
 * <p>Cross-tenant safety: {@code tenant_id} is REQUIRED (unlike listBudgets
 * where it is optional under AdminKeyAuth). Every bulk-action MUST target
 * exactly one tenant — prevents accidental all-tenant fan-out and keeps
 * the audit-log entry cleanly attributable.
 *
 * <p>Cascading reset across a scope subtree uses {@code scope_prefix} (e.g.
 * {@code tenant:acme/workspace:eng} matches all descendants of that
 * scope). The hierarchy is encoded in the scope path; no separate
 * {@code cascade} flag is needed.
 *
 * <p>{@code additionalProperties: false} is enforced by
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} — unknown keys
 * surface as a 400 parse failure.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class BudgetBulkFilter {
    @NotBlank @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("scope_prefix") private String scopePrefix;
    @JsonProperty("unit") private UnitEnum unit;
    @JsonProperty("status") private BudgetStatus status;
    @JsonProperty("over_limit") private Boolean overLimit;
    @JsonProperty("has_debt") private Boolean hasDebt;
    @DecimalMin("0.0") @DecimalMax("1.0") @JsonProperty("utilization_min") private Double utilizationMin;
    @DecimalMin("0.0") @DecimalMax("1.0") @JsonProperty("utilization_max") private Double utilizationMax;
    @Size(max = 128) @JsonProperty("search") private String search;
}
