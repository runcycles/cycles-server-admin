package io.runcycles.admin.model.policy;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.Caps;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import lombok.*;
import java.time.Instant;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class Policy {
    @JsonProperty("policy_id") private String policyId;
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @JsonProperty("scope_pattern") private String scopePattern;
    @JsonProperty("priority") private Integer priority;
    @JsonProperty("caps") private Caps caps;
    @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @JsonProperty("reservation_ttl_override") private ReservationTtlOverride reservationTtlOverride;
    @JsonProperty("rate_limits") private RateLimits rateLimits;
    @JsonProperty("status") private PolicyStatus status;
    @JsonProperty("effective_from") private Instant effectiveFrom;
    @JsonProperty("effective_until") private Instant effectiveUntil;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("updated_at") private Instant updatedAt;
}
