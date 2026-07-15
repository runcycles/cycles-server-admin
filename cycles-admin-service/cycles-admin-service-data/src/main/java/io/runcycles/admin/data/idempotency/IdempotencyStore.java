package io.runcycles.admin.data.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.logging.LogSanitizer;
import io.runcycles.admin.model.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * Redis-backed atomic idempotency coordinator for admin bulk-action endpoints
 * (spec v0.1.25.21). A request claims {@code (endpoint, idempotencyKey,
 * payloadHash)} before reading mutable match state, then publishes one immutable
 * JSON response envelope for the 15-minute replay window.
 *
 * <p>Key shape: {@code idem:v2:{endpoint}:{key}} — {@code endpoint} is a
 * short caller-owned discriminator (e.g. {@code "tenants-bulk"},
 * {@code "webhooks-bulk"}) so distinct endpoints cannot collide on the
 * same operator-supplied key.
 *
 * <p>This store is not a replacement for the {@code BudgetController.fund}
 * idempotency embedded in {@code BudgetRepository}'s Lua script. The
 * fund-path idempotency is check-and-cache inside the same atomic Lua
 * transaction as the balance mutation — externalising it would lose the
 * atomic "never apply twice under concurrent retry" guarantee. Bulk-action
 * idempotency has a different shape: the mutation is a per-row loop, so the
 * owner claim serializes equal retries and stale ownership can be taken over.
 */
