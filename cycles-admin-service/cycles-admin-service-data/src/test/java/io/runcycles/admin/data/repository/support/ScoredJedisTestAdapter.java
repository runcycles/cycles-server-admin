package io.runcycles.admin.data.repository.support;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.Tuple;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/** Bridges older member-only repository fixtures to scored ZSET page reads. */
public final class ScoredJedisTestAdapter {
    private ScoredJedisTestAdapter() {}

    public static void install(Jedis jedis) {
        // Exact-score continuation fixtures historically stubbed the no-offset
        // overload. Preserve those fixtures while the production pager always
        // enforces a bounded offset/count read.
        lenient().when(jedis.zrangeByScore(anyString(), anyDouble(), anyDouble(),
                anyInt(), anyInt())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            double min = invocation.getArgument(1);
            double max = invocation.getArgument(2);
            return Double.compare(min, max) == 0
                ? jedis.zrangeByScore(key, min, max) : List.of();
        });
        lenient().when(jedis.zrevrangeByScore(anyString(), anyDouble(), anyDouble(),
                anyInt(), anyInt())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            double max = invocation.getArgument(1);
            double min = invocation.getArgument(2);
            return Double.compare(min, max) == 0
                ? jedis.zrevrangeByScore(key, max, min) : List.of();
        });

        lenient().when(jedis.zrangeByScoreWithScores(anyString(), anyDouble(),
                anyDouble(), anyInt(), anyInt())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            double min = invocation.getArgument(1);
            double max = invocation.getArgument(2);
            int offset = invocation.getArgument(3);
            int count = invocation.getArgument(4);
            double fallbackScore = Double.isFinite(min) ? min : 0.0;
            return jedis.zrangeByScore(key, min, max, offset, count).stream()
                .map(id -> new Tuple(id, fallbackScore)).toList();
        });
        lenient().when(jedis.zrevrangeByScoreWithScores(anyString(), anyDouble(),
                anyDouble(), anyInt(), anyInt())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            double max = invocation.getArgument(1);
            double min = invocation.getArgument(2);
            int offset = invocation.getArgument(3);
            int count = invocation.getArgument(4);
            double fallbackScore = Double.isFinite(max) ? max : 0.0;
            return jedis.zrevrangeByScore(key, max, min, offset, count).stream()
                .map(id -> new Tuple(id, fallbackScore)).toList();
        });
    }
}
