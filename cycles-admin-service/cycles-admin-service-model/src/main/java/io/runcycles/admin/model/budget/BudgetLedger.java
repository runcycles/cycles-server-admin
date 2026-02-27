package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.UnitEnum;
import lombok.*;
import java.time.Instant;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetLedger {
    @JsonProperty("ledger_id") private String ledgerId;
    @JsonProperty("scope") private String scope;
    @JsonProperty("scope_path") private String scopePath;
    @JsonProperty("unit") private UnitEnum unit;
    @JsonProperty("allocated") private Amount allocated;
    @JsonProperty("remaining") private Amount remaining;
    @JsonProperty("reserved") private Amount reserved;
    @JsonProperty("spent") private Amount spent;
    @JsonProperty("debt") private Amount debt;
    @JsonProperty("overdraft_limit") private Amount overdraftLimit;
    @JsonProperty("is_over_limit") private Boolean isOverLimit;
    @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @JsonProperty("status") private BudgetStatus status;
    @JsonProperty("rollover_policy") private RolloverPolicy rolloverPolicy;
    @JsonProperty("period_start") private Instant periodStart;
    @JsonProperty("period_end") private Instant periodEnd;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("updated_at") private Instant updatedAt;
}
