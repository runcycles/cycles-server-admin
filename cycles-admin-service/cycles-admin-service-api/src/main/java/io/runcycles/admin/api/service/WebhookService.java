package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.*;
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
    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSecurityConfigRepository securityConfigRepository;
    private final WebhookUrlValidator urlValidator;

    public WebhookService(WebhookRepository webhookRepository,
                          WebhookDeliveryRepository deliveryRepository,
                          WebhookSecurityConfigRepository securityConfigRepository,
                          WebhookUrlValidator urlValidator) {
        this.webhookRepository = webhookRepository;
        this.deliveryRepository = deliveryRepository;
        this.securityConfigRepository = securityConfigRepository;
        this.urlValidator = urlValidator;
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
        try {
            // Placeholder: actual HTTP delivery will be in WebhookDispatchService
            int responseStatus = 200; // TODO: actual HTTP call
            long elapsed = System.currentTimeMillis() - start;
            return WebhookTestResponse.builder()
                .success(true)
                .responseStatus(responseStatus)
                .responseTimeMs((int) elapsed)
                .eventId(testEventId)
                .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return WebhookTestResponse.builder()
                .success(false)
                .responseTimeMs((int) elapsed)
                .errorMessage(e.getMessage())
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
        webhookRepository.findById(subscriptionId); // Throws if not found
        // TODO: check for active replay, queue events for re-delivery
        String replayId = "replay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return ReplayResponse.builder()
            .replayId(replayId)
            .eventsQueued(0) // Placeholder
            .estimatedCompletionSeconds(0)
            .build();
    }

    private String generateSigningSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
