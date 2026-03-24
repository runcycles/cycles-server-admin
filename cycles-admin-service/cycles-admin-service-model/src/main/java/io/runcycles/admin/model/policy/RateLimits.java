package io.runcycles.admin.model.policy;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.*;
// v0: rate limit configuration is stored but runtime enforcement is deferred.
// Protocol spec marks HTTP 429 rate limiting as optional in v0.
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RateLimits {
    @Min(1) @JsonProperty("max_reservations_per_minute") private Integer maxReservationsPerMinute;
    @Min(1) @JsonProperty("max_commits_per_minute") private Integer maxCommitsPerMinute;
}
