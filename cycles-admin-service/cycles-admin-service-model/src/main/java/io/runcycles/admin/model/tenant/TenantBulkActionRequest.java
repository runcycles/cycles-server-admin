package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
/**
 * Request envelope for POST /v1/admin/tenants/bulk-action (spec v0.1.25.21).
 * Names the filter selecting target tenants, the lifecycle action to
 * apply, and safety metadata that protects against over-apply
 * ({@code expectedCount}) and retry duplication ({@code idempotencyKey}).
 *
 * <p>{@code expectedCount} mismatch → HTTP 409 COUNT_MISMATCH, no writes.
 * {@code idempotencyKey} replay window is 15 minutes — repeat submits
 * return the original response without re-applying the action.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class TenantBulkActionRequest {
    @NotNull @Valid @JsonProperty("filter") private TenantBulkFilter filter;
    @NotNull @JsonProperty("action") private TenantBulkAction action;
    @Min(0) @JsonProperty("expected_count") private Integer expectedCount;
    @NotBlank @Size(min = 1, max = 128) @JsonProperty("idempotency_key") private String idempotencyKey;
}
