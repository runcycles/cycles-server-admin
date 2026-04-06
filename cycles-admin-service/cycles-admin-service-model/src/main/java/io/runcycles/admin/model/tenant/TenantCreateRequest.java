package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class TenantCreateRequest {
    @NotBlank @Pattern(regexp = "^[a-z0-9-]+$") @Size(min = 3, max = 64)
    @JsonProperty("tenant_id") private String tenantId;
    @NotBlank @Size(max = 256)
    @JsonProperty("name") private String name;
    @JsonProperty("parent_tenant_id") private String parentTenantId;
    @Size(max = 32) @JsonProperty("metadata") private Map<String, String> metadata;
    @JsonProperty("default_commit_overage_policy") private CommitOveragePolicy defaultCommitOveragePolicy;
    @Min(1000) @Max(86400000) @JsonProperty("default_reservation_ttl_ms") private Long defaultReservationTtlMs;
    @Min(1000) @Max(86400000) @JsonProperty("max_reservation_ttl_ms") private Long maxReservationTtlMs;
    @Min(0) @JsonProperty("max_reservation_extensions") private Integer maxReservationExtensions;
    @JsonProperty("reservation_expiry_policy") private ReservationExpiryPolicy reservationExpiryPolicy;
}
