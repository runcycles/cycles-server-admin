package io.runcycles.admin.api.service;

import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers every key the v0.1.25.30 bulk-action metadata builder emits
 * and pins the ordering contract so downstream dashboards that rely on
 * JSON field order do not silently regress.
 */
class BulkActionAuditMetadataBuilderTest {

    @Test
    void build_emitsAllKeysInDocumentedOrder() {
        List<BulkActionRowOutcome> succeeded = List.of(row("t_ok_1"), row("t_ok_2"));
        List<BulkActionRowOutcome> failed = List.of(
                BulkActionRowOutcome.builder().id("t_fail").errorCode("BUDGET_EXCEEDED")
                        .message("remaining would go negative").build());
        List<BulkActionRowOutcome> skipped = List.of(
                BulkActionRowOutcome.builder().id("t_skip").reason("ALREADY_IN_TARGET_STATE").build());
        Object filter = Map.of("tenant_id", "acme", "scope_prefix", "tenant:acme/workspace:eng");
        long startNanos = System.nanoTime() - 5_000_000L; // ~5 ms ago

        Map<String, Object> meta = BulkActionAuditMetadataBuilder.build(
                "CREDIT", 4, succeeded, failed, skipped, "rollover-2026-04", filter, startNanos);

        assertThat(meta.keySet()).containsExactly(
                "action", "total_matched", "succeeded", "failed", "skipped",
                "succeeded_ids", "failed_rows", "skipped_rows",
                "filter", "idempotency_key", "duration_ms");
        assertThat(meta.get("action")).isEqualTo("CREDIT");
        assertThat(meta.get("total_matched")).isEqualTo(4);
        assertThat(meta.get("succeeded")).isEqualTo(2);
        assertThat(meta.get("failed")).isEqualTo(1);
        assertThat(meta.get("skipped")).isEqualTo(1);
        assertThat(meta.get("succeeded_ids")).isEqualTo(List.of("t_ok_1", "t_ok_2"));
        assertThat(meta.get("failed_rows")).isEqualTo(failed);
        assertThat(meta.get("skipped_rows")).isEqualTo(skipped);
        assertThat(meta.get("filter")).isEqualTo(filter);
        assertThat(meta.get("idempotency_key")).isEqualTo("rollover-2026-04");
        assertThat((Long) meta.get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void build_allEmptyBuckets_stillEmitsArrays() {
        Map<String, Object> meta = BulkActionAuditMetadataBuilder.build(
                "DELETE", 0, List.of(), List.of(), List.of(),
                "k1", Map.of("tenant_id", "acme"), System.nanoTime());

        assertThat(meta.get("succeeded")).isEqualTo(0);
        assertThat(meta.get("failed")).isEqualTo(0);
        assertThat(meta.get("skipped")).isEqualTo(0);
        assertThat((List<?>) meta.get("succeeded_ids")).isEmpty();
        assertThat((List<?>) meta.get("failed_rows")).isEmpty();
        assertThat((List<?>) meta.get("skipped_rows")).isEmpty();
    }

    @Test
    void build_nullFilter_isPassedThroughUnchanged() {
        // A controller that rejects a missing filter at validation time never reaches the
        // builder, but defense-in-depth: null is allowed and echoed, not substituted.
        Map<String, Object> meta = BulkActionAuditMetadataBuilder.build(
                "RESET", 0, List.of(), List.of(), List.of(), "k1", null, System.nanoTime());

        assertThat(meta).containsKey("filter");
        assertThat(meta.get("filter")).isNull();
    }

    @Test
    void build_durationMs_isMonotonicAndNonNegative() {
        long start = System.nanoTime();
        Map<String, Object> meta = BulkActionAuditMetadataBuilder.build(
                "PAUSE", 0, List.of(), List.of(), List.of(), "k1",
                Map.of("tenant_id", "acme"), start);
        assertThat((Long) meta.get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void build_succeededIdsOrder_mirrorsInputOrder() {
        List<BulkActionRowOutcome> succeeded = new ArrayList<>();
        succeeded.add(row("id-3"));
        succeeded.add(row("id-1"));
        succeeded.add(row("id-2"));

        Map<String, Object> meta = BulkActionAuditMetadataBuilder.build(
                "RESUME", 3, succeeded, List.of(), List.of(), "k1",
                Map.of("tenant_id", "acme"), System.nanoTime());

        assertThat(meta.get("succeeded_ids")).isEqualTo(List.of("id-3", "id-1", "id-2"));
    }

    private static BulkActionRowOutcome row(String id) {
        return BulkActionRowOutcome.builder().id(id).build();
    }
}
