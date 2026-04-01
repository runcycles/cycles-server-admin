package io.runcycles.admin.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.DeliveryStatus;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatchServiceTest {

    @Mock private WebhookRepository webhookRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    private WebhookDispatchService webhookDispatchService;

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        webhookDispatchService = new WebhookDispatchService(webhookRepository, deliveryRepository, objectMapper, jedisPool);
    }

    private Event buildEvent() {
        return Event.builder()
            .eventId("evt_1")
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("t1")
            .scope("org/team1")
            .timestamp(Instant.now())
            .build();
    }

    private WebhookSubscription buildSubscription(String subId) {
        return WebhookSubscription.builder()
            .subscriptionId(subId)
            .tenantId("t1")
            .url("https://example.com/webhook")
            .eventTypes(List.of(EventType.BUDGET_CREATED))
            .signingSecret("whsec_test")
            .status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now())
            .build();
    }

    @Test
    void dispatch_findsMatchingSubscriptionsAndCreatesDelivery() {
        Event event = buildEvent();
        WebhookSubscription sub = buildSubscription("whsub_1");
        when(webhookRepository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "org/team1"))
            .thenReturn(List.of(sub));

        webhookDispatchService.dispatch(event);

        verify(deliveryRepository).save(argThat(delivery ->
            "whsub_1".equals(delivery.getSubscriptionId()) &&
            "evt_1".equals(delivery.getEventId())));
    }

    @Test
    void dispatch_createsDeliveryForEachMatchingSubscription() {
        Event event = buildEvent();
        WebhookSubscription sub1 = buildSubscription("whsub_1");
        WebhookSubscription sub2 = buildSubscription("whsub_2");
        when(webhookRepository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "org/team1"))
            .thenReturn(List.of(sub1, sub2));

        webhookDispatchService.dispatch(event);

        verify(deliveryRepository, times(2)).save(any());
    }

    @Test
    void dispatch_doesNotThrowOnFailure() {
        Event event = buildEvent();
        when(webhookRepository.findMatchingSubscriptions(any(), any(), any()))
            .thenThrow(new RuntimeException("DB error"));

        // Should not throw
        webhookDispatchService.dispatch(event);
    }

    @Test
    void dispatch_handlesEmptySubscriptionList() {
        Event event = buildEvent();
        when(webhookRepository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "org/team1"))
            .thenReturn(List.of());

        webhookDispatchService.dispatch(event);

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void dispatch_continuesOnIndividualDeliveryFailure() {
        Event event = buildEvent();
        WebhookSubscription sub1 = buildSubscription("whsub_1");
        WebhookSubscription sub2 = buildSubscription("whsub_2");
        when(webhookRepository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "org/team1"))
            .thenReturn(List.of(sub1, sub2));
        doThrow(new RuntimeException("Delivery failed")).when(deliveryRepository).save(argThat(d ->
            "whsub_1".equals(d.getSubscriptionId())));

        // Should not throw, should still attempt second delivery
        webhookDispatchService.dispatch(event);

        verify(deliveryRepository, times(2)).save(any());
    }

    @Test
    void signPayload_producesConsistentHmacSha256Output() {
        String payload = "{\"test\":\"data\"}";
        String secret = "test-secret";

        String sig1 = webhookDispatchService.signPayload(payload, secret);
        String sig2 = webhookDispatchService.signPayload(payload, secret);

        assertThat(sig1).isNotBlank();
        assertThat(sig1).startsWith("sha256=");
        assertThat(sig1).hasSize(7 + 64); // "sha256=" + 64 hex chars
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    void signPayload_differentPayloadsProduceDifferentSignatures() {
        String secret = "test-secret";

        String sig1 = webhookDispatchService.signPayload("payload1", secret);
        String sig2 = webhookDispatchService.signPayload("payload2", secret);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void dispatchToSubscription_createsDeliveryAndEnqueues() {
        Event event = buildEvent();
        WebhookSubscription sub = buildSubscription("whsub_1");

        webhookDispatchService.dispatchToSubscription(event, sub);

        verify(deliveryRepository).save(argThat(d ->
            d.getSubscriptionId().equals("whsub_1") &&
            d.getEventId().equals("evt_1") &&
            d.getStatus() == DeliveryStatus.PENDING));
        verify(jedis).lpush(eq("dispatch:pending"), anyString());
    }
}
