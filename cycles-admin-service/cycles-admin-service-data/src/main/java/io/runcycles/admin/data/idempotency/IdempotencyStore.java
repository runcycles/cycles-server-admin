package io.runcycles.admin.data.idempotency;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.logging.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
/**
 * Redis-backed idempotency cache for admin bulk-action endpoints
 * (spec v0.1.25.21). Stores the full JSON response envelope keyed by
 * {@code (endpoint, idempotencyKey)} with a 15-minute TTL so a retried
 * request returns the original response without re-applying the action.
 *
 * <p>Key shape: {@code idem:{endpoint}:{key}} — {@code endpoint} is a
 * short caller-owned discriminator (e.g. {@code "tenants-bulk"},
 * {@code "webhooks-bulk"}) so distinct endpoints cannot collide on the
 * same operator-supplied key.
 *
 * <p>NOTE — design divergence from the v0.1.25.26 release plan: this
 * store is NOT a drop-in replacement for the {@code BudgetController.fund}
 * idempotency embedded in {@code BudgetRepository}'s Lua script. The
 * fund-path idempotency is check-and-cache inside the same atomic Lua
 * transaction as the balance mutation — externalising it would lose the
 * atomic "never apply twice under concurrent retry" guarantee. Bulk-action
 * idempotency has a different shape: the mutation is a per-row loop that
 * completes before the envelope is cached, so a standalone JSON cache is
 * correct here.
 */
@Component
public class IdempotencyStore {
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyStore.class);
    /** 15-minute replay window per spec v0.1.25.21. */
    public static final int TTL_SECONDS = 900;
    private static final String KEY_PREFIX = "idem:";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    @Autowired
    public IdempotencyStore(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    /**
     * Look up a cached response envelope for this (endpoint, key) pair.
     * Returns an empty Optional on cache miss OR on any deserialisation
     * failure — corrupt cache entries degrade to a fresh apply instead of
     * propagating the parse error to the caller, because the mutation is
     * still a bounded operation.
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

    /**
     * Cache a response envelope for 15 minutes. Uses Redis {@code SET ... EX}
     * so repeated stores under the same key refresh the TTL — this is the
     * desired semantics when a client retries with the same idempotency
     * key after a partial failure in the caller (the freshest response
     * wins). Store failures are logged but not thrown; the caller has
     * already returned to the user, and a missed cache just means the
     * next retry will re-apply (safe because the bulk op is deterministic
     * given the same filter + action).
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
