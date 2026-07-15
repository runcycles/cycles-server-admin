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
    public static final String COMMITTED_PREFIX = "tenant-close:committed:";
    public static final String ATTEMPTS_PREFIX = "tenant-close:outbox:attempts:";
    public static final String DEAD_LETTER_PREFIX = "tenant-close:outbox:dead-letter:";
    public static final long LEASE_MILLIS = 300_000L;
    public static final long PREPARE_GRACE_MILLIS = LEASE_MILLIS;
    public static final int MAX_OUTBOX_ATTEMPTS = 8;
    private static final String RELEASE_LEASE_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0";
    private static final String RENEW_LEASE_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0";
    private static final String PREPARE_LUA =
        "redis.call('SET', KEYS[1], ARGV[1], 'NX'); redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3]); return 1";
    private static final String ACK_LUA =
        "redis.call('DEL', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]); redis.call('HDEL', KEYS[3], ARGV[1]); return 1";
    private static final String RECORD_FAILURE_LUA =
        "local attempts = redis.call('HINCRBY', KEYS[3], ARGV[1], 1)\n"
            + "if attempts >= tonumber(ARGV[2]) then\n"
            + " redis.call('SREM', KEYS[1], ARGV[1]); redis.call('SADD', KEYS[2], ARGV[1]); return {attempts,1}\n"
            + "end\nreturn {attempts,0}";
    private static final String COMPLETE_LUA =
        "if redis.call('SCARD', KEYS[3]) == 0 and redis.call('SCARD', KEYS[4]) == 0 then "
            + "redis.call('ZREM', KEYS[1], ARGV[1]); redis.call('DEL', KEYS[2]); return 1 end return 0";
    private static final String DISCARD_UNCOMMITTED_LUA =
        "if redis.call('EXISTS', KEYS[4]) == 1 then return 0 end\n"
            + "local tenant_json = redis.call('GET', KEYS[1])\n"
            + "if tenant_json then local tenant = cjson.decode(tenant_json); if tenant['status'] == 'CLOSED' then return 0 end end\n"
            + "redis.call('ZREM', KEYS[2], ARGV[1]); redis.call('DEL', KEYS[3]); return 1";
    private static final String REQUEUE_DEAD_LETTER_LUA =
        "if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then "
            + "redis.call('SADD', KEYS[2], ARGV[1]); redis.call('HDEL', KEYS[3], ARGV[1]); "
            + "redis.call('ZADD', KEYS[4], ARGV[3], ARGV[2]); return 1 end return 0";

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
                          Instant createdAt, String parentCorrelationId,
                          String actorKeyId) {
        public Intent(String tenantId, String requestId, String traceId,
                      String correlationId, String sourceIp, String userAgent,
                      Instant createdAt) {
            this(tenantId, requestId, traceId, correlationId, sourceIp, userAgent,
                createdAt, correlationId, null);
        }
    }

    public record OutboxFailure(int attempts, boolean deadLettered) {}

    public void prepare(Intent intent) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(intent);
            String intentKey = intentKey(intent.tenantId());
            // Preserve the first request context until every mutation outbox item
            // produced by that logical close has been durably emitted.
            jedis.eval(PREPARE_LUA, List.of(intentKey, PENDING_KEY),
                List.of(json,
                    String.valueOf(System.currentTimeMillis() + PREPARE_GRACE_MILLIS),
                    intent.tenantId()));
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

    public boolean renewLease(String tenantId, String token) {
        if (token == null) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(RENEW_LEASE_LUA,
                List.of(leaseKey(tenantId)),
                List.of(token, String.valueOf(LEASE_MILLIS)));
            return Long.valueOf(1L).equals(result);
        }
    }

    public void reschedule(String tenantId, long delayMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(PENDING_KEY, System.currentTimeMillis() + Math.max(1L, delayMillis), tenantId);
        }
    }

    /**
     * Remove a dead-lettered cascade from automatic scheduling while retaining
     * its intent, committed marker, outbox body, and dead-letter membership for
     * operator inspection and requeue.
     */
    public void parkDeadLettered(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zrem(PENDING_KEY, tenantId);
        }
    }

    public List<TenantCloseOutboxItem> listOutbox(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<TenantCloseOutboxItem> items = new ArrayList<>();
            for (String itemId : jedis.smembers(outboxKey(tenantId))) {
                String json = jedis.get(outboxItemKey(tenantId, itemId));
                if (json == null) {
                    items.add(corruptItem(tenantId, itemId));
                    continue;
                }
                try {
                    items.add(objectMapper.readValue(json, TenantCloseOutboxItem.class));
                } catch (Exception e) {
                    // Return a poison marker so the cascade's normal retry and
                    // dead-letter policy handles corrupt/missing bodies instead
                    // of retrying the entire tenant forever without escalation.
                    items.add(corruptItem(tenantId, itemId));
                }
            }
            return items;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read tenant-close outbox", e);
        }
    }

    public void acknowledge(String tenantId, String itemId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(ACK_LUA,
                List.of(outboxItemKey(tenantId, itemId), outboxKey(tenantId),
                    attemptsKey(tenantId)),
                List.of(itemId));
        }
    }

    public OutboxFailure recordOutboxFailure(String tenantId, String itemId) {
        try (Jedis jedis = jedisPool.getResource()) {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) jedis.eval(RECORD_FAILURE_LUA,
                List.of(outboxKey(tenantId), deadLetterKey(tenantId), attemptsKey(tenantId)),
                List.of(itemId, String.valueOf(MAX_OUTBOX_ATTEMPTS)));
            return new OutboxFailure(result.get(0).intValue(), result.get(1) == 1L);
        }
    }

    public long deadLetterCount(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.scard(deadLetterKey(tenantId));
        }
    }

    public boolean requeueDeadLetter(String tenantId, String itemId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(REQUEUE_DEAD_LETTER_LUA,
                List.of(deadLetterKey(tenantId), outboxKey(tenantId), attemptsKey(tenantId),
                    PENDING_KEY),
                List.of(itemId, tenantId, String.valueOf(System.currentTimeMillis() + 1L)));
            return Long.valueOf(1L).equals(result);
        }
    }

    /**
     * Completes the work item only after its outbox and dead-letter set are
     * empty. The committed marker intentionally survives as the permanent
     * proof that the parent-event obligation was created for this terminal
     * tenant; an already-CLOSED retry can then distinguish a modern completed
     * close from a pre-upgrade row that needs durability backfill.
     */
    public boolean completeIfDrained(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(COMPLETE_LUA,
                List.of(PENDING_KEY, intentKey(tenantId), outboxKey(tenantId),
                    deadLetterKey(tenantId)),
                List.of(tenantId));
            return Long.valueOf(1L).equals(result);
        }
    }

    /**
     * Atomically discard an aged prepare only when the parent close never
     * committed. This script races safely with TenantRepository's atomic
     * close: either discard wins and close refuses to flip without its intent,
     * or close wins and the committed marker prevents deletion.
     */
    public boolean discardIfUncommitted(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(DISCARD_UNCOMMITTED_LUA,
                List.of("tenant:" + tenantId, PENDING_KEY, intentKey(tenantId),
                    committedKey(tenantId)),
                List.of(tenantId));
            return Long.valueOf(1L).equals(result);
        }
    }

    public static String intentKey(String tenantId) { return "tenant-close:intent:" + tenantId; }
    public static String leaseKey(String tenantId) { return "tenant-close:lease:" + tenantId; }
    public static String committedKey(String tenantId) { return COMMITTED_PREFIX + tenantId; }
    public static String outboxKey(String tenantId) { return OUTBOX_PREFIX + tenantId; }
    public static String attemptsKey(String tenantId) { return ATTEMPTS_PREFIX + tenantId; }
    public static String deadLetterKey(String tenantId) { return DEAD_LETTER_PREFIX + tenantId; }
    public static String outboxItemKey(String tenantId, String itemId) {
        return OUTBOX_ITEM_PREFIX + tenantId + ":" + itemId;
    }

    private static TenantCloseOutboxItem corruptItem(String tenantId, String itemId) {
        return new TenantCloseOutboxItem(itemId, tenantId, "corrupt", itemId,
            null, null, null, null, 0L);
    }
}
