package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookListResponse {
    @JsonProperty("subscriptions") private List<WebhookSubscription> subscriptions;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor") private String nextCursor;
    @JsonProperty("has_more") private boolean hasMore;
}
