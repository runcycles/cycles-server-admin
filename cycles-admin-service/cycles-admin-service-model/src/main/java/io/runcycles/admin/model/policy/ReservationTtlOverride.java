package io.runcycles.admin.model.policy;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReservationTtlOverride {
    @Min(1000) @Max(86400000) @JsonProperty("default_ttl_ms") private Integer defaultTtlMs;
    @Min(1000) @Max(86400000) @JsonProperty("max_ttl_ms") private Integer maxTtlMs;
    @Min(0) @JsonProperty("max_extensions") private Integer maxExtensions;
}
