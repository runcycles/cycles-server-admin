package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookCreateResponse {
    @JsonProperty("subscription") private WebhookSubscription subscription;
    @JsonProperty("signing_secret") private String signingSecret;
}
