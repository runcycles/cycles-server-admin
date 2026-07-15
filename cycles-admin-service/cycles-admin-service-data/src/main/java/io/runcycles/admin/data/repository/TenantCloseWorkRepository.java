package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.repository.support.TenantCloseOutboxItem;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Redis-backed work queue, origin context, lease, and child-observability outbox. */
@Repository
public class TenantCloseWorkRepository {
    public static final String PENDING_KEY = "tenant-close:pending";
    public static final String OUTBOX_PREFIX = "tenant-close:outbox:";
    public static final String OUTBOX_ITEM_PREFIX = "tenant-close:outbox:item:";
    private static final long LEASE_MILLIS = 300_000L;
    private static final String RELEASE_LEASE_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0";
    private static final String PREPARE_LUA =
        "redis.call('SET', KEYS[1], ARGV[1], 'NX'); redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3]); return 1";
    private static final String ACK_LUA =
        "redis.call('DEL', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]); return 1";
    private static final String COMPLETE_LUA =
        "if redis.call('SCARD', KEYS[3]) == 0 then "
            + "redis.call('ZREM', KEYS[1], ARGV[1]); redis.call('DEL', KEYS[2]); return 1 end return 0";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public TenantCloseWorkRepository(JedisPool jedisPool,
                                     @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intent(String tenantId, String requestId, String traceId,
                         String correlationId, String sourceIp, String userAgent,
                         Instant createdAt) {}

    public void prepare(Intent intent) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(intent);
            String intentKey = intentKey(intent.tenantId());
            // Preserve the first request context until every mutation outbox item
            // produced by that logical close has been durably emitted.
            jedis.eval(PREPARE_LUA, List.of(intentKey, PENDING_KEY),
                List.of(json, String.valueOf(System.currentTimeMillis()), intent.tenantId()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist tenant-close intent", e);
        }
    }

    public Optional<Intent> findIntent(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(intentKey(tenantId));
            return json == null ? Optional.empty()
                : Optional.of(objectMapper.readValue(json, Intent.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read tenant-close intent", e);
        }
    }

    public List<String> dueTenantIds(int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zrangeByScore(PENDING_KEY, Double.NEGATIVE_INFINITY,
                System.currentTimeMillis(), 0, Math.max(0, limit));
        }
    }

    public String tryAcquireLease(String tenantId) {
        String token = UUID.randomUUID().toString();
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(leaseKey(tenantId), token,
                SetParams.setParams().nx().px(LEASE_MILLIS));
            return "OK".equals(result) ? token : null;
        }
    }

    public void releaseLease(String tenantId, String token) {
        if (token == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(RELEASE_LEASE_LUA, List.of(leaseKey(tenantId)), List.of(token));
        }
    }

    public void reschedule(String tenantId, long delayMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(PENDING_KEY, System.currentTimeMillis() + Math.max(1L, delayMillis), tenantId);
        }
    }

    public List<TenantCloseOutboxItem> listOutbox(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<TenantCloseOutboxItem> items = new ArrayList<>();
            for (String itemId : jedis.smembers(outboxKey(tenantId))) {
                String json = jedis.get(outboxItemKey(tenantId, itemId));
                if (json == null) {
                    throw new IllegalStateException(
                        "Tenant-close outbox index points to a missing item: " + itemId);
                }
                items.add(objectMapper.readValue(json, TenantCloseOutboxItem.class));
            }
            return items;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read tenant-close outbox", e);
        }
    }

    public void acknowledge(String tenantId, String itemId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(ACK_LUA,
                List.of(outboxItemKey(tenantId, itemId), outboxKey(tenantId)),
                List.of(itemId));
        }
    }

    /** Completes the work item only after its child outbox is empty. */
    public boolean completeIfDrained(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(COMPLETE_LUA,
                List.of(PENDING_KEY, intentKey(tenantId), outboxKey(tenantId)),
                List.of(tenantId));
            return Long.valueOf(1L).equals(result);
        }
    }

    public void discard(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zrem(PENDING_KEY, tenantId);
            jedis.del(intentKey(tenantId));
        }
    }

    public static String intentKey(String tenantId) { return "tenant-close:intent:" + tenantId; }
    public static String leaseKey(String tenantId) { return "tenant-close:lease:" + tenantId; }
    public static String outboxKey(String tenantId) { return OUTBOX_PREFIX + tenantId; }
    public static String outboxItemKey(String tenantId, String itemId) {
        return OUTBOX_ITEM_PREFIX + tenantId + ":" + itemId;
    }
}
