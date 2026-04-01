package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.webhook.DeliveryStatus;
import io.runcycles.admin.model.webhook.WebhookDelivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class WebhookDeliveryRepository {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    @Value("${events.retention.delivery-ttl-days:14}")
    private int deliveryTtlDays;

    // Lua script for atomic delivery creation with TTL
    private static final String SAVE_DELIVERY_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[4])\n" +
        "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n" +
        "return 1\n";

    public void save(WebhookDelivery delivery) {
        try (Jedis jedis = jedisPool.getResource()) {
            String deliveryId = "del_" + UUID.randomUUID().toString().substring(0, 16);
            delivery.setDeliveryId(deliveryId);
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
            LOG.error("Failed to save webhook delivery", e);
            throw new RuntimeException("Failed to save webhook delivery", e);
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
            LOG.error("Failed to read webhook delivery: {}", deliveryId, e);
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

            if (cursor != null && !cursor.isBlank()) {
                Double cursorScore = jedis.zscore(indexKey, cursor);
                if (cursorScore != null) {
                    maxScore = Math.min(maxScore, cursorScore - 1);
                }
            }

            List<String> ids = jedis.zrevrangeByScore(indexKey, maxScore, minScore, 0, limit * 3);
            List<WebhookDelivery> deliveries = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("delivery:" + id);
                    if (data == null) {
                        LOG.warn("Delivery data missing for id: {}", id);
                        continue;
                    }
                    WebhookDelivery delivery = objectMapper.readValue(data, WebhookDelivery.class);
                    if (status != null && (delivery.getStatus() == null ||
                            !status.equals(delivery.getStatus().name()))) continue;
                    deliveries.add(delivery);
                    if (deliveries.size() >= limit) break;
                } catch (Exception e) {
                    LOG.warn("Failed to parse delivery: {}", id, e);
                }
            }
            return deliveries;
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
            jedis.set("delivery:" + delivery.getDeliveryId(), json);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to update webhook delivery: {}", delivery.getDeliveryId(), e);
            throw new RuntimeException("Failed to update webhook delivery", e);
        }
    }
}
