package io.runcycles.admin.data.repository;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Mockito adapters that preserve the RedisBatchReader production call shape. */
final class RedisBatchTestStubs {
    private RedisBatchTestStubs() {}

    static void installStringReads(Jedis jedis) {
        lenient().when(jedis.mget(any(String[].class))).thenAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            String[] keys = arguments.length == 1 && arguments[0] instanceof String[] raw
                ? raw
                : Arrays.copyOf(arguments, arguments.length, String[].class);
            List<String> values = new ArrayList<>(keys.length);
            for (String key : keys) values.add(jedis.get(key));
            return values;
        });
    }

    @SuppressWarnings("unchecked")
    static void installHashReads(Jedis jedis) {
        Pipeline pipeline = mock(Pipeline.class);
        lenient().when(jedis.pipelined()).thenReturn(pipeline);
        lenient().when(pipeline.hgetAll(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Response<Map<String, String>> response = mock(Response.class);
            lenient().when(response.get()).thenAnswer(ignored -> jedis.hgetAll(key));
            return response;
        });
    }
}
