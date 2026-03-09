package io.runcycles.admin.model.policy;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReservationTtlOverride {
    @JsonProperty("default_ttl_ms") private Integer defaultTtlMs;
    @JsonProperty("max_ttl_ms") private Integer maxTtlMs;
    @JsonProperty("max_extensions") private Integer maxExtensions;
}
