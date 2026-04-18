package io.runcycles.admin.api.service;

import io.runcycles.admin.model.shared.BulkActionRowOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code metadata} map embedded in the single {@code AuditLogEntry}
 * emitted per bulk-action invocation (v0.1.25.30 enrichment).
 *
 * <p>Prior to v0.1.25.30 the map carried bucket counts only, which meant
 * post-incident triage of a bulk-op required either (a) the operator's
 * own copy of the response envelope or (b) re-running the op. Neither
 * is acceptable for compliance review. This builder closes that gap by
 * embedding the full per-row outcomes plus enough surrounding context
 * (filter echo, duration) to reconstruct intent.
 *
 * <p><b>Emitted keys</b> (stable — downstream dashboards rely on them):
 * <table>
 *   <tr><th>key</th><th>type</th><th>purpose</th></tr>
 *   <tr><td>{@code action}</td><td>String</td><td>echo of the action enum</td></tr>
 *   <tr><td>{@code total_matched}</td><td>int</td><td>rows the filter matched</td></tr>
 *   <tr><td>{@code succeeded}</td><td>int</td><td>count — kept for backward compat</td></tr>
 *   <tr><td>{@code failed}</td><td>int</td><td>count — kept for backward compat</td></tr>
 *   <tr><td>{@code skipped}</td><td>int</td><td>count — kept for backward compat</td></tr>
 *   <tr><td>{@code succeeded_ids}</td><td>List&lt;String&gt;</td>
 *       <td>per-row ids of successful operations — paper trail</td></tr>
 *   <tr><td>{@code failed_rows}</td><td>List&lt;BulkActionRowOutcome&gt;</td>
 *       <td>full id + error_code + message per failure</td></tr>
 *   <tr><td>{@code skipped_rows}</td><td>List&lt;BulkActionRowOutcome&gt;</td>
 *       <td>full id + reason per skip</td></tr>
 *   <tr><td>{@code filter}</td><td>Object</td>
 *       <td>normalized filter — reconstructs operator intent</td></tr>
 *   <tr><td>{@code idempotency_key}</td><td>String</td>
 *       <td>correlates envelope ↔ retries ↔ audit</td></tr>
 *   <tr><td>{@code duration_ms}</td><td>long</td>
 *       <td>handler-entry → audit-emit wall-clock — SLO triage</td></tr>
 * </table>
 *
 * <p><b>Bounding.</b> The 500-row bulk-action cap bounds worst-case metadata
 * size to ~40 KB, well within Redis value-size comfort range.
 *
 * <p><b>Ordering.</b> Returns a {@link LinkedHashMap} so JSON serialization
 * preserves the table order above — aids human scannability in log viewers.
 */
public final class BulkActionAuditMetadataBuilder {

    private BulkActionAuditMetadataBuilder() {}

    /**
     * @param startTimeNanos value of {@link System#nanoTime()} captured
     *                       at handler entry. Used to compute
     *                       {@code duration_ms} at emit time.
     */
    public static Map<String, Object> build(
            String actionName,
            int totalMatched,
            List<BulkActionRowOutcome> succeeded,
            List<BulkActionRowOutcome> failed,
            List<BulkActionRowOutcome> skipped,
            String idempotencyKey,
            Object filter,
            long startTimeNanos) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("action", actionName);
        meta.put("total_matched", totalMatched);
        meta.put("succeeded", succeeded.size());
        meta.put("failed", failed.size());
        meta.put("skipped", skipped.size());
        meta.put("succeeded_ids", succeeded.stream()
                .map(BulkActionRowOutcome::getId)
                .toList());
        meta.put("failed_rows", failed);
        meta.put("skipped_rows", skipped);
        meta.put("filter", filter);
        meta.put("idempotency_key", idempotencyKey);
        meta.put("duration_ms", (System.nanoTime() - startTimeNanos) / 1_000_000L);
        return meta;
    }
}
