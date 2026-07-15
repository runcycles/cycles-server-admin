package io.runcycles.admin.data.repository.support;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Lossless cursor walk over a score-ordered Redis ZSET.
 *
 * <p>The stored member remains the wire cursor for backward compatibility.
 * Equal-score members are resumed by Redis' deterministic member ordering,
 * while sparse post-hydration filters are handled by continuing in bounded
 * batches until the requested number of matching rows is found or the score
 * range is genuinely exhausted.</p>
 */
public final class ZSetAdaptivePager {
    private static final int MIN_BATCH_SIZE = 64;
    private static final int MAX_BATCH_SIZE = 1024;
    public static final int DEFAULT_MAX_SCANNED_MEMBERS = 5_000;

    private ZSetAdaptivePager() {}

    /**
     * @param loader hydrates and filters one member; return {@code null} to
     *               skip a missing, malformed, or non-matching row
     */
    public static <T> List<T> collect(
            Jedis jedis,
            String indexKey,
            double minScore,
            double maxScore,
            String cursor,
            int limit,
            boolean ascending,
            Function<String, T> loader) {
        return collect(jedis, indexKey, minScore, maxScore, cursor, limit,
            ascending, "timestamp-indexed", DEFAULT_MAX_SCANNED_MEMBERS, loader);
    }

    public static <T> List<T> collect(
            Jedis jedis,
            String indexKey,
            double minScore,
            double maxScore,
            String cursor,
            int limit,
            boolean ascending,
            String surface,
            Function<String, T> loader) {
        return collect(jedis, indexKey, minScore, maxScore, cursor, limit,
            ascending, surface, DEFAULT_MAX_SCANNED_MEMBERS, loader);
    }

    static <T> List<T> collect(
            Jedis jedis,
            String indexKey,
            double minScore,
            double maxScore,
            String cursor,
            int limit,
            boolean ascending,
            String surface,
            int maxScannedMembers,
            Function<String, T> loader) {
        List<T> results = new ArrayList<>(Math.max(0, limit));
        if (limit <= 0 || minScore > maxScore) {
            return results;
        }
        ScanBudget budget = new ScanBudget(surface, maxScannedMembers);

        double currentMin = minScore;
        double currentMax = maxScore;
        if (cursor != null && !cursor.isBlank()) {
            Double cursorScore = jedis.zscore(indexKey, cursor);
            if (cursorScore == null || cursorScore < minScore || cursorScore > maxScore) {
                throw invalidCursor();
            }
            if (collectAfterAtScore(jedis, indexKey, cursorScore, cursor,
                    ascending, results, limit, budget, loader)) {
                return results;
            }
            if (ascending) {
                currentMin = Math.max(currentMin, Math.nextUp(cursorScore));
            } else {
                currentMax = Math.min(currentMax, Math.nextDown(cursorScore));
            }
        }

        int batchSize = Math.max(MIN_BATCH_SIZE, Math.min(MAX_BATCH_SIZE, limit * 3));
        while (results.size() < limit && currentMin <= currentMax) {
            List<Tuple> page = range(jedis, indexKey, currentMin, currentMax,
                ascending, batchSize);
            if (page.isEmpty()) {
                break;
            }
            List<String> ids = page.stream().map(Tuple::getElement).toList();
            if (collectIds(ids, 0, results, limit, budget, loader)) {
                break;
            }
            if (ids.size() < batchSize) {
                break;
            }

            Tuple boundary = page.get(page.size() - 1);
            String lastId = boundary.getElement();
            double lastScore = boundary.getScore();

            if (collectAfterAtScore(jedis, indexKey, lastScore, lastId,
                    ascending, results, limit, budget, loader)) {
                break;
            }
            if (ascending) {
                currentMin = Math.max(currentMin, Math.nextUp(lastScore));
            } else {
                currentMax = Math.min(currentMax, Math.nextDown(lastScore));
            }
        }
        return results;
    }

