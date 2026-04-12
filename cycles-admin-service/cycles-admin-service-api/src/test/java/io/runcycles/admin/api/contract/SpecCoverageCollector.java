package io.runcycles.admin.api.contract;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-singleton set recording {@code "METHOD /path/template"} pairs for every
 * spec-path MockMvc request validated during a test run. Populated by
 * {@link ContractValidationConfig}'s result matcher, consumed by
 * {@code ZContractCoverageReportTest} after all other tests complete.
 *
 * <p>Stores templates (e.g. {@code GET /v1/admin/tenants/{tenant_id}}),
 * not concrete URIs (e.g. {@code GET /v1/admin/tenants/tenant-1}) so the
 * set size tops out at 43 (one per spec operation) regardless of how many
 * fixtures tests use.
 *
 * <p>Surefire runs tests in a single JVM by default; a static collection
 * is sufficient. If the build ever switches to {@code <forkCount>} greater
 * than 1, this needs to be file-backed instead.
 */
public final class SpecCoverageCollector {

    private static final Set<String> COVERED = ConcurrentHashMap.newKeySet();

    private SpecCoverageCollector() {}

    /** Record that a request hit the given method + spec path template. */
    public static void record(String method, String pathTemplate) {
        COVERED.add(method.toUpperCase() + " " + pathTemplate);
    }

    /** Snapshot of all covered {@code "METHOD /path/template"} keys. */
    public static Set<String> covered() {
        return Set.copyOf(COVERED);
    }
}
