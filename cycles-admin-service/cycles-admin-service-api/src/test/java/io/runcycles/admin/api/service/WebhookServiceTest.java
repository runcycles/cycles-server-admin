package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock private WebhookRepository webhookRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private WebhookSecurityConfigRepository securityConfigRepository;
    @Mock private WebhookUrlValidator urlValidator;
    @Mock private WebhookDispatchService dispatchService;
    @Mock private io.runcycles.admin.data.repository.EventRepository eventRepository;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @InjectMocks private WebhookService webhookService;

    private WebhookCreateRequest createRequest() {
        return WebhookCreateRequest.builder()
            .name("Test Webhook")
            .url("https://example.com/webhook")
            .eventTypes(List.of(EventType.BUDGET_CREATED))
            .build();
    }

    private WebhookSubscription buildSubscription(String subId, String tenantId) {
        return WebhookSubscription.builder()
            .subscriptionId(subId)
            .tenantId(tenantId)
            .url("https://example.com/webhook")
            .eventTypes(List.of(EventType.BUDGET_CREATED))
            .signingSecret("whsec_testsecret")
            .headers(Map.of("Authorization", "Bearer token123"))
            .status(WebhookStatus.ACTIVE)
            .consecutiveFailures(0)
            .createdAt(Instant.now())
            .build();
    }

    @Test
    void create_generatesSubscriptionIdAndSigningSecret() {
        WebhookCreateRequest request = createRequest();

        WebhookCreateResponse response = webhookService.create("t1", request);

        assertThat(response.getSubscription().getSubscriptionId()).startsWith("whsub_");
        assertThat(response.getSigningSecret()).startsWith("whsec_");
        verify(urlValidator).validate("https://example.com/webhook");
        verify(webhookRepository).save(any());
    }

    @Test
    void create_usesProvidedSigningSecretIfGiven() {
        WebhookCreateRequest request = createRequest();
        request.setSigningSecret("whsec_custom");

        WebhookCreateResponse response = webhookService.create("t1", request);

        assertThat(response.getSigningSecret()).isEqualTo("whsec_custom");
    }

    @Test
    void create_returnsResponseWithSecret() {
        WebhookCreateRequest request = createRequest();

        WebhookCreateResponse response = webhookService.create("t1", request);

        assertThat(response.getSubscription()).isNotNull();
        assertThat(response.getSigningSecret()).isNotNull();
    }

    @Test
    void get_masksSigningSecretAndHeaders() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        WebhookSubscription result = webhookService.get("whsub_1");

        assertThat(result.getSigningSecret()).isNull();
        assertThat(result.getHeaders()).containsEntry("Authorization", "********");
    }

    @Test
    void get_noHeaders_masksOnlySecret() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        sub.setHeaders(null);
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        WebhookSubscription result = webhookService.get("whsub_1");

        assertThat(result.getSigningSecret()).isNull();
        assertThat(result.getHeaders()).isNull();
    }

    @Test
    void update_partialUpdate_onlyModifiesProvidedFields() {
        WebhookSubscription existing = buildSubscription("whsub_1", "t1");
        existing.setName("Original");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .name("Updated Name")
            .build();

        webhookService.update("whsub_1", request);

        assertThat(existing.getName()).isEqualTo("Updated Name");
        assertThat(existing.getUrl()).isEqualTo("https://example.com/webhook");
        verify(webhookRepository).update(eq("whsub_1"), any());
    }

    @Test
    void update_resetsConsecutiveFailuresOnActiveStatus() {
        WebhookSubscription existing = buildSubscription("whsub_1", "t1");
        existing.setConsecutiveFailures(5);
        existing.setStatus(WebhookStatus.DISABLED);
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .status(WebhookStatus.ACTIVE)
            .build();

        webhookService.update("whsub_1", request);

        assertThat(existing.getConsecutiveFailures()).isEqualTo(0);
    }

    @Test
    void update_rejectsDisabledStatus() {
        WebhookSubscription existing = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .status(WebhookStatus.DISABLED)
            .build();

        assertThatThrownBy(() -> webhookService.update("whsub_1", request))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("DISABLED");
    }

    @Test
    void update_validatesUrlIfProvided() {
        WebhookSubscription existing = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .url("https://new-url.com/webhook")
            .build();

        webhookService.update("whsub_1", request);

        verify(urlValidator).validate("https://new-url.com/webhook");
    }

    @Test
    void delete_throwsIfNotFound() {
        when(webhookRepository.findById("whsub_missing"))
            .thenThrow(GovernanceException.webhookNotFound("whsub_missing"));

        assertThatThrownBy(() -> webhookService.delete("whsub_missing"))
            .isInstanceOf(GovernanceException.class);
    }

    @Test
    void delete_deletesIfFound() {
        WebhookSubscription existing = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        webhookService.delete("whsub_1");

        verify(webhookRepository).delete("whsub_1");
    }

    @Test
    void test_returnsWebhookTestResponse() throws Exception {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.getSigningSecret("whsub_1")).thenReturn("test-secret");
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":true}");

        WebhookTestResponse response = webhookService.test("whsub_1");

        // HTTP call to https://example.com/webhook will fail in unit test (no server)
        // but the response structure should still be valid
        assertThat(response).isNotNull();
        assertThat(response.getEventId()).startsWith("evt_test_");
        assertThat(response.getResponseTimeMs()).isGreaterThanOrEqualTo(0);
        // Will be false because there's no server at example.com, or errorMessage is set
        assertThat(response.isSuccess() || response.getErrorMessage() != null).isTrue();
    }

    @Test
    void listByTenant_delegatesAndMasksSecrets() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.listByTenant("t1", null, null, null, 50))
            .thenReturn(List.of(sub));

        WebhookListResponse response = webhookService.listByTenant("t1", null, null, null, 50);

        assertThat(response.getSubscriptions()).hasSize(1);
        assertThat(response.getSubscriptions().get(0).getSigningSecret()).isNull();
    }

    @Test
    void listAll_withTenantId_delegatesToListByTenant() {
        WebhookSubscription sub1 = buildSubscription("whsub_1", "t1");
        when(webhookRepository.listByTenant("t1", null, null, null, 50))
            .thenReturn(List.of(sub1));

        WebhookListResponse response = webhookService.listAll("t1", null, null, null, 50);

        assertThat(response.getSubscriptions()).hasSize(1);
        assertThat(response.getSubscriptions().get(0).getTenantId()).isEqualTo("t1");
        verify(webhookRepository).listByTenant("t1", null, null, null, 50);
        verify(webhookRepository, never()).listAll(any(), any(), any(), anyInt());
    }

    @Test
    void listAll_noTenantFilter_returnsAll() {
        WebhookSubscription sub1 = buildSubscription("whsub_1", "t1");
        WebhookSubscription sub2 = buildSubscription("whsub_2", "t2");
        when(webhookRepository.listAll(null, null, null, 50))
            .thenReturn(List.of(sub1, sub2));

        WebhookListResponse response = webhookService.listAll(null, null, null, null, 50);

        assertThat(response.getSubscriptions()).hasSize(2);
    }

    @Test
    void listDeliveries_throwsIfSubscriptionNotFound() {
        when(webhookRepository.findById("whsub_missing"))
            .thenThrow(GovernanceException.webhookNotFound("whsub_missing"));

        assertThatThrownBy(() -> webhookService.listDeliveries("whsub_missing", null, null, null, null, 50))
            .isInstanceOf(GovernanceException.class);
    }

    @Test
    void listDeliveries_returnsDeliveries() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        WebhookDelivery delivery = WebhookDelivery.builder()
            .deliveryId("del_1").subscriptionId("whsub_1").eventId("evt_1")
            .status(DeliveryStatus.PENDING).attemptedAt(Instant.now()).build();
        when(deliveryRepository.listBySubscription(eq("whsub_1"), any(), any(), any(), any(), eq(50)))
            .thenReturn(List.of(delivery));

        WebhookDeliveryListResponse response = webhookService.listDeliveries("whsub_1", null, null, null, null, 50);

        assertThat(response.getDeliveries()).hasSize(1);
    }

    @Test
    void replay_noEvents_returnsZeroQueued() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(eventRepository.list(eq("t1"), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of());

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getReplayId()).startsWith("replay_");
        assertThat(response.getEventsQueued()).isEqualTo(0);
    }

    @Test
    void replay_withEvents_queuesDeliveries() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        io.runcycles.admin.model.event.Event event = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(io.runcycles.admin.model.event.EventType.BUDGET_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.BUDGET)
            .tenantId("t1").source("admin").timestamp(Instant.now()).build();
        when(eventRepository.list(eq("t1"), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of(event));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .maxEvents(100)
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService).dispatchToSubscription(event, sub);
    }

    @Test
    void replay_withEventTypeFilter_filtersEvents() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        io.runcycles.admin.model.event.Event budgetEvt = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(io.runcycles.admin.model.event.EventType.BUDGET_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.BUDGET)
            .tenantId("t1").source("admin").timestamp(Instant.now()).build();
        io.runcycles.admin.model.event.Event tenantEvt = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_2").eventType(io.runcycles.admin.model.event.EventType.TENANT_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.TENANT)
            .tenantId("t1").source("admin").timestamp(Instant.now()).build();
        when(eventRepository.list(eq("t1"), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of(budgetEvt, tenantEvt));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .eventTypes(List.of(io.runcycles.admin.model.event.EventType.BUDGET_CREATED))
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService).dispatchToSubscription(budgetEvt, sub);
        verify(dispatchService, never()).dispatchToSubscription(eq(tenantEvt), any());
    }

    @Test
    void replay_systemSubscription_queriesAllTenants() {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_sys").tenantId("__system__")
            .url("https://system.example.com/hook")
            .eventTypes(List.of(io.runcycles.admin.model.event.EventType.BUDGET_CREATED))
            .status(WebhookStatus.ACTIVE).consecutiveFailures(0).disableAfterFailures(10).build();
        when(webhookRepository.findById("whsub_sys")).thenReturn(sub);
        when(eventRepository.list(isNull(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of());

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        webhookService.replay("whsub_sys", request);

        // Should pass null for tenantId to query all tenants
        verify(eventRepository).list(isNull(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void test_withNoSigningSecret_stillWorks() throws Exception {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.getSigningSecret("whsub_1")).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":true}");

        WebhookTestResponse response = webhookService.test("whsub_1");

        assertThat(response).isNotNull();
        assertThat(response.getEventId()).startsWith("evt_test_");
    }

    @Test
    void test_serializationError_returnsFailure() throws Exception {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.getSigningSecret("whsub_1")).thenReturn("secret");
        when(objectMapper.writeValueAsString(any()))
            .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("fail") {});

        WebhookTestResponse response = webhookService.test("whsub_1");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorMessage()).contains("serialization");
        assertThat(response.getEventId()).startsWith("evt_test_");
    }

    @Test
    void replay_withEventTypeFilter_filtersCorrectly() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        io.runcycles.admin.model.event.Event evt1 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(EventType.BUDGET_CREATED).tenantId("t1")
            .timestamp(Instant.now()).build();
        io.runcycles.admin.model.event.Event evt2 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_2").eventType(EventType.TENANT_CREATED).tenantId("t1")
            .timestamp(Instant.now()).build();

        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of(evt1, evt2));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .eventTypes(List.of(EventType.BUDGET_CREATED))
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        // Only evt1 matches the filter, so only 1 should be dispatched
        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService, times(1)).dispatchToSubscription(any(), any());
    }

    @Test
    void replay_dispatchFailure_continuesAndCounts() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        io.runcycles.admin.model.event.Event evt1 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(EventType.BUDGET_CREATED).tenantId("t1")
            .timestamp(Instant.now()).build();
        io.runcycles.admin.model.event.Event evt2 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_2").eventType(EventType.BUDGET_FUNDED).tenantId("t1")
            .timestamp(Instant.now()).build();

        when(eventRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of(evt1, evt2));
        doThrow(new RuntimeException("dispatch error")).when(dispatchService)
            .dispatchToSubscription(eq(evt1), any());
        doNothing().when(dispatchService).dispatchToSubscription(eq(evt2), any());

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        // evt1 failed, evt2 succeeded
        assertThat(response.getEventsQueued()).isEqualTo(1);
    }

    @Test
    void update_statusDisabled_throws() {
        WebhookSubscription existing = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .status(WebhookStatus.DISABLED)
            .build();

        assertThatThrownBy(() -> webhookService.update("whsub_1", request))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("DISABLED");
    }

    @Test
    void update_statusActive_resetsConsecutiveFailures() {
        WebhookSubscription existing = buildSubscription("whsub_1", "t1");
        existing.setConsecutiveFailures(5);
        existing.setStatus(WebhookStatus.PAUSED);
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .status(WebhookStatus.ACTIVE)
            .build();

        // findById is called again in get() after update
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);
        webhookService.update("whsub_1", request);

        // Should reset consecutive failures on re-enable
        verify(webhookRepository).update(eq("whsub_1"), argThat(sub ->
            sub.getConsecutiveFailures() == 0 && sub.getStatus() == WebhookStatus.ACTIVE));
    }

    @Test
    void listAll_withTenantFilter_paginatesCorrectly() {
        // Verifies fix for #57: tenant filter now uses tenant-specific Redis set
        // so hasMore and nextCursor reflect tenant-scoped pagination
        WebhookSubscription sub1 = buildSubscription("whsub_1", "t1");
        WebhookSubscription sub2 = buildSubscription("whsub_2", "t1");
        when(webhookRepository.listByTenant("t1", null, null, null, 2))
            .thenReturn(List.of(sub1, sub2));

        WebhookListResponse response = webhookService.listAll("t1", null, null, null, 2);

        assertThat(response.getSubscriptions()).hasSize(2);
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getNextCursor()).isEqualTo("whsub_2");
        verify(webhookRepository, never()).listAll(any(), any(), any(), anyInt());
    }
}
