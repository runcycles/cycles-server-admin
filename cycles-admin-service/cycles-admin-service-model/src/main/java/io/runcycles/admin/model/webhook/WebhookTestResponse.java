package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookTestResponse {
    @JsonProperty("success") private boolean success;
    @JsonProperty("response_status") private Integer responseStatus;
    @JsonProperty("response_time_ms") private Integer responseTimeMs;
    @JsonProperty("error_message") private String errorMessage;
    @JsonProperty("event_id") private String eventId;
}
