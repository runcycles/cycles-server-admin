package io.runcycles.admin.api.support;

import java.util.List;

/** Derives truthful pagination metadata from a {@code limit + 1} read. */
public record PageSlice<T>(List<T> items, boolean hasMore) {
    public static <T> PageSlice<T> from(List<T> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        int end = Math.min(rows.size(), limit);
        return new PageSlice<>(List.copyOf(rows.subList(0, end)), hasMore);
    }
}
