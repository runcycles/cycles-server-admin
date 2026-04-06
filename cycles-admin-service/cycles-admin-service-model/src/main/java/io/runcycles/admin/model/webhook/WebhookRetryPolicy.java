package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class WebhookRetryPolicy {
    @Min(0) @Max(10) @JsonProperty("max_retries") @Builder.Default private Integer maxRetries = 5;
    @Min(100) @Max(60000) @JsonProperty("initial_delay_ms") @Builder.Default private Integer initialDelayMs = 1000;
    @DecimalMin("1.0") @DecimalMax("10.0") @JsonProperty("backoff_multiplier") @Builder.Default private Double backoffMultiplier = 2.0;
    @Min(1000) @Max(3600000) @JsonProperty("max_delay_ms") @Builder.Default private Integer maxDelayMs = 60000;
}
