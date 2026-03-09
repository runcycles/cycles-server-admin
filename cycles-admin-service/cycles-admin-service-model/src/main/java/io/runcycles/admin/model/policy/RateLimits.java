package io.runcycles.admin.model.policy;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RateLimits {
    @JsonProperty("max_reservations_per_minute") private Integer maxReservationsPerMinute;
    @JsonProperty("max_commits_per_minute") private Integer maxCommitsPerMinute;
}
