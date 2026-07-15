package io.runcycles.admin.data.repository.support;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;

import java.util.List;
import java.util.function.Function;

/** Shared strict cursor validation for materialized, deterministically ordered lists. */
public final class CursorSupport {
    private CursorSupport() {}

    public static int startAfterIds(List<String> orderedIds, String cursor) {
        return startAfter(orderedIds, cursor, Function.identity());
    }

    public static <T> int startAfter(List<T> orderedRows, String cursor,
                                     Function<T, String> idExtractor) {
        if (cursor == null || cursor.isBlank()) return 0;
        for (int i = 0; i < orderedRows.size(); i++) {
            if (cursor.equals(idExtractor.apply(orderedRows.get(i)))) return i + 1;
        }
        throw invalidCursor();
    }

    public static GovernanceException invalidCursor() {
        return new GovernanceException(ErrorCode.INVALID_REQUEST,
            "Cursor is not valid for this result set; restart pagination without a cursor",
            400);
    }
}
