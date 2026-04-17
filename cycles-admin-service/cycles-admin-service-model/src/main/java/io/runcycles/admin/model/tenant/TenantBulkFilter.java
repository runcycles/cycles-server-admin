package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.Size;
import lombok.*;
/**
 * Filter selecting which tenants a bulk-action applies to (spec v0.1.25.21).
 * Shape mirrors the query params of listTenants so operators can preview
 * the match set via GET /v1/admin/tenants and then submit the same filter
 * to bulk-action.
 *
 * <p>At least one property MUST be present (empty filter → 400). AND
 * combination across properties. {@code search} uses ILIKE substring
 * semantics identical to the list endpoint's {@code search} param.
 *
 * <p>{@code additionalProperties: false} is enforced by
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} — unknown keys
 * surface as a 400 parse failure.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class TenantBulkFilter {
    @JsonProperty("status") private TenantStatus status;
    @JsonProperty("parent_tenant_id") private String parentTenantId;
    @JsonProperty("observe_mode") private String observeMode;
    @Size(max = 128) @JsonProperty("search") private String search;

    /**
     * Returns true when every property is null/blank. The controller
     * rejects such a request with 400 per spec (empty filter is an
     * accidental-all-tenants footgun).
     */
    @JsonIgnore
    public boolean isEmpty() {
        return status == null
            && (parentTenantId == null || parentTenantId.isBlank())
            && (observeMode == null || observeMode.isBlank())
            && (search == null || search.isBlank());
    }
}
