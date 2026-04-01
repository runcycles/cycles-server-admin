package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.*;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.webhook.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class WebhookService {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build();
    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSecurityConfigRepository securityConfigRepository;
    private final WebhookUrlValidator urlValidator;
    private final WebhookDispatchService dispatchService;
    private final EventRepository eventRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public WebhookService(WebhookRepository webhookRepository,
                          WebhookDeliveryRepository deliveryRepository,
                          WebhookSecurityConfigRepository securityConfigRepository,
                          WebhookUrlValidator urlValidator,
                          WebhookDispatchService dispatchService,
                          EventRepository eventRepository,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.webhookRepository = webhookRepository;
        this.deliveryRepository = deliveryRepository;
        this.securityConfigRepository = securityConfigRepository;
        this.urlValidator = urlValidator;
        this.dispatchService = dispatchService;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    public WebhookCreateResponse create(String tenantId, WebhookCreateRequest request) {
        urlValidator.validate(request.getUrl());
        String signingSecret = request.getSigningSecret();
        if (signingSecret == null || signingSecret.isBlank()) {
            signingSecret = generateSigningSecret();
        }
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
            .tenantId(tenantId)
            .name(request.getName())
            .description(request.getDescription())
            .url(request.getUrl())
            .eventTypes(request.getEventTypes())
            .eventCategories(request.getEventCategories())
            .scopeFilter(request.getScopeFilter())
            .thresholds(request.getThresholds())
            .signingSecret(signingSecret)
            .headers(request.getHeaders())
            .status(WebhookStatus.ACTIVE)
            .retryPolicy(request.getRetryPolicy() != null ? request.getRetryPolicy() : WebhookRetryPolicy.builder().build())
            .disableAfterFailures(request.getDisableAfterFailures() != null ? request.getDisableAfterFailures() : 10)
            .consecutiveFailures(0)
            .createdAt(Instant.now())
            .metadata(request.getMetadata())
            .build();
        webhookRepository.save(sub);
        return WebhookCreateResponse.builder()
            .subscription(sub)
            .signingSecret(signingSecret)
            .build();
    }

    public WebhookSubscription get(String subscriptionId) {
        WebhookSubscription sub = webhookRepository.findById(subscriptionId);
        // Mask sensitive fields for GET responses
        sub.setSigningSecret(null);
        if (sub.getHeaders() != null) {
            Map<String, String> masked = new LinkedHashMap<>();
            sub.getHeaders().forEach((k, v) -> masked.put(k, "********"));
            sub.setHeaders(masked);
        }
        return sub;
    }

    public WebhookSubscription update(String subscriptionId, WebhookUpdateRequest request) {
        WebhookSubscription existing = webhookRepository.findById(subscriptionId);
        if (request.getUrl() != null) {
            urlValidator.validate(request.getUrl());
            existing.setUrl(request.getUrl());
        }
        if (request.getName() != null) existing.setName(request.getName());
        if (request.getDescription() != null) existing.setDescription(request.getDescription());
        if (request.getEventTypes() != null) existing.setEventTypes(request.getEventTypes());
        if (request.getEventCategories() != null) existing.setEventCategories(request.getEventCategories());
        if (request.getScopeFilter() != null) existing.setScopeFilter(request.getScopeFilter());
        if (request.getThresholds() != null) existing.setThresholds(request.getThresholds());
        if (request.getSigningSecret() != null) existing.setSigningSecret(request.getSigningSecret());
        if (request.getHeaders() != null) existing.setHeaders(request.getHeaders());
        if (request.getRetryPolicy() != null) existing.setRetryPolicy(request.getRetryPolicy());
        if (request.getDisableAfterFailures() != null) existing.setDisableAfterFailures(request.getDisableAfterFailures());
        if (request.getMetadata() != null) existing.setMetadata(request.getMetadata());
        if (request.getStatus() != null) {
            // Spec restricts update status to ACTIVE or PAUSED only; DISABLED is system-managed
            if (request.getStatus() == WebhookStatus.DISABLED) {
                throw new GovernanceException(
                    ErrorCode.INVALID_REQUEST,
                    "Cannot set status to DISABLED via update; only ACTIVE and PAUSED are allowed", 400);
            }
            existing.setStatus(request.getStatus());
            if (request.getStatus() == WebhookStatus.ACTIVE) {
                existing.setConsecutiveFailures(0); // Reset on re-enable
            }
        }
        existing.setUpdatedAt(Instant.now());
        webhookRepository.update(subscriptionId, existing);
        return get(subscriptionId); // Return masked version
    }

    public void delete(String subscriptionId) {
        webhookRepository.findById(subscriptionId); // Throws if not found
        webhookRepository.delete(subscriptionId);
    }

    public WebhookTestResponse test(String subscriptionId) {
        WebhookSubscription sub = webhookRepository.findById(subscriptionId);
        String secret = webhookRepository.getSigningSecret(subscriptionId);
        String testEventId = "evt_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Event testEvent = Event.builder()
            .eventId(testEventId)
            .eventType(EventType.SYSTEM_WEBHOOK_TEST)
            .category(EventCategory.SYSTEM)
            .timestamp(Instant.now())
            .tenantId(sub.getTenantId())
            .source("cycles-admin")
            .data(Map.of("subscription_id", subscriptionId, "test", true))
            .build();
        long start = System.currentTimeMillis();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(testEvent);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.error("Failed to serialize test event for {}: {}", subscriptionId, e.getMessage());
            return WebhookTestResponse.builder()
                .success(false)
                .responseTimeMs(0)
                .errorMessage("Internal error: event serialization failed")
                .eventId(testEventId)
                .build();
        }
        try {
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(sub.getUrl()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "cycles-server-admin/test")
                .header("X-Cycles-Event-Id", testEventId)
                .header("X-Cycles-Event-Type", testEvent.getEventType().getValue())
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload));
            if (secret != null && !secret.isBlank()) {
                reqBuilder.header("X-Cycles-Signature", dispatchService.signPayload(payload, secret));
            }
            java.net.http.HttpResponse<String> response = HTTP_CLIENT
                .send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            int elapsed = (int) (System.currentTimeMillis() - start);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            return WebhookTestResponse.builder()
                .success(success)
                .responseStatus(response.statusCode())
                .responseTimeMs(elapsed)
                .eventId(testEventId)
                .errorMessage(success ? null : "HTTP " + response.statusCode())
                .build();
        } catch (Exception e) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            LOG.warn("Webhook test delivery failed for {}: {}", subscriptionId, e.getMessage());
            // Sanitize error message to avoid leaking internal details (hostnames, IPs)
            String safeMessage = e instanceof java.net.http.HttpConnectTimeoutException ? "Connection timed out"
                : e instanceof java.net.http.HttpTimeoutException ? "Request timed out"
                : e instanceof java.net.ConnectException ? "Connection refused"
                : "Delivery failed";
            return WebhookTestResponse.builder()
                .success(false)
                .responseTimeMs(elapsed)
                .errorMessage(safeMessage)
                .eventId(testEventId)
                .build();
        }
    }

    public WebhookListResponse listByTenant(String tenantId, String status, String eventType,
                                             String cursor, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        List<WebhookSubscription> subs = webhookRepository.listByTenant(tenantId, status, eventType, cursor, effectiveLimit);
        subs.forEach(s -> { s.setSigningSecret(null); }); // Mask secrets
        return WebhookListResponse.builder()
            .subscriptions(subs)
            .hasMore(subs.size() >= effectiveLimit)
            .nextCursor(subs.size() >= effectiveLimit ? subs.get(subs.size() - 1).getSubscriptionId() : null)
            .build();
    }

    public WebhookListResponse listAll(String tenantId, String status, String eventType,
                                        String cursor, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        List<WebhookSubscription> subs = webhookRepository.listAll(status, eventType, cursor, effectiveLimit);
        if (tenantId != null) {
            subs = subs.stream().filter(s -> tenantId.equals(s.getTenantId())).toList();
        }
        subs.forEach(s -> { s.setSigningSecret(null); });
        return WebhookListResponse.builder()
            .subscriptions(subs)
            .hasMore(subs.size() >= effectiveLimit)
            .nextCursor(subs.size() >= effectiveLimit ? subs.get(subs.size() - 1).getSubscriptionId() : null)
            .build();
    }

    public WebhookDeliveryListResponse listDeliveries(String subscriptionId, String status,
                                                       Instant from, Instant to, String cursor, int limit) {
        webhookRepository.findById(subscriptionId); // Throws if not found
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        List<WebhookDelivery> deliveries = deliveryRepository.listBySubscription(subscriptionId, status, from, to, cursor, effectiveLimit);
        return WebhookDeliveryListResponse.builder()
            .deliveries(deliveries)
            .hasMore(deliveries.size() >= effectiveLimit)
            .nextCursor(deliveries.size() >= effectiveLimit ? deliveries.get(deliveries.size() - 1).getDeliveryId() : null)
            .build();
    }

    public ReplayResponse replay(String subscriptionId, ReplayRequest request) {
        WebhookSubscription sub = webhookRepository.findById(subscriptionId);
        String replayId = "replay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int maxEvents = request.getMaxEvents() != null ? Math.min(request.getMaxEvents(), 1000) : 100;
        // Query events in the requested time range
        List<Event> events = eventRepository.list(
            sub.getTenantId().equals("__system__") ? null : sub.getTenantId(),
            null, null, null, null,
            request.getFrom(), request.getTo(), null, maxEvents);
        // Filter by event types if specified in replay request
        if (request.getEventTypes() != null && !request.getEventTypes().isEmpty()) {
            events = events.stream()
                .filter(e -> request.getEventTypes().contains(e.getEventType()))
                .toList();
        }
        // Queue each matching event for re-delivery to this subscription
        int queued = 0;
        for (Event event : events) {
            try {
                dispatchService.dispatchToSubscription(event, sub);
                queued++;
            } catch (Exception e) {
                LOG.warn("Failed to queue replay delivery for event {}: {}", event.getEventId(), e.getMessage());
            }
        }
        return ReplayResponse.builder()
            .replayId(replayId)
            .eventsQueued(queued)
            .estimatedCompletionSeconds(queued > 0 ? Math.max(1, queued / 10) : 0)
            .build();
    }

    private String generateSigningSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
