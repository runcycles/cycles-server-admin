package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.UnitEnum;
import lombok.*;
import java.time.Instant;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BudgetLedger {
    // Required per spec: ledger_id, scope, unit, allocated, remaining, status, created_at
    @JsonProperty("ledger_id") private String ledgerId;
    @JsonIgnore private String tenantId;
    @JsonProperty("scope") private String scope;
    @JsonProperty("unit") private UnitEnum unit;
    @JsonProperty("allocated") private Amount allocated;
    @JsonProperty("remaining") private Amount remaining;
    @JsonProperty("status") private BudgetStatus status;
    @JsonProperty("created_at") private Instant createdAt;
    // Optional fields (omit when null)
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("scope_path") private String scopePath;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reserved") private Amount reserved;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("spent") private Amount spent;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("debt") private Amount debt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("overdraft_limit") private Amount overdraftLimit;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("is_over_limit") private Boolean isOverLimit;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("rollover_policy") private RolloverPolicy rolloverPolicy;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("period_start") private Instant periodStart;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("period_end") private Instant periodEnd;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("updated_at") private Instant updatedAt;
    @JsonIgnore private Map<String, Object> metadata;
}
