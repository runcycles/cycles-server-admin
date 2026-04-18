package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
    private final MeterRegistry meterRegistry;

    public WebhookDispatchService(WebhookRepository webhookRepository,
                                   WebhookDeliveryRepository deliveryRepository,
                                   ObjectMapper objectMapper,
                                   JedisPool jedisPool,
                                   MeterRegistry meterRegistry) {
        this.webhookRepository = webhookRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.jedisPool = jedisPool;
        this.meterRegistry = meterRegistry;
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
                    recordDispatch("queued");
                } catch (Exception e) {
                    LOG.error("Failed to create delivery for subscription {}: {}",
                        sub.getSubscriptionId(), e.getMessage());
                    recordDispatch("failure");
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to dispatch event {}: {}", event.getEventId(), e.getMessage());
            recordDispatch("failure");
        }
    }

    private void recordDispatch(String result) {
        Counter.builder("cycles_admin_webhook_dispatched_total")
            .description("Count of webhook delivery enqueue attempts, labelled by result")
            .tag("result", result)
            .register(meterRegistry)
            .increment();
    }

    /**
     * Dispatch a single event to a specific subscription (used by replay).
     */
    public void dispatchToSubscription(Event event, WebhookSubscription sub) {
        createDelivery(event, sub);
    }

    private void createDelivery(Event event, WebhookSubscription sub) {
        TraceSnapshot trace = currentTraceSnapshot(event);
        WebhookDelivery delivery = WebhookDelivery.builder()
            .deliveryId("del_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
            .subscriptionId(sub.getSubscriptionId())
            .eventId(event.getEventId())
            .eventType(event.getEventType())
            .status(DeliveryStatus.PENDING)
            .attemptedAt(Instant.now())
            .attempts(0)
            .traceId(trace.traceId)
            .traceFlags(trace.traceFlags)
            .traceparentInboundValid(trace.traceparentInboundValid)
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
     * Captures the inbound trace context for outbound webhook delivery. Prefers
     * the current request's attributes (set by {@link TraceContextFilter}) so
     * trace-flags preservation works per cycles-protocol-v0 §CORRELATION AND
     * TRACING. Falls back to {@code event.trace_id} when the emit happened
     * off-request — in that case the sidecar defaults trace-flags to {@code 01}.
     */
    private TraceSnapshot currentTraceSnapshot(Event event) {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                Object traceId = sra.getRequest().getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE);
                Object flags = sra.getRequest().getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE);
                Object valid = sra.getRequest().getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE);
                return new TraceSnapshot(
                    traceId != null ? traceId.toString() : event.getTraceId(),
                    flags != null ? flags.toString() : null,
                    valid instanceof Boolean ? (Boolean) valid : null);
            }
        } catch (IllegalStateException ignored) {
            // No active request — fall through.
        }
        return new TraceSnapshot(event.getTraceId(), null, null);
    }

    private record TraceSnapshot(String traceId, String traceFlags, Boolean traceparentInboundValid) {}

    /**
     * Sign a payload with HMAC-SHA256. Returns "sha256=<hex>" format per v0.1.25 spec.
     * Used by the /test endpoint; runtime delivery signing is done by cycles-server-events.
     */
    public String signPayload(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign payload", e);
        }
    }
}
