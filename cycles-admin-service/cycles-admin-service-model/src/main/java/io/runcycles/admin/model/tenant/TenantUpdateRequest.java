package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
public class TenantUpdateRequest {
    @JsonProperty("name") private String name;
    @JsonProperty("status") private TenantStatus status;
    @Size(max = 32) @JsonProperty("metadata") private Map<String, String> metadata;
    @JsonProperty("default_commit_overage_policy") private CommitOveragePolicy defaultCommitOveragePolicy;
}
