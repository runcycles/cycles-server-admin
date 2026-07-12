package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import lombok.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
public class WebhookSubscription {

    /** Owning-tenant sentinel for admin-provisioned, non-tenant-owned rows. */
    public static final String SYSTEM_TENANT = "__system__";

    /**
     * Single source of truth for "is this subscription system-owned (not
     * tenant-owned)". Per governance v0.1.25.40 the tenant-accessible boundary
     * exempts ONLY a null/omitted owner and the literal {@link #SYSTEM_TENANT}
     * sentinel. A blank (whitespace-only) {@code tenant_id} is NOT system - it
     * is treated as a concrete owner so it stays boundary-validated (closes the
     * blank-owner exemption bypass). Used by both the write-path validator (api)
     * and the cleanup reconciler (data) so they cannot disagree.
     */
    public static boolean isSystemOwner(String tenantId) {
        return tenantId == null || SYSTEM_TENANT.equals(tenantId);
    }

    @JsonProperty("subscription_id") private String subscriptionId;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("name") private String name;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("description") private String description;
    @JsonProperty("url") private String url;
    @JsonProperty("event_types") private List<EventType> eventTypes;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("event_categories") private List<EventCategory> eventCategories;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("scope_filter") private String scopeFilter;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("thresholds") private WebhookThresholdConfig thresholds;
    @JsonIgnore private String signingSecret;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("headers") private Map<String, String> headers;
    @JsonProperty("status") private WebhookStatus status;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("retry_policy") private WebhookRetryPolicy retryPolicy;
    @JsonProperty("disable_after_failures") @Builder.Default private Integer disableAfterFailures = 10;
    @JsonProperty("consecutive_failures") private Integer consecutiveFailures;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("updated_at") private Instant updatedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("last_triggered_at") private Instant lastTriggeredAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("last_success_at") private Instant lastSuccessAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("last_failure_at") private Instant lastFailureAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("metadata") private Map<String, Object> metadata;
}
