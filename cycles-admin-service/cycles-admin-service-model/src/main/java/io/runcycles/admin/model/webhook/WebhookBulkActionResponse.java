package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import lombok.*;
import java.util.List;
/**
 * Response envelope for POST /v1/admin/webhooks/bulk-action (spec v0.1.25.21).
 * Mirrors TenantBulkActionResponse; action enum is PAUSE|RESUME|DELETE.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookBulkActionResponse {
    @JsonProperty("action") private WebhookBulkAction action;
    @JsonProperty("total_matched") private int totalMatched;
    @JsonProperty("succeeded") private List<BulkActionRowOutcome> succeeded;
    @JsonProperty("failed") private List<BulkActionRowOutcome> failed;
    @JsonProperty("skipped") private List<BulkActionRowOutcome> skipped;
    @JsonProperty("idempotency_key") private String idempotencyKey;
}
