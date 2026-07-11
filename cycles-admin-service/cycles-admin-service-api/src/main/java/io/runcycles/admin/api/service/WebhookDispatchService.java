package io.runcycles.admin.api.service;

import static io.runcycles.admin.api.logging.LogSanitizer.safe;

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
    private final WebhookCategoryBoundaryValidator categoryBoundaryValidator;

    public WebhookDispatchService(WebhookRepository webhookRepository,
                                   WebhookDeliveryRepository deliveryRepository,
                                   ObjectMapper objectMapper,
                                   JedisPool jedisPool,
                                   MeterRegistry meterRegistry,
                                   WebhookCategoryBoundaryValidator categoryBoundaryValidator) {
        this.webhookRepository = webhookRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.jedisPool = jedisPool;
        this.meterRegistry = meterRegistry;
        this.categoryBoundaryValidator = categoryBoundaryValidator;
    }

    /**
     * #209 — the DURABLE, fail-closed confidentiality boundary. A
     * concrete-tenant-owned subscription (owner is not {@code __system__}/null,
     * per {@link WebhookSubscription#isSystemOwner}) MUST NOT receive an
     * admin-only event (admin category OR admin type). Enforced at DELIVERY, so
     * the leak is impossible regardless of what selectors are stored on the row
     * or whether the cleanup reconciler has run — the write-path gate stops NEW
     * offenders and the reconciler is best-effort hygiene, but THIS is the
     * guarantee. Per-event: a mixed subscription still receives its
     * tenant-accessible events; only the admin-only ones are skipped.
     */
    private boolean isBlockedByOwnershipBoundary(Event event, WebhookSubscription sub) {
        boolean blocked = !WebhookSubscription.isSystemOwner(sub.getTenantId())
            && categoryBoundaryValidator.isAdminOnly(event.getEventType());
        if (blocked) {
            LOG.debug("Webhook delivery blocked by ownership boundary (#209): subscription_id={} tenant_id={} event_type={} — a concrete-tenant subscription cannot receive admin-only events",
                safe(sub.getSubscriptionId()), safe(sub.getTenantId()),
                event.getEventType() != null ? event.getEventType().getValue() : null);
        }
        return blocked;
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
                // Fail-closed ownership boundary (no I/O). Live dispatch already
                // read fresh status via findMatchingSubscriptions, so no status
                // re-check is needed here (there is no large stale-snapshot window
                // as there is on the replay path).
                if (isBlockedByOwnershipBoundary(event, sub)) {
                    recordDispatch("boundary_skipped");
                    continue;
                }
                try {
                    createDelivery(event, sub);
                    recordDispatch("queued");
                } catch (Exception e) {
                    LOG.error("Failed to create webhook delivery: event_id={} event_type={} tenant_id={} scope={} subscription_id={} correlation_id={} request_id={} trace_id={} error={}",
                            safe(event != null ? event.getEventId() : null),
                            event != null && event.getEventType() != null ? event.getEventType().getValue() : null,
                            safe(event != null ? event.getTenantId() : null),
                            safe(event != null ? event.getScope() : null),
                            safe(sub != null ? sub.getSubscriptionId() : null),
                            safe(event != null ? event.getCorrelationId() : null),
                            safe(event != null ? event.getRequestId() : null),
                            safe(event != null ? event.getTraceId() : null),
                            safe(e.getMessage()), e);
                    recordDispatch("failure");
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to dispatch admin event to webhooks: event_id={} event_type={} tenant_id={} scope={} correlation_id={} request_id={} trace_id={} error={}",
                    safe(event != null ? event.getEventId() : null),
                    event != null && event.getEventType() != null ? event.getEventType().getValue() : null,
                    safe(event != null ? event.getTenantId() : null),
                    safe(event != null ? event.getScope() : null),
                    safe(event != null ? event.getCorrelationId() : null),
                    safe(event != null ? event.getRequestId() : null),
                    safe(event != null ? event.getTraceId() : null),
                    safe(e.getMessage()), e);
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
     * Returns {@code true} if a delivery was enqueued, {@code false} if it was
     * skipped by a delivery guard.
     *
     * <p>Applies BOTH guards, so replay is fail-closed:
     * <ul>
     *   <li>the ownership boundary (a concrete-tenant sub never gets admin-only
     *       events); and</li>
     *   <li>a fresh STATUS re-read at delivery time — replay queues events from a
     *       subscription snapshot captured before the replay lock, so a
     *       subscription disabled (or paused) concurrently must stop receiving
     *       deliveries mid-replay ("disabling stops delivery immediately" under
     *       concurrency, TOCTOU fix).</li>
     * </ul>
     */
    public boolean dispatchToSubscription(Event event, WebhookSubscription sub) {
        if (isBlockedByOwnershipBoundary(event, sub)) {
            return false;
        }
        WebhookSubscription current;
        try {
            current = webhookRepository.findById(sub.getSubscriptionId());
        } catch (Exception e) {
            // Subscription vanished (deleted) mid-replay — skip.
            return false;
        }
        if (current == null || current.getStatus() != WebhookStatus.ACTIVE) {
            LOG.debug("Webhook replay delivery skipped — subscription no longer ACTIVE: subscription_id={} tenant_id={} status={}",
                safe(sub.getSubscriptionId()), safe(sub.getTenantId()),
                current != null ? current.getStatus() : null);
            return false;
        }
        createDelivery(event, sub);
        return true;
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
            LOG.warn("Failed to enqueue webhook delivery: delivery_id={} event_id={} event_type={} subscription_id={} tenant_id={} queue=dispatch:pending trace_id={} error={}",
                    safe(delivery.getDeliveryId()), safe(delivery.getEventId()),
                    delivery.getEventType() != null ? delivery.getEventType().getValue() : null,
                    safe(delivery.getSubscriptionId()), safe(sub.getTenantId()),
                    safe(delivery.getTraceId()), safe(e.getMessage()), e);
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
