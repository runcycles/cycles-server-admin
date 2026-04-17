package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
/**
 * Request envelope for POST /v1/admin/webhooks/bulk-action (spec v0.1.25.21).
 * Mirrors TenantBulkActionRequest in shape and safety semantics; targets
 * webhook subscriptions instead of tenants.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class WebhookBulkActionRequest {
    @NotNull @Valid @JsonProperty("filter") private WebhookBulkFilter filter;
    @NotNull @JsonProperty("action") private WebhookBulkAction action;
    @Min(0) @JsonProperty("expected_count") private Integer expectedCount;
    @NotBlank @Size(min = 1, max = 128) @JsonProperty("idempotency_key") private String idempotencyKey;
}
