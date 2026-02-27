package io.runcycles.admin.model.policy;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.shared.Caps;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import jakarta.validation.constraints.*;
import lombok.*;
import java.time.Instant;
@Data @NoArgsConstructor @AllArgsConstructor
public class PolicyCreateRequest {
    @NotBlank @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @NotBlank @JsonProperty("scope_pattern") private String scopePattern;
    @JsonProperty("priority") private Integer priority;
    @JsonProperty("caps") private Caps caps;
    @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @JsonProperty("effective_from") private Instant effectiveFrom;
    @JsonProperty("effective_until") private Instant effectiveUntil;
}
