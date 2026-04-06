package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class WebhookUpdateRequest {
    @Size(max = 256) @JsonProperty("name") private String name;
    @Size(max = 1024) @JsonProperty("description") private String description;
    @Size(max = 2048) @JsonProperty("url") private String url;
    @JsonProperty("event_types") private List<EventType> eventTypes;
    @JsonProperty("event_categories") private List<EventCategory> eventCategories;
    @JsonProperty("scope_filter") private String scopeFilter;
    @Valid @JsonProperty("thresholds") private WebhookThresholdConfig thresholds;
    @JsonProperty("signing_secret") private String signingSecret;
    @JsonProperty("headers") private Map<String, String> headers;
    @Valid @JsonProperty("retry_policy") private WebhookRetryPolicy retryPolicy;
    @JsonProperty("disable_after_failures") private Integer disableAfterFailures;
    @JsonProperty("status") private WebhookStatus status;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
