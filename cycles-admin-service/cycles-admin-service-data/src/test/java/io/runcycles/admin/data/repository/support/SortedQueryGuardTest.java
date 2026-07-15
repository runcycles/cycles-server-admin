package io.runcycles.admin.data.repository.support;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortedQueryGuardTest {

    @Test
    void atLimit_isAllowed() {
        assertThatCode(() -> SortedQueryGuard.requireBounded(
            SortedQueryGuard.MAX_CANDIDATES, "event")).doesNotThrowAnyException();
    }

    @Test
    void aboveLimit_failsWithExactNarrowingDetails() {
        long candidates = SortedQueryGuard.MAX_CANDIDATES + 1L;

        assertThatThrownBy(() -> SortedQueryGuard.requireBounded(candidates, "event"))
            .isInstanceOf(GovernanceException.class)
            .satisfies(error -> {
                GovernanceException governance = (GovernanceException) error;
                assertThat(governance.getErrorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                assertThat(governance.getHttpStatus()).isEqualTo(400);
                assertThat(governance.getDetails()).containsEntry("total_matched", candidates)
                    .containsEntry("max_sort_candidates", SortedQueryGuard.MAX_CANDIDATES);
            });
    }

    @Test
    void sourceScanHasIndependentHardCeiling() {
        assertThatCode(() -> SortedQueryGuard.requireScannable(
            SortedQueryGuard.MAX_SCANNED_CANDIDATES, "tenant"))
            .doesNotThrowAnyException();

        long candidates = SortedQueryGuard.MAX_SCANNED_CANDIDATES + 1L;
        assertThatThrownBy(() -> SortedQueryGuard.requireScannable(candidates, "tenant"))
            .isInstanceOf(GovernanceException.class)
            .satisfies(error -> {
                GovernanceException governance = (GovernanceException) error;
                assertThat(governance.getErrorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                assertThat(governance.getMessage())
                    .contains("post-hydration filters cannot reduce source scan cost")
                    .doesNotContain("indexed owner/time filters");
                assertThat(governance.getDetails())
                    .containsEntry("total_candidates", candidates)
                    .containsEntry("max_scan_candidates",
                        SortedQueryGuard.MAX_SCANNED_CANDIDATES);
            });
    }

    @Test
    void indexedSurfaceUsesCallerSpecificNarrowingGuidance() {
        long candidates = SortedQueryGuard.MAX_SCANNED_CANDIDATES + 1L;

        assertThatThrownBy(() -> SortedQueryGuard.requireScannable(
                candidates, "event", "narrow the indexed tenant/time window"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("narrow the indexed tenant/time window");
    }
}
