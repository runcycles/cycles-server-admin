package io.runcycles.admin.model.policy;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.Caps;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import lombok.*;
import java.time.Instant;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Policy {
    // Required per spec: policy_id, scope_pattern, status, created_at
    @JsonProperty("policy_id") private String policyId;
    @JsonIgnore private String tenantId;
    @JsonProperty("scope_pattern") private String scopePattern;
    @JsonProperty("status") private PolicyStatus status;
    @JsonProperty("created_at") private Instant createdAt;
    // Optional fields (omit when null)
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("name") private String name;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("description") private String description;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("priority") private Integer priority;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("caps") private Caps caps;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reservation_ttl_override") private ReservationTtlOverride reservationTtlOverride;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("rate_limits") private RateLimits rateLimits;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("effective_from") private Instant effectiveFrom;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("effective_until") private Instant effectiveUntil;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("updated_at") private Instant updatedAt;
}
