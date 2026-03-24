package io.runcycles.admin.model.policy;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.*;
import java.time.Instant;
@Data @NoArgsConstructor @AllArgsConstructor
public class PolicyUpdateRequest {
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @JsonProperty("priority") private Integer priority;
    @Valid @JsonProperty("caps") private Caps caps;
    @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @Valid @JsonProperty("reservation_ttl_override") private ReservationTtlOverride reservationTtlOverride;
    @Valid @JsonProperty("rate_limits") private RateLimits rateLimits;
    @JsonProperty("effective_from") private Instant effectiveFrom;
    @JsonProperty("effective_until") private Instant effectiveUntil;
    @JsonProperty("status") private PolicyStatus status;
}
