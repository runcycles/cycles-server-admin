package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookModelTest {

    private final ObjectMapper mapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    // ---- WebhookSubscription ----

    @Test
    void webhookSubscription_jsonRoundTrip() throws Exception {
        Instant now = Instant.now();
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_abc123")
                .tenantId("t1")
                .name("My Webhook")
                .description("Test webhook")
                .url("https://example.com/hook")
                .eventTypes(List.of(EventType.TENANT_CREATED, EventType.BUDGET_FUNDED))
                .eventCategories(List.of(EventCategory.BUDGET))
                .scopeFilter("org/eng")
                .status(WebhookStatus.ACTIVE)
                .retryPolicy(WebhookRetryPolicy.builder().build())
                .disableAfterFailures(10)
                .consecutiveFailures(0)
                .createdAt(now)
                .headers(Map.of("X-Custom", "value"))
                .build();

        String json = mapper.writeValueAsString(sub);
        WebhookSubscription deserialized = mapper.readValue(json, WebhookSubscription.class);

        assertEquals(sub.getSubscriptionId(), deserialized.getSubscriptionId());
        assertEquals(sub.getTenantId(), deserialized.getTenantId());
        assertEquals(sub.getName(), deserialized.getName());
        assertEquals(sub.getUrl(), deserialized.getUrl());
        assertEquals(sub.getStatus(), deserialized.getStatus());
        assertEquals(2, deserialized.getEventTypes().size());
        assertTrue(json.contains("\"subscription_id\""));
        assertTrue(json.contains("\"tenant_id\""));
        assertTrue(json.contains("\"event_types\""));
        assertTrue(json.contains("\"scope_filter\""));
    }

    @Test
    void webhookSubscription_signingSecretIgnoredInJson() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1")
                .tenantId("t1")
                .url("https://example.com/hook")
                .status(WebhookStatus.ACTIVE)
                .signingSecret("super_secret")
                .consecutiveFailures(0)
                .createdAt(Instant.now())
                .build();

        String json = mapper.writeValueAsString(sub);

        assertFalse(json.contains("super_secret"));
        assertFalse(json.contains("signing_secret"));
    }

    // ---- WebhookCreateRequest ----

    @Test
    void webhookCreateRequest_serializationWithAllFields() throws Exception {
        WebhookCreateRequest req = WebhookCreateRequest.builder()
                .name("Budget Hook")
                .description("Fires on budget events")
                .url("https://example.com/budget-hook")
                .eventTypes(List.of(EventType.BUDGET_CREATED, EventType.BUDGET_FUNDED))
                .eventCategories(List.of(EventCategory.BUDGET))
                .scopeFilter("org/eng")
                .signingSecret("secret123")
                .headers(Map.of("Authorization", "Bearer token"))
                .retryPolicy(WebhookRetryPolicy.builder().maxRetries(3).build())
                .disableAfterFailures(5)
                .metadata(Map.of("team", "platform"))
                .build();

        String json = mapper.writeValueAsString(req);
        WebhookCreateRequest deserialized = mapper.readValue(json, WebhookCreateRequest.class);

        assertEquals(req.getName(), deserialized.getName());
        assertEquals(req.getUrl(), deserialized.getUrl());
        assertEquals(2, deserialized.getEventTypes().size());
        assertEquals("secret123", deserialized.getSigningSecret());
        assertTrue(json.contains("\"event_types\""));
        assertTrue(json.contains("\"scope_filter\""));
        assertTrue(json.contains("\"retry_policy\""));
    }

    // ---- WebhookRetryPolicy ----

    @Test
    void webhookRetryPolicy_defaultValues() {
        WebhookRetryPolicy policy = WebhookRetryPolicy.builder().build();

        assertEquals(5, policy.getMaxRetries());
        assertEquals(1000, policy.getInitialDelayMs());
        assertEquals(2.0, policy.getBackoffMultiplier());
        assertEquals(60000, policy.getMaxDelayMs());
    }

    @Test
    void webhookRetryPolicy_jsonRoundTrip() throws Exception {
        WebhookRetryPolicy policy = WebhookRetryPolicy.builder()
                .maxRetries(3)
                .initialDelayMs(500)
                .backoffMultiplier(1.5)
                .maxDelayMs(30000)
                .build();

        String json = mapper.writeValueAsString(policy);
        WebhookRetryPolicy deserialized = mapper.readValue(json, WebhookRetryPolicy.class);

        assertEquals(3, deserialized.getMaxRetries());
        assertEquals(500, deserialized.getInitialDelayMs());
        assertEquals(1.5, deserialized.getBackoffMultiplier());
        assertEquals(30000, deserialized.getMaxDelayMs());
        assertTrue(json.contains("\"max_retries\""));
        assertTrue(json.contains("\"initial_delay_ms\""));
        assertTrue(json.contains("\"backoff_multiplier\""));
        assertTrue(json.contains("\"max_delay_ms\""));
    }

    // ---- WebhookSecurityConfig ----

    @Test
    void webhookSecurityConfig_defaultBlockedCidrRanges() {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder().build();

        assertNotNull(config.getBlockedCidrRanges());
        assertTrue(config.getBlockedCidrRanges().contains("10.0.0.0/8"));
        assertTrue(config.getBlockedCidrRanges().contains("172.16.0.0/12"));
        assertTrue(config.getBlockedCidrRanges().contains("192.168.0.0/16"));
        assertTrue(config.getBlockedCidrRanges().contains("127.0.0.0/8"));
        assertTrue(config.getBlockedCidrRanges().contains("169.254.0.0/16"));
        assertTrue(config.getBlockedCidrRanges().contains("::1/128"));
        assertTrue(config.getBlockedCidrRanges().contains("fc00::/7"));
        assertEquals(7, config.getBlockedCidrRanges().size());
    }

    @Test
    void webhookSecurityConfig_defaultAllowHttpFalse() {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder().build();

        assertFalse(config.getAllowHttp());
    }

    @Test
    void webhookSecurityConfig_jsonRoundTrip() throws Exception {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder()
                .blockedCidrRanges(List.of("10.0.0.0/8"))
                .allowedUrlPatterns(List.of("https://*.example.com"))
                .allowHttp(true)
                .build();

        String json = mapper.writeValueAsString(config);
        WebhookSecurityConfig deserialized = mapper.readValue(json, WebhookSecurityConfig.class);

        assertEquals(1, deserialized.getBlockedCidrRanges().size());
        assertEquals("https://*.example.com", deserialized.getAllowedUrlPatterns().get(0));
        assertTrue(deserialized.getAllowHttp());
    }

    // ---- WebhookStatus enum ----

    @Test
    void webhookStatus_allValues() {
        WebhookStatus[] values = WebhookStatus.values();
        assertEquals(3, values.length);
        assertNotNull(WebhookStatus.valueOf("ACTIVE"));
        assertNotNull(WebhookStatus.valueOf("PAUSED"));
        assertNotNull(WebhookStatus.valueOf("DISABLED"));
    }

    @Test
    void webhookStatus_serializesCorrectly() throws Exception {
        for (WebhookStatus status : WebhookStatus.values()) {
            String json = mapper.writeValueAsString(status);
            WebhookStatus deserialized = mapper.readValue(json, WebhookStatus.class);
            assertEquals(status, deserialized);
        }
    }

    // ---- DeliveryStatus enum ----

    @Test
    void deliveryStatus_allValues() {
        DeliveryStatus[] values = DeliveryStatus.values();
        assertEquals(4, values.length);
        assertNotNull(DeliveryStatus.valueOf("PENDING"));
        assertNotNull(DeliveryStatus.valueOf("SUCCESS"));
        assertNotNull(DeliveryStatus.valueOf("FAILED"));
        assertNotNull(DeliveryStatus.valueOf("RETRYING"));
    }

    @Test
    void deliveryStatus_serializesCorrectly() throws Exception {
        for (DeliveryStatus status : DeliveryStatus.values()) {
            String json = mapper.writeValueAsString(status);
            DeliveryStatus deserialized = mapper.readValue(json, DeliveryStatus.class);
            assertEquals(status, deserialized);
        }
    }

    // ---- ReplayRequest ----

    @Test
    void replayRequest_serialization() throws Exception {
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2025-01-02T00:00:00Z");

        ReplayRequest request = ReplayRequest.builder()
                .from(from)
                .to(to)
                .eventTypes(List.of(EventType.BUDGET_CREATED, EventType.TENANT_CREATED))
                .maxEvents(50)
                .build();

        String json = mapper.writeValueAsString(request);
        ReplayRequest deserialized = mapper.readValue(json, ReplayRequest.class);

        assertEquals(from, deserialized.getFrom());
        assertEquals(to, deserialized.getTo());
        assertEquals(2, deserialized.getEventTypes().size());
        assertEquals(50, deserialized.getMaxEvents());
    }

    @Test
    void replayRequest_defaultMaxEvents() {
        ReplayRequest request = ReplayRequest.builder().build();
        assertEquals(100, request.getMaxEvents());
    }

    // ---- ReplayResponse ----

    @Test
    void replayResponse_serialization() throws Exception {
        ReplayResponse response = ReplayResponse.builder()
                .replayId("replay_abc123")
                .eventsQueued(42)
                .estimatedCompletionSeconds(30)
                .build();

        String json = mapper.writeValueAsString(response);
        ReplayResponse deserialized = mapper.readValue(json, ReplayResponse.class);

        assertEquals("replay_abc123", deserialized.getReplayId());
        assertEquals(42, deserialized.getEventsQueued());
        assertEquals(30, deserialized.getEstimatedCompletionSeconds());
        assertTrue(json.contains("\"replay_id\""));
        assertTrue(json.contains("\"events_queued\""));
        assertTrue(json.contains("\"estimated_completion_seconds\""));
    }

    // ---- WebhookDelivery ----

    @Test
    void webhookDelivery_jsonRoundTrip() throws Exception {
        Instant now = Instant.now();
        WebhookDelivery delivery = WebhookDelivery.builder()
                .deliveryId("del_abc123")
                .subscriptionId("whsub_1")
                .eventId("evt_1")
                .eventType(EventType.BUDGET_CREATED)
                .status(DeliveryStatus.SUCCESS)
                .attemptedAt(now)
                .completedAt(now)
                .attempts(1)
                .responseStatus(200)
                .responseTimeMs(150)
                .build();

        String json = mapper.writeValueAsString(delivery);
        WebhookDelivery deserialized = mapper.readValue(json, WebhookDelivery.class);

        assertEquals("del_abc123", deserialized.getDeliveryId());
        assertEquals("whsub_1", deserialized.getSubscriptionId());
        assertEquals(EventType.BUDGET_CREATED, deserialized.getEventType());
        assertEquals(DeliveryStatus.SUCCESS, deserialized.getStatus());
        assertEquals(200, deserialized.getResponseStatus());
        assertTrue(json.contains("\"delivery_id\""));
        assertTrue(json.contains("\"subscription_id\""));
        assertTrue(json.contains("\"response_time_ms\""));
    }
}
