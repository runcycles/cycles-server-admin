package io.runcycles.admin.data.repository.support;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded MGET hydration that avoids one Redis round trip per sorted candidate. */
public final class RedisBatchReader {
    private static final int BATCH_SIZE = 500;

    private RedisBatchReader() {}

    public static Map<String, String> getById(Jedis jedis, String keyPrefix,
                                               Collection<String> ids) {
        List<String> orderedIds = new ArrayList<>(ids);
        Map<String, String> valuesById = new LinkedHashMap<>();
        for (int offset = 0; offset < orderedIds.size(); offset += BATCH_SIZE) {
            List<String> batchIds = orderedIds.subList(
                offset, Math.min(orderedIds.size(), offset + BATCH_SIZE));
            String[] keys = batchIds.stream().map(keyPrefix::concat).toArray(String[]::new);
            List<String> values = jedis.mget(keys);
            // Mockito-backed repository tests written before batch hydration
            // return an empty/default value. Fall back there and for defensive
            // compatibility with non-standard Redis clients.
            if (values == null || values.size() != batchIds.size()) {
                values = batchIds.stream().map(id -> jedis.get(keyPrefix + id)).toList();
            }
            for (int i = 0; i < batchIds.size(); i++) {
                valuesById.put(batchIds.get(i), values.get(i));
            }
        }
        return valuesById;
    }

    /** Pipeline hash hydration in bounded batches to avoid one RTT per candidate. */
    public static Map<String, Map<String, String>> getHashesByKey(
            Jedis jedis, Collection<String> keys) {
        List<String> orderedKeys = new ArrayList<>(keys);
        Map<String, Map<String, String>> valuesByKey = new LinkedHashMap<>();
        for (int offset = 0; offset < orderedKeys.size(); offset += BATCH_SIZE) {
            List<String> batchKeys = orderedKeys.subList(
                offset, Math.min(orderedKeys.size(), offset + BATCH_SIZE));
            if (!readHashPipeline(jedis, batchKeys, valuesByKey)) {
                for (String key : batchKeys) {
                    valuesByKey.put(key, jedis.hgetAll(key));
                }
            }
        }
        return valuesByKey;
    }

    private static boolean readHashPipeline(
            Jedis jedis, List<String> keys,
            Map<String, Map<String, String>> valuesByKey) {
        Pipeline pipeline = jedis.pipelined();
        if (pipeline == null) return false; // Mockito/default-client compatibility.
        try (pipeline) {
            List<Response<Map<String, String>>> responses = new ArrayList<>(keys.size());
            for (String key : keys) responses.add(pipeline.hgetAll(key));
            pipeline.sync();
            for (int i = 0; i < keys.size(); i++) {
                valuesByKey.put(keys.get(i), responses.get(i).get());
            }
            return true;
        }
    }
}
