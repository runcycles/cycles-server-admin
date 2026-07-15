package io.runcycles.admin.data.repository.support;

import java.util.ArrayList;
import java.util.List;

/** Complete per-row outcome for an idempotent tenant-close cascade step. */
public record CascadeMutationResult<T>(List<T> succeeded, List<RowFailure> failed) {
    public CascadeMutationResult {
        succeeded = List.copyOf(succeeded);
        failed = List.copyOf(failed);
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public boolean complete() {
        return failed.isEmpty();
    }

    public record RowFailure(String resourceId, String exceptionType) {}

    public static final class Builder<T> {
        private final List<T> succeeded = new ArrayList<>();
        private final List<RowFailure> failed = new ArrayList<>();

        public void succeeded(T outcome) {
            succeeded.add(outcome);
        }

        public void failed(String resourceId, Exception error) {
            failed.add(new RowFailure(resourceId, error.getClass().getSimpleName()));
        }

        public CascadeMutationResult<T> build() {
            return new CascadeMutationResult<>(succeeded, failed);
        }
    }
}