@Component
public class IdempotencyStore {
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyStore.class);
    /** 15-minute replay window per spec v0.1.25.21. */
    public static final int TTL_SECONDS = 900;
    private static final String KEY_PREFIX = "idem:";
    private static final String V2_KEY_PREFIX = "idem:v2:";
    private static final long OWNER_STALE_MILLIS = 60_000L;
    private static final long WAIT_NANOS = 5_000_000_000L;
    private static final String BEGIN_LUA =
        "local state = redis.call('HGET', KEYS[1], 'state')\n" +
        "if not state then\n" +
        " redis.call('HSET', KEYS[1], 'state','IN_PROGRESS','payload_hash',ARGV[1],'owner',ARGV[2],'started_at',ARGV[3])\n" +
        " redis.call('EXPIRE', KEYS[1], ARGV[4]); return {'ACQUIRED'}\n" +
        "end\n" +
        "local stored_hash = redis.call('HGET', KEYS[1], 'payload_hash') or ''\n" +
        "if stored_hash ~= ARGV[1] then return {'MISMATCH'} end\n" +
        "if state == 'COMPLETE' then return {'COMPLETE', redis.call('HGET', KEYS[1], 'response') or ''} end\n" +
        "local started = tonumber(redis.call('HGET', KEYS[1], 'started_at') or '0')\n" +
        "if tonumber(ARGV[3]) - started > tonumber(ARGV[5]) then\n" +
        " redis.call('HSET', KEYS[1], 'owner',ARGV[2],'started_at',ARGV[3]); redis.call('EXPIRE', KEYS[1], ARGV[4]); return {'ACQUIRED'}\n" +
        "end\n" +
        "return {'IN_PROGRESS'}\n";
    private static final String COMPLETE_LUA =
        "if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end\n" +
        "redis.call('HSET', KEYS[1], 'state','COMPLETE','response',ARGV[2])\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[3]); return 1\n";
    private static final String ABANDON_LUA =
        "if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end\n" +
        "if redis.call('HGET', KEYS[1], 'state') ~= 'IN_PROGRESS' then return 0 end\n" +
        "return redis.call('DEL', KEYS[1])\n";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    @Autowired
    public IdempotencyStore(JedisPool jedisPool,
                            @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    /**
     * Look up a legacy pre-v2 response envelope for this (endpoint, key) pair.
     * Returns an empty Optional on cache miss OR on any deserialisation
     * failure — corrupt cache entries degrade to a fresh apply instead of
     * propagating the parse error to the caller, because the mutation is
     * still a bounded operation.
     *
     * <p>Legacy only: entries do not carry a payload hash and therefore cannot
     * safely satisfy current bulk requests. Retained for rolling-upgrade
     * diagnostics; production controllers use {@link #begin}.
     */
    public <T> Optional<T> lookup(String endpoint, String key, Class<T> type) {
        String redisKey = redisKey(endpoint, key);
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(redisKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            LOG.warn("Corrupt idempotency entry: endpoint={} key_present={} key_sha256={} response_type={} error={}",
                    endpoint, key != null && !key.isBlank(), fingerprint(key), type.getSimpleName(), LogSanitizer.safe(e.getMessage()));
            return Optional.empty();
        } catch (Exception e) {
            LOG.warn("Idempotency lookup failed: endpoint={} key_present={} key_sha256={} response_type={} error={}",
                    endpoint, key != null && !key.isBlank(), fingerprint(key), type.getSimpleName(), LogSanitizer.safe(e.getMessage()), e);
            return Optional.empty();
        }
    }

    /** Ownership token for one atomic bulk invocation, or an immutable replay. */
    public record Claim<T>(String endpoint, String key, String ownerToken,
                           T replayResponse) {
        public boolean isReplay() { return replayResponse != null; }
    }

    /**
     * Atomically claim a bulk idempotency key. Concurrent equal requests wait
     * briefly for and then replay the first immutable response; different
     * payloads receive IDEMPOTENCY_MISMATCH.
     */
    public <T> Claim<T> begin(String endpoint, String key, Object request, Class<T> type) {
        String payloadHash;
        try {
            payloadHash = fingerprint(objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fingerprint idempotent request", e);
        }
        String owner = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (true) {
            try (Jedis jedis = jedisPool.getResource()) {
                @SuppressWarnings("unchecked")
                List<String> result = (List<String>) jedis.eval(BEGIN_LUA,
                    List.of(v2RedisKey(endpoint, key)),
                    List.of(payloadHash, owner, String.valueOf(System.currentTimeMillis()),
                        String.valueOf(TTL_SECONDS), String.valueOf(OWNER_STALE_MILLIS)));
                switch (result.get(0)) {
                    case "ACQUIRED": return new Claim<>(endpoint, key, owner, null);
                    case "COMPLETE": return new Claim<>(endpoint, key, null,
                        objectMapper.readValue(result.get(1), type));
                    case "MISMATCH": throw new GovernanceException(ErrorCode.IDEMPOTENCY_MISMATCH,
                        "Idempotency key was already used with a different request", 409);
                    case "IN_PROGRESS": {
                        if (System.nanoTime() >= deadline) {
                            throw new GovernanceException(ErrorCode.INTERNAL_ERROR,
                                "An invocation with this idempotency key is still in progress", 503);
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            throw new GovernanceException(ErrorCode.INTERNAL_ERROR,
                                "Interrupted while waiting for an idempotent invocation", 503);
                        }
                        break;
                    }
                    default: throw new IllegalStateException("Unexpected idempotency result: " + result);
                }
            } catch (GovernanceException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to claim idempotency key", e);
            }
            LockSupport.parkNanos(50_000_000L);
        }
    }

    public <T> void complete(Claim<?> claim, T envelope) {
        if (claim == null || claim.isReplay()) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(envelope);
            Object result = jedis.eval(COMPLETE_LUA,
                List.of(v2RedisKey(claim.endpoint(), claim.key())),
                List.of(claim.ownerToken(), json, String.valueOf(TTL_SECONDS)));
            if (!Long.valueOf(1L).equals(result)) {
                throw new IllegalStateException("Idempotency ownership was lost before completion");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to complete idempotency response", e);
        }
    }

    /**
     * Release an owner claim when a pre-mutation match/count gate rejects the
     * request. The compare-and-delete script cannot remove another invocation's
     * claim or a completed replay envelope.
     */
    public void abandon(Claim<?> claim) {
        if (claim == null || claim.isReplay()) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(ABANDON_LUA,
                List.of(v2RedisKey(claim.endpoint(), claim.key())),
                List.of(claim.ownerToken()));
        } catch (Exception e) {
            LOG.warn("Failed to abandon idempotency claim: endpoint={} key_present={} key_sha256={} error={}",
                claim.endpoint(), claim.key() != null && !claim.key().isBlank(),
                fingerprint(claim.key()), LogSanitizer.safe(e.getMessage()), e);
        }
    }

    /**
     * Write a legacy pre-v2 replay entry.
     *
     * <p>Legacy only. Current callers use
     * {@link #begin(String, String, Object, Class)} and
     * {@link #complete(Claim, Object)} so the payload is verified and
     * concurrent invocations cannot both mutate rows.
     */
    public <T> void store(String endpoint, String key, T envelope) {
        String redisKey = redisKey(endpoint, key);
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(envelope);
            jedis.setex(redisKey, TTL_SECONDS, json);
        } catch (Exception e) {
            LOG.warn("Idempotency store failed: endpoint={} key_present={} key_sha256={} envelope_type={} ttl_seconds={} error={}",
                    endpoint, key != null && !key.isBlank(), fingerprint(key),
                    envelope != null ? envelope.getClass().getSimpleName() : null, TTL_SECONDS, LogSanitizer.safe(e.getMessage()), e);
        }
    }

    private static String redisKey(String endpoint, String key) {
        return KEY_PREFIX + endpoint + ":" + key;
    }

    private static String v2RedisKey(String endpoint, String key) {
        return V2_KEY_PREFIX + endpoint + ":" + key;
    }

    private static String fingerprint(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "sha256_unavailable";
        }
    }
}
