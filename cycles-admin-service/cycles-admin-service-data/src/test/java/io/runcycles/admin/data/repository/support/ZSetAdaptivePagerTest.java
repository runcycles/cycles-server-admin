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
        when(jedis.zrevrangeByScore("events", 100.0, 100.0, 0, 1024))
            .thenReturn(List.of("d", "c", "b", "a"));
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
        when(jedis.zrangeByScore("events", 100.0, 100.0, 0, 1024))
            .thenReturn(List.of("a", "b", "c", "d"));

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
        when(jedis.zrevrangeByScore("events", 100.0, 100.0, 0, 1024))
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
    void equalScoreBucket_isTraversedInBoundedChunks() {
        when(jedis.zscore("events", "m-boundary")).thenReturn(100.0);
        List<String> firstChunk = new ArrayList<>();
        for (int i = 0; i < 1023; i++) firstChunk.add("a-" + i);
        firstChunk.add("m-boundary");
        when(jedis.zrangeByScore("events", 100.0, 100.0, 0, 1024))
            .thenReturn(firstChunk);
        when(jedis.zrangeByScore("events", 100.0, 100.0, 1024, 1024))
            .thenReturn(List.of("z-match"));

        List<String> rows = ZSetAdaptivePager.collect(jedis, "events",
            0.0, 200.0, "m-boundary", 1, true, id -> id);

        assertThat(rows).containsExactly("z-match");
        verify(jedis).zrangeByScore("events", 100.0, 100.0, 0, 1024);
        verify(jedis).zrangeByScore("events", 100.0, 100.0, 1024, 1024);
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
        when(jedis.zrangeByScore("events", 100.0, 100.0, 0, 1024))
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
        when(jedis.zrangeByScore("events", 100.0, 100.0, 0, 1024))
            .thenReturn(List.of("last"));

        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            "last", 1, true, id -> id)).isEmpty();
    }

    @Test
    void emptyTieBucketAndFilteredTieRowsAreSkipped() {
        when(jedis.zscore("events", "cursor-empty")).thenReturn(50.0);
        when(jedis.zrangeByScore("events", 50.0, 50.0, 0, 1024)).thenReturn(List.of());
        when(jedis.zrangeByScoreWithScores("events", Math.nextUp(50.0), 100.0, 0, 64))
            .thenReturn(List.of(new Tuple("next", 51.0)));

        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            "cursor-empty", 1, true, id -> id)).containsExactly("next");

        when(jedis.zscore("events", "cursor-filtered")).thenReturn(60.0);
        when(jedis.zrangeByScore("events", 60.0, 60.0, 0, 1024))
            .thenReturn(List.of("cursor-filtered", "filtered", "z-accepted"));
        assertThat(ZSetAdaptivePager.collect(jedis, "events", 0, 100,
            "cursor-filtered", 1, true, id -> id.equals("z-accepted") ? id : null))
            .containsExactly("z-accepted");
    }
}
