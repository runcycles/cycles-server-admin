package io.runcycles.admin.data.repository.support;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZSetAdaptivePagerTest {
    private final Jedis jedis = mock(Jedis.class);

    @Test
    void descendingCursor_resumesWithinEqualScoreBeforeLowerScores() {
        when(jedis.zscore("events", "c")).thenReturn(100.0);
        when(jedis.zrevrank("events", "c")).thenReturn(1L);
        when(jedis.zrevrangeByScore("events", 100.0, 100.0, 1, 1024))
            .thenReturn(List.of("c", "b", "a"));
        when(jedis.zrevrangeByScoreWithScores("events", Math.nextDown(100.0),
            Double.NEGATIVE_INFINITY, 0, 64))
            .thenReturn(List.of(new Tuple("lower", 99.0)));

        List<String> rows = ZSetAdaptivePager.collect(jedis, "events",
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, "c", 3, false, id -> id);

        assertThat(rows).containsExactly("b", "a", "lower");
    }

    @Test
    void ascendingCursor_resumesWithinEqualScoreInRedisOrder() {
        when(jedis.zscore("events", "b")).thenReturn(100.0);
        when(jedis.zrank("events", "b")).thenReturn(1L);
        when(jedis.zrangeByScore("events", 100.0, 100.0, 1, 1024))
            .thenReturn(List.of("b", "c", "d"));

        List<String> rows = ZSetAdaptivePager.collect(jedis, "events",
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, "b", 2, true, id -> id);

        assertThat(rows).containsExactly("c", "d");
    }

    @Test
    void sparseFilter_continuesBeyondInitialBatch() {
        List<String> firstBatch = new ArrayList<>();
        for (int i = 0; i < 64; i++) firstBatch.add("skip-" + i);
        String boundary = firstBatch.get(63);
        when(jedis.zrevrangeByScoreWithScores("events", Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, 0, 64)).thenReturn(firstBatch.stream()
                .map(id -> new Tuple(id, 100.0)).toList());
        when(jedis.zrevrank("events", boundary)).thenReturn(63L);
        when(jedis.zrevrangeByScore("events", 100.0, 100.0, 63, 1024))
            .thenReturn(List.of(boundary));
        when(jedis.zrevrangeByScoreWithScores("events", Math.nextDown(100.0),
            Double.NEGATIVE_INFINITY, 0, 64)).thenReturn(List.of(new Tuple("match", 99.0)));

        List<String> rows = ZSetAdaptivePager.collect(jedis, "events",
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, null, 1, false,
            id -> id.equals("match") ? id : null);

        assertThat(rows).containsExactly("match");
        verify(jedis).zrevrangeByScoreWithScores("events", Math.nextDown(100.0),
            Double.NEGATIVE_INFINITY, 0, 64);
    }

    @Test
    void missingCursor_isRejectedInsteadOfRestartingAtFirstPage() {
        when(jedis.zscore("events", "gone")).thenReturn(null);

        assertThatThrownBy(() -> ZSetAdaptivePager.collect(jedis, "events",
            0.0, 100.0, "gone", 10, false, id -> id))
            .isInstanceOf(GovernanceException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void equalScoreBucket_seeksDirectlyToBoundaryRank() {
        when(jedis.zscore("events", "m-boundary")).thenReturn(100.0);
        when(jedis.zrank("events", "m-boundary")).thenReturn(6_000L);
        when(jedis.zrangeByScore("events", 100.0, 100.0, 6_000, 1024))
            .thenReturn(List.of("m-boundary", "z-match"));

        List<String> rows = ZSetAdaptivePager.collect(jedis, "events",
            0.0, 200.0, "m-boundary", 1, true, id -> id);

        assertThat(rows).containsExactly("z-match");
        verify(jedis).zrangeByScore("events", 100.0, 100.0, 6_000, 1024);
    }

    @Test
    void sparseFilter_stopsAtExplicitScanBudget() {
        when(jedis.zrevrangeByScoreWithScores("events", 100.0, 0.0, 0, 64))
            .thenReturn(List.of(new Tuple("skip-1", 99.0),
                new Tuple("skip-2", 98.0), new Tuple("skip-3", 97.0)));

        assertThatThrownBy(() -> ZSetAdaptivePager.collect(jedis, "events",
                0.0, 100.0, null, 1, false, "audit log", 2, id -> null))
            .isInstanceOf(GovernanceException.class)
            .satisfies(error -> {
                GovernanceException governance = (GovernanceException) error;
                assertThat(governance.getErrorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                assertThat(governance.getDetails())
                    .containsEntry("max_scan_candidates", 2)
                    .containsEntry("scanned_candidates", 3);
            });
    }

    @Test
    void emptyAndInvalidRangesReturnWithoutReadingRedis() {
        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            null, 0, true, id -> id)).isEmpty();
        assertThat(ZSetAdaptivePager.collect(jedis, "events", 101, 100,
            null, 1, true, id -> id)).isEmpty();
    }

    @Test
    void blankCursorStartsAtFirstPageAndAscendingBoundaryContinues() {
        List<Tuple> first = new ArrayList<>();
        for (int i = 0; i < 64; i++) first.add(new Tuple("id-" + i, 100.0));
        when(jedis.zrangeByScoreWithScores("events", 0.0, 200.0, 0, 64)).thenReturn(first);
        when(jedis.zrank("events", "id-63")).thenReturn(63L);
        when(jedis.zrangeByScore("events", 100.0, 100.0, 63, 1024))
            .thenReturn(List.of("id-63", "z-match"));

        List<String> rows = ZSetAdaptivePager.collect(jedis, "events", 0, 200,
            " ", 1, true, id -> id.equals("z-match") ? id : null);

        assertThat(rows).containsExactly("z-match");
    }

    @Test
    void cursorOutsideRequestedScoreRangeIsRejected() {
        when(jedis.zscore("events", "below")).thenReturn(-1.0);
        when(jedis.zscore("events", "above")).thenReturn(101.0);

        assertThatThrownBy(() -> ZSetAdaptivePager.collect(jedis, "events",
            0, 100, "below", 1, true, id -> id)).isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> ZSetAdaptivePager.collect(jedis, "events",
            0, 100, "above", 1, true, id -> id)).isInstanceOf(GovernanceException.class);
    }

    @Test
    void cursorAtRangeEdgeCanExhaustWithoutAnotherScoreRead() {
        when(jedis.zscore("events", "last")).thenReturn(100.0);
        when(jedis.zrank("events", "last")).thenReturn(0L);
        when(jedis.zrangeByScore("events", 100.0, 100.0, 0, 1024))
            .thenReturn(List.of("last"));

        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            "last", 1, true, id -> id)).isEmpty();
    }

    @Test
    void emptyTieBucketAndFilteredTieRowsAreSkipped() {
        when(jedis.zscore("events", "cursor-empty")).thenReturn(50.0);
        when(jedis.zrank("events", "cursor-empty")).thenReturn(0L);
        when(jedis.zrangeByScore("events", 50.0, 50.0, 0, 1024)).thenReturn(List.of());
        when(jedis.zrangeByScoreWithScores("events", Math.nextUp(50.0), 100.0, 0, 64))
            .thenReturn(List.of(new Tuple("next", 51.0)));

        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            "cursor-empty", 1, true, id -> id)).containsExactly("next");

        when(jedis.zscore("events", "cursor-filtered")).thenReturn(60.0);
        when(jedis.zrank("events", "cursor-filtered")).thenReturn(0L);
        when(jedis.zrangeByScore("events", 60.0, 60.0, 0, 1024))
            .thenReturn(List.of("cursor-filtered", "filtered", "z-accepted"));
        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            "cursor-filtered", 1, true, id -> id.equals("z-accepted") ? id : null))
            .containsExactly("z-accepted");
    }

    @Test
    void descendingEqualScoreCursorBeyondBudgetSeeksWithoutChargingSkippedMembers() {
        when(jedis.zscore("events", "m-boundary")).thenReturn(100.0);
        when(jedis.zrevrank("events", "m-boundary")).thenReturn(6_500L);
        when(jedis.zrevrangeByScore("events", 100.0, 100.0, 6_500, 1024))
            .thenReturn(List.of("m-boundary", "a-match"));

        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 200,
            "m-boundary", 1, false, "event", 5_000, id -> id))
            .containsExactly("a-match");
    }

    @Test
    void fullPageGetsOneTruthfulLookaheadCandidateBeyondBaseBudget() {
        when(jedis.zrangeByScoreWithScores("events", 0.0, 100.0, 0, 64))
            .thenReturn(List.of(new Tuple("skip", 1.0),
                new Tuple("page-row", 2.0), new Tuple("lookahead", 3.0)));

        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            null, 2, true, "event", 2,
            id -> id.equals("skip") ? null : id))
            .containsExactly("page-row", "lookahead");
    }

    @Test
    void equalScoreOffsetBeyondRedisLimitFailsExplicitly() {
        when(jedis.zscore("events", "cursor")).thenReturn(100.0);
        when(jedis.zrank("events", "cursor"))
            .thenReturn((long) Integer.MAX_VALUE + 1L);

        assertThatThrownBy(() -> ZSetAdaptivePager.collect(jedis, "events",
            0, 200, "cursor", 1, true, id -> id))
            .isInstanceOf(GovernanceException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.LIMIT_EXCEEDED);
    }
}
