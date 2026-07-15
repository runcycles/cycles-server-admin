package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.logging.LogSanitizer;
import io.runcycles.admin.data.repository.support.ZSetAdaptivePager;
import io.runcycles.admin.model.webhook.DeliveryStatus;
import io.runcycles.admin.model.webhook.WebhookDelivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class WebhookDeliveryRepository {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired @Qualifier("redisObjectMapper") private ObjectMapper objectMapper;

    @Value("${events.retention.delivery-ttl-days:14}")
    private int deliveryTtlDays;

    // Lua script for atomic delivery creation with TTL
    private static final String SAVE_DELIVERY_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[4])\n" +
        "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n" +
        "return 1\n";
    private static final String SAVE_AND_ENQUEUE_ONCE_LUA =
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end\n"
            + "redis.call('SET', KEYS[1], ARGV[1])\n"
            + "redis.call('EXPIRE', KEYS[1], ARGV[4])\n"
            + "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n"
            + "redis.call('LPUSH', KEYS[3], ARGV[3])\n"
            + "return 1";

    public void save(WebhookDelivery delivery) {
        try (Jedis jedis = jedisPool.getResource()) {
            String deliveryId = delivery.getDeliveryId();
            if (deliveryId == null || deliveryId.isBlank()) {
                deliveryId = "del_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                delivery.setDeliveryId(deliveryId);
            }
            if (delivery.getAttemptedAt() == null) {
                delivery.setAttemptedAt(Instant.now());
            }
            String json = objectMapper.writeValueAsString(delivery);
            String score = String.valueOf(delivery.getAttemptedAt().toEpochMilli());
            jedis.eval(SAVE_DELIVERY_LUA,
                List.of("delivery:" + deliveryId,
                        "deliveries:" + delivery.getSubscriptionId()),
                List.of(json, score, deliveryId, String.valueOf(deliveryTtlDays * 86400L)));
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to save webhook delivery: delivery_id={} subscription_id={} event_id={} event_type={} status={} trace_id={}",
                delivery != null ? LogSanitizer.safe(delivery.getDeliveryId()) : null,
                delivery != null ? LogSanitizer.safe(delivery.getSubscriptionId()) : null,
                delivery != null ? LogSanitizer.safe(delivery.getEventId()) : null,
                delivery != null && delivery.getEventType() != null ? delivery.getEventType().getValue() : null,
                delivery != null ? delivery.getStatus() : null,
                delivery != null ? delivery.getTraceId() : null,
                e);
            throw new RuntimeException("Failed to save webhook delivery", e);
        }
    }

    /**
     * Atomically persist and enqueue a deterministic delivery once. Returning
     * {@code false} means the same delivery already exists and was therefore
     * enqueued by the winning attempt; callers may acknowledge their outbox.
     */
    public boolean saveAndEnqueueOnce(WebhookDelivery delivery, String queueKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (delivery.getDeliveryId() == null || delivery.getDeliveryId().isBlank()) {
                throw new IllegalArgumentException("Deterministic delivery_id is required");
            }
            if (delivery.getAttemptedAt() == null) delivery.setAttemptedAt(Instant.now());
            String json = objectMapper.writeValueAsString(delivery);
            String score = String.valueOf(delivery.getAttemptedAt().toEpochMilli());
            Object result = jedis.eval(SAVE_AND_ENQUEUE_ONCE_LUA,
                List.of("delivery:" + delivery.getDeliveryId(),
                    "deliveries:" + delivery.getSubscriptionId(), queueKey),
                List.of(json, score, delivery.getDeliveryId(),
                    String.valueOf(deliveryTtlDays * 86400L)));
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to atomically save and enqueue webhook delivery", e);
        }
    }

    public WebhookDelivery findById(String deliveryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("delivery:" + deliveryId);
            if (data == null) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.NOT_FOUND,
                    "Delivery not found: " + deliveryId, 404);
            }
            return objectMapper.readValue(data, WebhookDelivery.class);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to read webhook delivery: delivery_id={}", LogSanitizer.safe(deliveryId), e);
            throw new RuntimeException("Failed to read webhook delivery", e);
        }
    }

    public List<WebhookDelivery> listBySubscription(String subscriptionId, String status,
                                                     Instant from, Instant to,
                                                     String cursor, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
            double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
            String indexKey = "deliveries:" + subscriptionId;

            return ZSetAdaptivePager.collect(jedis, indexKey, minScore, maxScore,
                cursor, limit, false, "webhook-delivery", id -> {
                try {
                    String data = jedis.get("delivery:" + id);
                    if (data == null) {
                        LOG.warn("Webhook delivery index points to missing row: delivery_id={} subscription_id={} index_key={} status_filter={}",
                            LogSanitizer.safe(id), LogSanitizer.safe(subscriptionId), LogSanitizer.safe(indexKey), status);
                        return null;
                    }
                    WebhookDelivery delivery = objectMapper.readValue(data, WebhookDelivery.class);
                    if (status != null && (delivery.getStatus() == null ||
                            !status.equals(delivery.getStatus().name()))) return null;
                    return delivery;
                } catch (Exception e) {
                    LOG.warn("Failed to parse webhook delivery row: delivery_id={} subscription_id={} index_key={} status_filter={}",
                        LogSanitizer.safe(id), LogSanitizer.safe(subscriptionId), LogSanitizer.safe(indexKey), status, e);
                    return null;
                }
            });
        }
    }

    public void update(WebhookDelivery delivery) {
        try (Jedis jedis = jedisPool.getResource()) {
            String existing = jedis.get("delivery:" + delivery.getDeliveryId());
            if (existing == null) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.NOT_FOUND,
                    "Delivery not found: " + delivery.getDeliveryId(), 404);
            }
            String json = objectMapper.writeValueAsString(delivery);
            String key = "delivery:" + delivery.getDeliveryId();
            long ttl = jedis.ttl(key);
            jedis.set(key, json);
            if (ttl > 0) {
                jedis.expire(key, ttl); // Preserve existing TTL after SET
            }
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to update webhook delivery: delivery_id={} subscription_id={} event_id={} event_type={} status={} attempts={} trace_id={}",
                delivery != null ? LogSanitizer.safe(delivery.getDeliveryId()) : null,
                delivery != null ? LogSanitizer.safe(delivery.getSubscriptionId()) : null,
                delivery != null ? LogSanitizer.safe(delivery.getEventId()) : null,
                delivery != null && delivery.getEventType() != null ? delivery.getEventType().getValue() : null,
                delivery != null ? delivery.getStatus() : null,
                delivery != null ? delivery.getAttempts() : null,
                delivery != null ? delivery.getTraceId() : null,
                e);
            throw new RuntimeException("Failed to update webhook delivery", e);
        }
    }
}
