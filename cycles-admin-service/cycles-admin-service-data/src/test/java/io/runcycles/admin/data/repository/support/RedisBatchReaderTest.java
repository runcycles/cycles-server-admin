package io.runcycles.admin.data.repository.support;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisBatchReaderTest {

    @Test
    void getById_usesOneMgetWhenResponseCardinalityMatches() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.mget("row:a", "row:b")).thenReturn(List.of("A", "B"));

        Map<String, String> rows = RedisBatchReader.getById(
            jedis, "row:", List.of("a", "b"));

        assertThat(rows).hasSize(2)
            .containsEntry("a", "A")
            .containsEntry("b", "B");
        verify(jedis, never()).get("row:a");
        verify(jedis, never()).get("row:b");
    }

    @Test
    void getById_fallsBackToGetsForNonConformingMgetResponse() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.mget("row:a", "row:b")).thenReturn(List.of("A"));
        when(jedis.get("row:a")).thenReturn("A");
        when(jedis.get("row:b")).thenReturn("B");

        Map<String, String> rows = RedisBatchReader.getById(
            jedis, "row:", List.of("a", "b"));

        assertThat(rows).containsEntry("a", "A").containsEntry("b", "B");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHashesByKey_pipelinesHashReads() {
        Jedis jedis = mock(Jedis.class);
        Pipeline pipeline = mock(Pipeline.class);
        Response<Map<String, String>> first = mock(Response.class);
        Response<Map<String, String>> second = mock(Response.class);
        when(jedis.pipelined()).thenReturn(pipeline);
        when(pipeline.hgetAll("budget:a")).thenReturn(first);
        when(pipeline.hgetAll("budget:b")).thenReturn(second);
        when(first.get()).thenReturn(Map.of("id", "a"));
        when(second.get()).thenReturn(Map.of("id", "b"));

        Map<String, Map<String, String>> rows = RedisBatchReader.getHashesByKey(
            jedis, List.of("budget:a", "budget:b"));

        assertThat(rows).containsEntry("budget:a", Map.of("id", "a"))
            .containsEntry("budget:b", Map.of("id", "b"));
        verify(pipeline).sync();
        verify(jedis, never()).hgetAll("budget:a");
        verify(jedis, never()).hgetAll("budget:b");
    }

    @Test
    void getHashesByKey_fallsBackWhenPipelineIsUnavailable() {
        Jedis jedis = mock(Jedis.class);
        when(jedis.pipelined()).thenReturn(null);
        when(jedis.hgetAll("budget:a")).thenReturn(Map.of("id", "a"));

        Map<String, Map<String, String>> rows = RedisBatchReader.getHashesByKey(
            jedis, List.of("budget:a"));

        assertThat(rows).containsEntry("budget:a", Map.of("id", "a"));
    }
}
