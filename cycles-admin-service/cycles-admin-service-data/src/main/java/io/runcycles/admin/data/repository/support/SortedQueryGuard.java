package io.runcycles.admin.data.repository.support;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;

/** Explicit all-or-narrow guard for exact in-memory non-primary sorts. */
public final class SortedQueryGuard {
    public static final int MAX_CANDIDATES = 20_000;
    public static final int MAX_SCANNED_CANDIDATES = 50_000;

    private SortedQueryGuard() {}

    public static void requireBounded(long candidates, String surface) {
        if (candidates > MAX_CANDIDATES) {
            throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                "Exact " + surface + " sort has " + candidates
                    + " filtered matches; narrow the supplied filters to "
                    + MAX_CANDIDATES + " matches or fewer",
                400, java.util.Map.of("total_matched", candidates,
                    "max_sort_candidates", MAX_CANDIDATES));
        }
    }

    /** Bound work before hydration while still allowing filters to narrow the sort set. */
    public static void requireScannable(long candidates, String surface) {
        if (candidates > MAX_SCANNED_CANDIDATES) {
            throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                "Exact " + surface + " sort would scan " + candidates
                    + " source candidates; use indexed owner/time filters or reduce the data set to "
                    + MAX_SCANNED_CANDIDATES + " candidates or fewer",
                400, java.util.Map.of("total_candidates", candidates,
                    "max_scan_candidates", MAX_SCANNED_CANDIDATES));
        }
    }
}
