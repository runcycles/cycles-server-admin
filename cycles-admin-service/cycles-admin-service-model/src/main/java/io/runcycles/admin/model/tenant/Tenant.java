package io.runcycles.admin.model.tenant;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Tenant {
    // Required per spec: tenant_id, name, status, created_at (always serialized)
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("name") private String name;
    @JsonProperty("status") private TenantStatus status;
    @JsonProperty("created_at") private Instant createdAt;
    // Optional fields (omit when null)
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("parent_tenant_id") private String parentTenantId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("default_commit_overage_policy") private CommitOveragePolicy defaultCommitOveragePolicy;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("default_reservation_ttl_ms") private Long defaultReservationTtlMs;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("max_reservation_ttl_ms") private Long maxReservationTtlMs;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("max_reservation_extensions") private Integer maxReservationExtensions;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reservation_expiry_policy") private ReservationExpiryPolicy reservationExpiryPolicy;
    @jakarta.validation.constraints.Size(max = 32) @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("metadata") private Map<String, String> metadata;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("updated_at") private Instant updatedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("suspended_at") private Instant suspendedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("closed_at") private Instant closedAt;
}
