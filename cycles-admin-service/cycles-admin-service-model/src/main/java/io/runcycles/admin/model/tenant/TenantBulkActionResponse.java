package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import lombok.*;
import java.util.List;
/**
 * Response envelope for POST /v1/admin/tenants/bulk-action (spec v0.1.25.21).
 * Splits the affected tenants into succeeded / failed / skipped with
 * per-row detail, echoes the action and idempotency_key so callers can
 * confirm they observed the intended effect.
 *
 * <p>{@code totalMatched} is the server-counted match — the invariant
 * {@code succeeded.size() + failed.size() + skipped.size() ==
 * totalMatched} holds. Overall HTTP status is 200 even when some rows
 * land in failed[].
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TenantBulkActionResponse {
    @JsonProperty("action") private TenantBulkAction action;
    @JsonProperty("total_matched") private int totalMatched;
    @JsonProperty("succeeded") private List<BulkActionRowOutcome> succeeded;
    @JsonProperty("failed") private List<BulkActionRowOutcome> failed;
    @JsonProperty("skipped") private List<BulkActionRowOutcome> skipped;
    @JsonProperty("idempotency_key") private String idempotencyKey;
}
