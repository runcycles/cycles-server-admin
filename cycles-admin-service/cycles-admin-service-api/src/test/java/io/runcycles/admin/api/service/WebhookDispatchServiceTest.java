package io.runcycles.admin.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
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
    private MeterRegistry meterRegistry;

    private WebhookDispatchService webhookDispatchService;

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        meterRegistry = new SimpleMeterRegistry();
        webhookDispatchService = new WebhookDispatchService(webhookRepository, deliveryRepository, objectMapper, jedisPool, meterRegistry, new WebhookCategoryBoundaryValidator());
    }

    private Event buildEvent() {
        return Event.builder()
            .eventId("evt_1")
            .eventType(EventType.BUDGET_CREATED)
            .tenantId("tenant-1")
            .scope("org/team1")
            .timestamp(Instant.now())
            .build();
    }

    private WebhookSubscription buildSubscription(String subId) {
        return WebhookSubscription.builder()
            .subscriptionId(subId)
            .tenantId("tenant-1")
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
        when(webhookRepository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/team1"))
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
        when(webhookRepository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/team1"))
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
        when(webhookRepository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/team1"))
            .thenReturn(List.of());

        webhookDispatchService.dispatch(event);

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void dispatch_continuesOnIndividualDeliveryFailure() {
        Event event = buildEvent();
        WebhookSubscription sub1 = buildSubscription("whsub_1");
        WebhookSubscription sub2 = buildSubscription("whsub_2");
        when(webhookRepository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/team1"))
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
        when(webhookRepository.findById("whsub_1")).thenReturn(sub); // fresh status re-read (ACTIVE)

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(event, sub);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.ENQUEUED);
        verify(deliveryRepository).save(argThat(d ->
            d.getSubscriptionId().equals("whsub_1") &&
            d.getEventId().equals("evt_1") &&
            d.getStatus() == DeliveryStatus.PENDING));
        verify(jedis).lpush(eq("dispatch:pending"), anyString());
    }

    // ---- #209 fail-closed ownership boundary (live dispatch + replay) ----

    private Event adminEvent() {
        return Event.builder().eventId("evt_admin").eventType(EventType.API_KEY_CREATED)
            .tenantId("tenant-1").scope(null).timestamp(Instant.now()).build();
    }

    private WebhookSubscription sub(String id, String tenantId, WebhookStatus status) {
        return WebhookSubscription.builder().subscriptionId(id).tenantId(tenantId)
            .url("https://example.com/wh").eventTypes(List.of(EventType.BUDGET_CREATED))
            .eventCategories(List.of(EventCategory.API_KEY)) // offender-style: subscribes to admin cat
            .status(status).createdAt(Instant.now()).build();
    }

    @Test
    void dispatch_liveConcreteTenantSub_doesNotReceiveAdminEvent_butDoesReceiveTenantEvent() {
        WebhookSubscription concrete = sub("whsub_c", "tenant-1", WebhookStatus.ACTIVE);
        // Admin event to a concrete-tenant sub → boundary-blocked (no delivery).
        when(webhookRepository.findMatchingSubscriptions("tenant-1", EventType.API_KEY_CREATED, null))
            .thenReturn(List.of(concrete));
        webhookDispatchService.dispatch(adminEvent());
        verify(deliveryRepository, never()).save(any());

        // Tenant-accessible event to the same sub → delivered.
        reset(deliveryRepository);
        Event budget = buildEvent(); // BUDGET_CREATED, scope org/team1
        when(webhookRepository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/team1"))
            .thenReturn(List.of(concrete));
        webhookDispatchService.dispatch(budget);
        verify(deliveryRepository).save(any());
    }

    @Test
    void dispatch_liveSystemSub_doesReceiveAdminEvent() {
        WebhookSubscription system = sub("whsub_s", "__system__", WebhookStatus.ACTIVE);
        when(webhookRepository.findMatchingSubscriptions(any(), eq(EventType.API_KEY_CREATED), any()))
            .thenReturn(List.of(system));

        webhookDispatchService.dispatch(adminEvent());

        verify(deliveryRepository).save(any()); // system subs CAN receive admin events
    }

    @Test
    void dispatchToSubscription_concreteTenant_adminEvent_blocked_returnsFalse() {
        WebhookSubscription concrete = sub("whsub_c", "tenant-1", WebhookStatus.ACTIVE);

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(adminEvent(), concrete);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.BLOCKED);
        verify(deliveryRepository, never()).save(any());
        verify(webhookRepository, never()).findById(anyString()); // short-circuits before status re-read
    }

    @Test
    void dispatchToSubscription_systemSub_adminEvent_delivered() {
        WebhookSubscription system = sub("whsub_s", "__system__", WebhookStatus.ACTIVE);
        when(webhookRepository.findById("whsub_s")).thenReturn(system);

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(adminEvent(), system);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.ENQUEUED);
        verify(deliveryRepository).save(any());
    }

    @Test
    void dispatchToSubscription_disabledConcurrently_reReadsStatus_skips() {
        // The INPUT snapshot is ACTIVE; the fresh re-read at dispatch time returns
        // the now-DISABLED row (a concurrent disable). This exercises the re-read
        // gate meaningfully — the decision comes from the re-read, not the stale
        // snapshot. (The durable close under true interleaving lives in the
        // cycles-server-events delivery worker's own status re-check.)
        WebhookSubscription staleActive = buildSubscription("whsub_1"); // ACTIVE snapshot
        assertThat(staleActive.getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        WebhookSubscription nowDisabled = buildSubscription("whsub_1");
        nowDisabled.setStatus(WebhookStatus.DISABLED);
        when(webhookRepository.findById("whsub_1")).thenReturn(nowDisabled);

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(buildEvent(), staleActive);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.INACTIVE);
        verify(webhookRepository).findById("whsub_1"); // the re-read actually happened
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void dispatchToSubscription_subscriptionDeletedMidReplay_inactive() {
        // Genuinely deleted → findById throws WEBHOOK_NOT_FOUND → INACTIVE
        // (intended lifecycle change).
        WebhookSubscription stale = buildSubscription("whsub_gone");
        when(webhookRepository.findById("whsub_gone"))
            .thenThrow(io.runcycles.admin.data.exception.GovernanceException.webhookNotFound("whsub_gone"));

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(buildEvent(), stale);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.INACTIVE);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void dispatchToSubscription_reReadBackendError_enqueueFailed_notInactive() {
        // #209 P2: a Redis/deserialization failure during the dispatch-time
        // re-read is a real backend DEGRADATION — it must classify as
        // ENQUEUE_FAILED (→ degraded WARN), NOT be hidden as INACTIVE.
        WebhookSubscription stale = buildSubscription("whsub_1");
        when(webhookRepository.findById("whsub_1"))
            .thenThrow(new RuntimeException("Failed to find webhook subscription",
                new redis.clients.jedis.exceptions.JedisConnectionException("redis down")));

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(buildEvent(), stale);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.ENQUEUE_FAILED);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void dispatchToSubscription_reReadNonNotFoundGovernanceError_enqueueFailed() {
        // A governance exception that is NOT WEBHOOK_NOT_FOUND is unexpected →
        // treated as degradation, not a benign lifecycle change.
        WebhookSubscription stale = buildSubscription("whsub_1");
        when(webhookRepository.findById("whsub_1"))
            .thenThrow(new io.runcycles.admin.data.exception.GovernanceException(
                io.runcycles.admin.model.shared.ErrorCode.INTERNAL_ERROR, "boom", 500));

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(buildEvent(), stale);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.ENQUEUE_FAILED);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void dispatchToSubscription_nullOwner_treatedAsSystem_adminEvent_delivered() {
        // Null-owner classifies as system (null-safe) → admin event allowed; no NPE.
        WebhookSubscription nullOwner = WebhookSubscription.builder()
            .subscriptionId("whsub_null").tenantId(null).url("https://example.com/wh")
            .eventTypes(List.of(EventType.API_KEY_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        when(webhookRepository.findById("whsub_null")).thenReturn(nullOwner);

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(adminEvent(), nullOwner);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.ENQUEUED);
        verify(deliveryRepository).save(any());
    }

    // #209 finding 1: the boundary checks the (independent) CATEGORY too, and
    // fail-closes an unclassifiable record — not only the event TYPE.
    @Test
    void dispatchToSubscription_concreteTenant_tenantTypeButAdminCategory_blocked() {
        WebhookSubscription concrete = sub("whsub_c", "tenant-1", WebhookStatus.ACTIVE);
        // Inconsistent record: tenant-accessible TYPE, admin-only CATEGORY.
        Event inconsistent = Event.builder().eventId("evt_incon")
            .eventType(EventType.BUDGET_CREATED).category(EventCategory.API_KEY)
            .tenantId("tenant-1").timestamp(Instant.now()).build();

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(inconsistent, concrete);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.BLOCKED);
        verify(deliveryRepository, never()).save(any());
        verify(webhookRepository, never()).findById(anyString()); // short-circuits
    }

    @Test
    void dispatchToSubscription_concreteTenant_unclassifiableEvent_blocked() {
        WebhookSubscription concrete = sub("whsub_c", "tenant-1", WebhookStatus.ACTIVE);
        Event unclassifiable = Event.builder().eventId("evt_null")
            .eventType(null).category(null)
            .tenantId("tenant-1").timestamp(Instant.now()).build();

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(unclassifiable, concrete);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.BLOCKED);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void dispatchToSubscription_systemSub_inconsistentEvent_stillDelivered() {
        WebhookSubscription system = sub("whsub_s", "__system__", WebhookStatus.ACTIVE);
        when(webhookRepository.findById("whsub_s")).thenReturn(system);
        Event inconsistent = Event.builder().eventId("evt_incon")
            .eventType(EventType.BUDGET_CREATED).category(EventCategory.API_KEY)
            .tenantId("tenant-1").timestamp(Instant.now()).build();

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(inconsistent, system);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.ENQUEUED); // system-owned: boundary does not apply
        verify(deliveryRepository).save(any());
    }

    // #209 finding 4: an honest boolean — a saved-but-not-enqueued delivery
    // (LPUSH failed) returns false so replay's eventsQueued does not over-report.
    @Test
    void dispatchToSubscription_enqueueFails_returnsFalse_evenThoughSaved() {
        WebhookSubscription sub = buildSubscription("whsub_1"); // ACTIVE, tenant-accessible event
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(jedis.lpush(eq("dispatch:pending"), anyString()))
            .thenThrow(new RuntimeException("redis down"));

        WebhookDispatchService.DispatchOutcome outcome = webhookDispatchService.dispatchToSubscription(buildEvent(), sub);

        assertThat(outcome).isEqualTo(WebhookDispatchService.DispatchOutcome.ENQUEUE_FAILED);       // not enqueued → honest false
        verify(deliveryRepository).save(any()); // row still persisted
    }

    @Test
    void dispatch_success_incrementsQueuedCounter() {
        Event event = buildEvent();
        WebhookSubscription sub = buildSubscription("whsub_1");
        when(webhookRepository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/team1"))
            .thenReturn(List.of(sub));

        webhookDispatchService.dispatch(event);

        assertThat(meterRegistry.counter("cycles_admin_webhook_dispatched_total", "result", "queued").count())
            .isEqualTo(1.0);
    }

    @Test
    void dispatch_failure_incrementsFailureCounter() {
        Event event = buildEvent();
        when(webhookRepository.findMatchingSubscriptions(any(), any(), any()))
            .thenThrow(new RuntimeException("DB error"));

        webhookDispatchService.dispatch(event);

        assertThat(meterRegistry.counter("cycles_admin_webhook_dispatched_total", "result", "failure").count())
            .isEqualTo(1.0);
    }
}
