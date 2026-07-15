package io.runcycles.admin.data.repository.support;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;

/** Explicit all-or-narrow guard for exact in-memory non-primary sorts. */
public final class SortedQueryGuard {
    public static final int MAX_CANDIDATES = 20_000;

    private SortedQueryGuard() {}

    public static void requireBounded(long candidates, String surface) {
        if (candidates > MAX_CANDIDATES) {
            throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                "Exact " + surface + " sort spans " + candidates
                    + " candidates; narrow the tenant or indexed time window to "
                    + MAX_CANDIDATES + " candidates or fewer",
                400, java.util.Map.of("total_matched", candidates,
                    "max_sort_candidates", MAX_CANDIDATES));
        }
    }
}
