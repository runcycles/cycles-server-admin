package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
public class BudgetUpdateRequest {
    @Valid @JsonProperty("overdraft_limit") private Amount overdraftLimit;
    @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
