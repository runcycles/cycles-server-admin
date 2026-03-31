package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookDeliveryListResponse {
    @JsonProperty("deliveries") private List<WebhookDelivery> deliveries;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor") private String nextCursor;
    @JsonProperty("has_more") private boolean hasMore;
}
