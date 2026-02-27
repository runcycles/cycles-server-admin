package io.runcycles.admin.model.tenant;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class Tenant {
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("name") private String name;
    @JsonProperty("status") private TenantStatus status;
    @JsonProperty("parent_tenant_id") private String parentTenantId;
    @JsonProperty("default_commit_overage_policy") private CommitOveragePolicy defaultCommitOveragePolicy;
    @JsonProperty("default_reservation_ttl_ms") private Long defaultReservationTtlMs;
    @JsonProperty("max_reservation_ttl_ms") private Long maxReservationTtlMs;
    @JsonProperty("max_reservation_extensions") private Integer maxReservationExtensions;
    @JsonProperty("reservation_expiry_policy") private ReservationExpiryPolicy reservationExpiryPolicy;
    @JsonProperty("metadata") private Map<String, String> metadata;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("updated_at") private Instant updatedAt;
    @JsonProperty("suspended_at") private Instant suspendedAt;
    @JsonProperty("closed_at") private Instant closedAt;
}
