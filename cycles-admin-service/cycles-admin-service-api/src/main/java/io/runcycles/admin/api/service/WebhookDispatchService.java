package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class WebhookDispatchService {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookDispatchService.class);
    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final JedisPool jedisPool;

    public WebhookDispatchService(WebhookRepository webhookRepository,
                                   WebhookDeliveryRepository deliveryRepository,
                                   ObjectMapper objectMapper,
                                   JedisPool jedisPool) {
        this.webhookRepository = webhookRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.jedisPool = jedisPool;
    }

    /**
     * Dispatch an event to all matching webhook subscriptions.
     * Non-blocking: failures logged but don't propagate.
     */
    public void dispatch(Event event) {
        try {
            List<WebhookSubscription> subs = webhookRepository.findMatchingSubscriptions(
                event.getTenantId(), event.getEventType(), event.getScope());
            for (WebhookSubscription sub : subs) {
                try {
                    createDelivery(event, sub);
                } catch (Exception e) {
                    LOG.error("Failed to create delivery for subscription {}: {}",
                        sub.getSubscriptionId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to dispatch event {}: {}", event.getEventId(), e.getMessage());
        }
    }

    private void createDelivery(Event event, WebhookSubscription sub) {
        WebhookDelivery delivery = WebhookDelivery.builder()
            .deliveryId("del_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
            .subscriptionId(sub.getSubscriptionId())
            .eventId(event.getEventId())
            .eventType(event.getEventType())
            .status(DeliveryStatus.PENDING)
            .attemptedAt(Instant.now())
            .attempts(0)
            .build();
        deliveryRepository.save(delivery);
        // Enqueue for cycles-server-events to pick up via BRPOP
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush("dispatch:pending", delivery.getDeliveryId());
        } catch (Exception e) {
            LOG.warn("Failed to enqueue delivery {} to dispatch:pending: {}", delivery.getDeliveryId(), e.getMessage());
        }
    }

    /**
     * Sign a payload with HMAC-SHA256.
     */
    public String signPayload(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign payload", e);
        }
    }
}
