package io.runcycles.admin.model.policy;
import io.runcycles.admin.model.shared.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.shared.Caps;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.time.Instant;
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
public class PolicyCreateRequest {
    // Spec v0.1.25.13: tenant_id is REQUIRED when calling with AdminKeyAuth,
    // MUST NOT be set when calling with ApiKeyAuth. Controller validates.
    @JsonProperty("tenant_id") private String tenantId;
    @NotBlank @Size(max = 256) @JsonProperty("name") private String name;
    @Size(max = 1024) @JsonProperty("description") private String description;
    @NotBlank @JsonProperty("scope_pattern") private String scopePattern;
    @Min(0) @JsonProperty("priority") private Integer priority;
    @Valid @JsonProperty("caps") private Caps caps;
    @JsonProperty("commit_overage_policy") private CommitOveragePolicy commitOveragePolicy;
    @Valid @JsonProperty("reservation_ttl_override") private ReservationTtlOverride reservationTtlOverride;
    @Valid @JsonProperty("rate_limits") private RateLimits rateLimits;
    @JsonProperty("effective_from") private Instant effectiveFrom;
    @JsonProperty("effective_until") private Instant effectiveUntil;
}
