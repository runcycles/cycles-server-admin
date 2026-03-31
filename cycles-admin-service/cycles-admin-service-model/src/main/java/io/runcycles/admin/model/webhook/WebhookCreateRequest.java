package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookCreateRequest {
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @NotNull @JsonProperty("url") private String url;
    @NotEmpty @JsonProperty("event_types") private List<EventType> eventTypes;
    @JsonProperty("event_categories") private List<EventCategory> eventCategories;
    @JsonProperty("scope_filter") private String scopeFilter;
    @JsonProperty("thresholds") private WebhookThresholdConfig thresholds;
    @JsonProperty("signing_secret") private String signingSecret;
    @JsonProperty("headers") private Map<String, String> headers;
    @JsonProperty("retry_policy") private WebhookRetryPolicy retryPolicy;
    @JsonProperty("disable_after_failures") private Integer disableAfterFailures;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
