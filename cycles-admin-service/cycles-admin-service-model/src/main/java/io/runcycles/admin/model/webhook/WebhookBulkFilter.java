package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.EventType;
import jakarta.validation.constraints.Size;
import lombok.*;
/**
 * Filter selecting which webhook subscriptions a bulk-action applies to
 * (spec v0.1.25.21). Mirrors listWebhookSubscriptions query params so
 * operators can preview the match set with GET /v1/admin/webhooks and
 * submit the same filter to bulk-action.
 *
 * <p>At least one property MUST be present; empty filter → 400.
 * {@code search} uses ILIKE substring match on {@code subscription_id}
 * or {@code url} — identical semantics to the list endpoint.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class WebhookBulkFilter {
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("status") private WebhookStatus status;
    @JsonProperty("event_type") private EventType eventType;
    @Size(max = 128) @JsonProperty("search") private String search;

    /** Empty filter rejected with 400 per spec. */
    @JsonIgnore
    public boolean isEmpty() {
        return (tenantId == null || tenantId.isBlank())
            && status == null
            && eventType == null
            && (search == null || search.isBlank());
    }
}
