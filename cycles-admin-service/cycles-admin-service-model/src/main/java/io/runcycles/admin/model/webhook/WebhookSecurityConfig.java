package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookSecurityConfig {

    @JsonAnySetter
    public void rejectUnknownProperty(String key, Object value) {
        throw new IllegalArgumentException("Unknown property: " + key);
    }
    @JsonProperty("blocked_cidr_ranges") @Builder.Default
    private List<String> blockedCidrRanges = List.of(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "127.0.0.0/8", "169.254.0.0/16", "::1/128", "fc00::/7"
    );
    @JsonProperty("allowed_url_patterns") private List<String> allowedUrlPatterns;
    @JsonProperty("allow_http") @Builder.Default private Boolean allowHttp = false;
}