    private static <T> boolean collectIds(List<String> ids, int start,
                                           List<T> results, int limit,
                                           ScanBudget budget,
                                           Function<String, T> loader) {
        for (int i = start; i < ids.size(); i++) {
            budget.consume(limit > 1 && results.size() == limit - 1);
            T row = loader.apply(ids.get(i));
            if (row != null) {
                results.add(row);
                if (results.size() >= limit) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Tuple> range(Jedis jedis, String indexKey,
                                     double minScore, double maxScore,
                                     boolean ascending, int count) {
        return ascending
            ? jedis.zrangeByScoreWithScores(indexKey, minScore, maxScore, 0, count)
            : jedis.zrevrangeByScoreWithScores(indexKey, maxScore, minScore, 0, count);
    }

    /**
     * Traverse one equal-score bucket in bounded chunks and collect members
     * strictly after {@code boundary} in Redis' lexicographic tie order. A
     * lexical comparison (instead of an index lookup) preserves progress if
     * the boundary is concurrently deleted between the initial page and this
     * continuation read.
     */
    private static <T> boolean collectAfterAtScore(
            Jedis jedis,
            String indexKey,
            double score,
            String boundary,
            boolean ascending,
            List<T> results,
            int limit,
            ScanBudget budget,
            Function<String, T> loader) {
        Long globalRank = ascending
            ? jedis.zrank(indexKey, boundary)
            : jedis.zrevrank(indexKey, boundary);
        if (globalRank == null) throw invalidCursor();
        long earlierScoreMembers = ascending
            ? jedis.zcount(indexKey, Double.NEGATIVE_INFINITY, Math.nextDown(score))
            : jedis.zcount(indexKey, Math.nextUp(score), Double.POSITIVE_INFINITY);
        long equalScoreRank = Math.max(0L, globalRank - earlierScoreMembers);
        if (equalScoreRank > Integer.MAX_VALUE) {
            throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                "The " + budget.surface + " equal-timestamp bucket is too large to page safely",
                400, java.util.Map.of("equal_score_cursor_offset", equalScoreRank));
        }
        // Start at the boundary's rank and compare lexically. If the boundary
        // is deleted after the rank lookup, the member that shifts into its
        // position is still considered instead of being skipped.
        int offset = (int) equalScoreRank;
        while (results.size() < limit) {
            List<String> tied = ascending
                ? jedis.zrangeByScore(indexKey, score, score, offset, MAX_BATCH_SIZE)
                : jedis.zrevrangeByScore(indexKey, score, score, offset, MAX_BATCH_SIZE);
            if (tied.isEmpty()) return false;
            for (String id : tied) {
                int comparison = id.compareTo(boundary);
                boolean after = ascending ? comparison > 0 : comparison < 0;
                if (!after) continue;
                budget.consume(limit > 1 && results.size() == limit - 1);
                T row = loader.apply(id);
                if (row != null) {
                    results.add(row);
                    if (results.size() >= limit) return true;
                }
            }
            if (tied.size() < MAX_BATCH_SIZE) return false;
            offset += tied.size();
        }
        return true;
    }

    private static GovernanceException invalidCursor() {
        return CursorSupport.invalidCursor();
    }

    private static final class ScanBudget {
        private final String surface;
        private final int maximum;
        private int scanned;

        private ScanBudget(String surface, int maximum) {
            this.surface = surface;
            this.maximum = Math.max(1, maximum);
        }

        private void consume(boolean truthfulnessLookahead) {
            scanned++;
            // Once the wire page is full, permit exactly one candidate beyond
            // the base safety budget to prove has_more without rejecting the
            // common all-matching boundary case. A filtered-out lookahead does
            // not make later unbounded scanning permissible.
            boolean allowedLookahead = truthfulnessLookahead && scanned == maximum + 1;
            if (scanned > maximum && !allowedLookahead) {
                throw new GovernanceException(ErrorCode.LIMIT_EXCEEDED,
                    "The " + surface + " query scanned more than " + maximum
                        + " candidates without filling the page; narrow the tenant, time window, or filters",
                    400, java.util.Map.of(
                        "scanned_candidates", scanned,
                        "max_scan_candidates", maximum));
            }
        }
    }
}
