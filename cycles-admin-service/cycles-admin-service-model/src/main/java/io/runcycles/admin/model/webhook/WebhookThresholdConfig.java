package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class WebhookThresholdConfig {
    @JsonProperty("budget_utilization") private List<@DecimalMin("0.0") @DecimalMax("1.0") Double> budgetUtilization;
    @DecimalMin("1.5") @JsonProperty("burn_rate_multiplier") @Builder.Default private Double burnRateMultiplier = 3.0;
    @Min(60) @Max(86400) @JsonProperty("burn_rate_window_seconds") @Builder.Default private Integer burnRateWindowSeconds = 300;
    @DecimalMin("0.0") @DecimalMax("1.0") @JsonProperty("denial_rate_threshold") @Builder.Default private Double denialRateThreshold = 0.10;
    @DecimalMin("0.0") @DecimalMax("1.0") @JsonProperty("expiry_rate_threshold") @Builder.Default private Double expiryRateThreshold = 0.05;
    @DecimalMin("0.0") @DecimalMax("1.0") @JsonProperty("auth_failure_rate_threshold") @Builder.Default private Double authFailureRateThreshold = 0.10;
    @Min(60) @Max(86400) @JsonProperty("rate_window_seconds") @Builder.Default private Integer rateWindowSeconds = 300;
}
