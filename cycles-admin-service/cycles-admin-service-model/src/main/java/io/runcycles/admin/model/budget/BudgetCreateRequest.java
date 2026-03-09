package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.time.Instant;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
public class BudgetCreateRequest {
    @JsonProperty("tenant_id") private String tenantId;
    @NotBlank @JsonProperty("scope") private String scope;
    @NotNull @JsonProperty("unit") private UnitEnum unit;
    @NotNull @Valid @JsonProperty("allocated") private Amount allocated;
    @Valid @JsonProperty("overdraft_limit") private Amount overdraftLimit;
    @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @JsonProperty("rollover_policy") private RolloverPolicy rolloverPolicy;
    @JsonProperty("period_start") private Instant periodStart;
    @JsonProperty("period_end") private Instant periodEnd;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
