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
import java.util.ArrayList;
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

    @org.junit.jupiter.api.BeforeEach
    void dispatchDeliversByDefault() {
        // dispatchToSubscription now returns whether a delivery was enqueued;
        // default to true so replay-counting tests behave as before (a guard
        // that skips delivery is exercised explicitly where relevant).
        org.mockito.Mockito.lenient()
            .when(dispatchService.dispatchToSubscription(any(), any())).thenReturn(true);
    }

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

        WebhookCreateResponse response = webhookService.create("tenant-1", request);

        assertThat(response.getSubscription().getSubscriptionId()).startsWith("whsub_");
        assertThat(response.getSigningSecret()).startsWith("whsec_");
        verify(urlValidator).validate("https://example.com/webhook");
        verify(webhookRepository).save(any());
    }

    @Test
    void create_usesProvidedSigningSecretIfGiven() {
        WebhookCreateRequest request = createRequest();
        request.setSigningSecret("whsec_custom");

        WebhookCreateResponse response = webhookService.create("tenant-1", request);

        assertThat(response.getSigningSecret()).isEqualTo("whsec_custom");
    }

    @Test
    void create_returnsResponseWithSecret() {
        WebhookCreateRequest request = createRequest();

        WebhookCreateResponse response = webhookService.create("tenant-1", request);

        assertThat(response.getSubscription()).isNotNull();
        assertThat(response.getSigningSecret()).isNotNull();
    }

    @Test
    void get_masksSigningSecretAndHeaders() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        WebhookSubscription result = webhookService.get("whsub_1");

        assertThat(result.getSigningSecret()).isNull();
        assertThat(result.getHeaders()).containsEntry("Authorization", "********");
    }

    @Test
    void get_noHeaders_masksOnlySecret() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        sub.setHeaders(null);
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        WebhookSubscription result = webhookService.get("whsub_1");

        assertThat(result.getSigningSecret()).isNull();
        assertThat(result.getHeaders()).isNull();
    }

    @Test
    void update_partialUpdate_onlyModifiesProvidedFields() {
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
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
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
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
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .status(WebhookStatus.DISABLED)
            .build();

        assertThatThrownBy(() -> webhookService.update("whsub_1", request))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("DISABLED");
    }

    // v0.1.25.50 (governance revision v0.1.25.38): a subscription must always
    // name at least one event_type or event_category. Create enforces
    // @NotEmpty event_types, and WebhookRepository.matchesEventType treats
    // empty-both as match-ALL - not creatable, so it must not be reachable
    // via PATCH on any plane either.
    @Test
    void update_clearingEventTypesWithNoCategories_throws400() {
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .eventTypes(List.of())
            .build();

        assertThatThrownBy(() -> webhookService.update("whsub_1", request))
            .isInstanceOf(GovernanceException.class)
            .hasFieldOrPropertyWithValue("httpStatus", 400)
            .hasMessageContaining("at least one event_type or event_category");
        verify(webhookRepository, never()).update(anyString(), any());
    }

    @Test
    void update_clearingBothEventFieldsInOneRequest_throws400() {
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
        existing.setEventCategories(List.of(io.runcycles.admin.model.event.EventCategory.BUDGET));
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .eventTypes(List.of())
            .eventCategories(List.of())
            .build();

        assertThatThrownBy(() -> webhookService.update("whsub_1", request))
            .isInstanceOf(GovernanceException.class)
            .hasFieldOrPropertyWithValue("httpStatus", 400);
        verify(webhookRepository, never()).update(anyString(), any());
    }

    @Test
    void update_clearingEventCategoriesWithEventTypesPresent_succeeds() {
        // The realistic repair path for a smuggled ADMIN_CATEGORIES row:
        // strip event_categories to [] (event_types omitted, so it survives) -
        // event_types remains, so it is NOT empty-both and the update proceeds.
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
        existing.setEventCategories(List.of(io.runcycles.admin.model.event.EventCategory.API_KEY));
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .eventCategories(List.of())
            .build();

        webhookService.update("whsub_1", request);

        assertThat(existing.getEventCategories()).isEmpty();
        assertThat(existing.getEventTypes()).isNotEmpty();
        verify(webhookRepository).update(eq("whsub_1"), any());
    }

    @Test
    void update_clearingEventTypesWithCategoriesPresent_succeeds() {
        // Category-only subscriptions remain legal: clearing event_types is
        // fine as long as at least one event_category survives.
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
        existing.setEventCategories(List.of(io.runcycles.admin.model.event.EventCategory.BUDGET));
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        WebhookUpdateRequest request = WebhookUpdateRequest.builder()
            .eventTypes(List.of())
            .build();

        webhookService.update("whsub_1", request);

        verify(webhookRepository).update(eq("whsub_1"), any());
    }

    @Test
    void update_validatesUrlIfProvided() {
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
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
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(existing);

        webhookService.delete("whsub_1");

        verify(webhookRepository).delete("whsub_1");
    }

    @Test
    void test_returnsWebhookTestResponse() throws Exception {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
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
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.listByTenant(eq("tenant-1"), isNull(), isNull(), isNull(), eq(50), any(), any()))
            .thenReturn(List.of(sub));

        WebhookListResponse response = webhookService.listByTenant("tenant-1", null, null, null, 50);

        assertThat(response.getSubscriptions()).hasSize(1);
        assertThat(response.getSubscriptions().get(0).getSigningSecret()).isNull();
    }

    @Test
    void listAll_withTenantId_delegatesToListByTenant() {
        WebhookSubscription sub1 = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.listByTenant(eq("tenant-1"), isNull(), isNull(), isNull(), eq(50), any(), any()))
            .thenReturn(List.of(sub1));

        WebhookListResponse response = webhookService.listAll("tenant-1", null, null, null, 50);

        assertThat(response.getSubscriptions()).hasSize(1);
        assertThat(response.getSubscriptions().get(0).getTenantId()).isEqualTo("tenant-1");
        verify(webhookRepository).listByTenant(eq("tenant-1"), isNull(), isNull(), isNull(), eq(50), any(), any());
        verify(webhookRepository, never()).listAll(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void listAll_noTenantFilter_returnsAll() {
        WebhookSubscription sub1 = buildSubscription("whsub_1", "tenant-1");
        WebhookSubscription sub2 = buildSubscription("whsub_2", "tenant-2");
        when(webhookRepository.listAll(isNull(), isNull(), isNull(), eq(50), any(), any()))
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
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
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
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        stubReplayWindow(List.of());

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
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        io.runcycles.admin.model.event.Event event = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(io.runcycles.admin.model.event.EventType.BUDGET_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.BUDGET)
            .tenantId("tenant-1").source("admin").timestamp(Instant.now()).build();
        stubReplayWindow(List.of(event));

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
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        io.runcycles.admin.model.event.Event budgetEvt = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(io.runcycles.admin.model.event.EventType.BUDGET_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.BUDGET)
            .tenantId("tenant-1").source("admin").timestamp(Instant.now()).build();
        io.runcycles.admin.model.event.Event tenantEvt = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_2").eventType(io.runcycles.admin.model.event.EventType.TENANT_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.TENANT)
            .tenantId("tenant-1").source("admin").timestamp(Instant.now()).build();
        stubReplayWindow(List.of(budgetEvt, tenantEvt));

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

    // #209 finding 1: replay MUST intersect with the SUBSCRIPTION's own
    // event_types/event_categories (spec replayEvents: "all event types the
    // subscription is subscribed to"), or a concrete-tenant budget-only
    // subscription could replay historical ADMIN-only events to its endpoint.
    @Test
    void replay_doesNotDeliverEventsOutsideSubscriptionSelectors() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1"); // subscribed to BUDGET_CREATED only
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        io.runcycles.admin.model.event.Event budgetEvt = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_ok").eventType(io.runcycles.admin.model.event.EventType.BUDGET_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.BUDGET)
            .tenantId("tenant-1").source("admin").timestamp(Instant.now()).build();
        io.runcycles.admin.model.event.Event adminEvt = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_admin").eventType(io.runcycles.admin.model.event.EventType.API_KEY_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.API_KEY)
            .tenantId("tenant-1").source("admin").timestamp(Instant.now()).build();
        stubReplayWindow(List.of(budgetEvt, adminEvt));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600)).to(Instant.now()).build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService).dispatchToSubscription(budgetEvt, sub);
        // The admin-only event is NOT in the subscription's selectors → not delivered.
        verify(dispatchService, never()).dispatchToSubscription(eq(adminEvt), any());
    }

    private io.runcycles.admin.model.event.Event evt(String id,
            io.runcycles.admin.model.event.EventType type,
            io.runcycles.admin.model.event.EventCategory cat) {
        return io.runcycles.admin.model.event.Event.builder()
            .eventId(id).eventType(type).category(cat)
            .tenantId("tenant-1").source("admin").timestamp(Instant.now()).build();
    }

    /**
     * Approach-B replay stubbing: the collector reads a bounded ordered id list
     * ({@code listEventIdsInRange}) then hydrates batches ({@code hydrateByIds}).
     * These unit tests supply a single in-order window (all filter/ordering/cap
     * logic in the collector is exercised); the real ZRANGEBYSCORE paging
     * mechanics are covered by WebhookReplayPaginationIntegrationTest.
     */
    private void stubReplayWindow(List<io.runcycles.admin.model.event.Event> events) {
        List<String> ids = events.stream()
            .map(io.runcycles.admin.model.event.Event::getEventId).toList();
        lenient().when(eventRepository.listEventIdsInRange(nullable(String.class), any(), any(), anyInt()))
            .thenReturn(ids);
        lenient().when(eventRepository.hydrateByIds(any())).thenReturn(events);
    }

    // NOTE: the paging MECHANICS (equal-timestamp boundary, hydration-thinned
    // pages, vanished cursor, scan ceiling, cross-page chronological order) are
    // covered against REAL Redis / the real EventRepository in
    // WebhookReplayPaginationIntegrationTest — mocked pages cannot exercise the
    // ZRANGEBYSCORE contract those bugs live in.

    // All-or-narrow: MORE than max_events deliverable in the window → 400,
    // NOTHING dispatched, replay lock released (max_events is not a partial cap).
    @Test
    void replay_moreThanMaxEventsDeliverable_throws400_nothingDispatched() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        io.runcycles.admin.model.event.Event a = evt("evt_a", io.runcycles.admin.model.event.EventType.BUDGET_CREATED, io.runcycles.admin.model.event.EventCategory.BUDGET);
        io.runcycles.admin.model.event.Event b = evt("evt_b", io.runcycles.admin.model.event.EventType.BUDGET_CREATED, io.runcycles.admin.model.event.EventCategory.BUDGET);
        io.runcycles.admin.model.event.Event c = evt("evt_c", io.runcycles.admin.model.event.EventType.BUDGET_CREATED, io.runcycles.admin.model.event.EventCategory.BUDGET);
        stubReplayWindow(List.of(a, b, c)); // 3 deliverable

        assertThatThrownBy(() -> webhookService.replay("whsub_1",
                ReplayRequest.builder().from(Instant.now().minusSeconds(3600)).to(Instant.now())
                    .maxEvents(2).build())) // 3 > max_events 2
            .isInstanceOf(GovernanceException.class)
            .satisfies(ex -> {
                GovernanceException ge = (GovernanceException) ex;
                assertThat(ge.getHttpStatus()).isEqualTo(400);
                assertThat(ge.getErrorCode())
                    .isEqualTo(io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST);
                assertThat(ge.getMessage()).contains("more than max_events").contains("raise max_events");
            });

        verify(dispatchService, never()).dispatchToSubscription(any(), any()); // nothing dispatched
        verify(webhookRepository).releaseReplayLock(eq("whsub_1"), any());       // lock released
    }

    // All-or-narrow, within the cap: all deliverable events (<= max_events) are
    // delivered, in chronological (id-list) order.
    @Test
    void replay_allDeliverableWithinMax_deliveredInOrder() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        io.runcycles.admin.model.event.Event a = evt("evt_a", io.runcycles.admin.model.event.EventType.BUDGET_CREATED, io.runcycles.admin.model.event.EventCategory.BUDGET);
        io.runcycles.admin.model.event.Event b = evt("evt_b", io.runcycles.admin.model.event.EventType.BUDGET_CREATED, io.runcycles.admin.model.event.EventCategory.BUDGET);
        io.runcycles.admin.model.event.Event c = evt("evt_c", io.runcycles.admin.model.event.EventType.BUDGET_CREATED, io.runcycles.admin.model.event.EventCategory.BUDGET);
        stubReplayWindow(List.of(a, b, c));

        ReplayResponse response = webhookService.replay("whsub_1",
            ReplayRequest.builder().from(Instant.now().minusSeconds(3600)).to(Instant.now())
                .maxEvents(5).build()); // 3 <= 5 → deliver all

        assertThat(response.getEventsQueued()).isEqualTo(3);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(dispatchService);
        inOrder.verify(dispatchService).dispatchToSubscription(a, sub);
        inOrder.verify(dispatchService).dispatchToSubscription(b, sub);
        inOrder.verify(dispatchService).dispatchToSubscription(c, sub);
    }

    // The fail-closed ownership boundary is applied DURING collection, so an
    // admin event a concrete-tenant OFFENDER still matches by selector does not
    // consume cap budget and is not delivered.
    @Test
    void replay_ownershipBoundaryExcludesAdminEventDuringCollection() {
        WebhookSubscription offender = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1")
            .url("https://example.com/webhook")
            .eventTypes(List.of(io.runcycles.admin.model.event.EventType.BUDGET_CREATED,
                                io.runcycles.admin.model.event.EventType.API_KEY_CREATED))
            .status(WebhookStatus.ACTIVE).consecutiveFailures(0).createdAt(Instant.now()).build();
        when(webhookRepository.findById("whsub_1")).thenReturn(offender);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        io.runcycles.admin.model.event.Event budgetEvt = evt("evt_ok", io.runcycles.admin.model.event.EventType.BUDGET_CREATED, io.runcycles.admin.model.event.EventCategory.BUDGET);
        io.runcycles.admin.model.event.Event adminEvt = evt("evt_admin", io.runcycles.admin.model.event.EventType.API_KEY_CREATED, io.runcycles.admin.model.event.EventCategory.API_KEY);
        stubReplayWindow(List.of(budgetEvt, adminEvt));
        // Boundary blocks the admin event for this concrete-tenant sub (the
        // budgetEvt call is left at the mock default, false → deliverable).
        lenient().when(dispatchService.isBlockedByOwnershipBoundary(adminEvt, offender)).thenReturn(true);

        ReplayResponse response = webhookService.replay("whsub_1",
            ReplayRequest.builder().from(Instant.now().minusSeconds(3600)).to(Instant.now()).build());

        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService).dispatchToSubscription(budgetEvt, offender);
        verify(dispatchService, never()).dispatchToSubscription(eq(adminEvt), any());
    }

    @Test
    void replay_disabledSubscription_deliversNothing_noLock() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        sub.setStatus(WebhookStatus.DISABLED);
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        ReplayResponse response = webhookService.replay("whsub_1",
            ReplayRequest.builder().from(Instant.now().minusSeconds(3600)).to(Instant.now()).build());

        assertThat(response.getEventsQueued()).isEqualTo(0);
        // Short-circuits before acquiring the lock or dispatching anything.
        verify(webhookRepository, never()).acquireReplayLock(anyString(), any());
        verify(dispatchService, never()).dispatchToSubscription(any(), any());
    }

    @Test
    void replay_systemSubscription_queriesAllTenants() {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_sys").tenantId("__system__")
            .url("https://system.example.com/hook")
            .eventTypes(List.of(io.runcycles.admin.model.event.EventType.BUDGET_CREATED))
            .status(WebhookStatus.ACTIVE).consecutiveFailures(0).disableAfterFailures(10).build();
        when(webhookRepository.findById("whsub_sys")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_sys"), any())).thenReturn(true);
        stubReplayWindow(List.of());

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        webhookService.replay("whsub_sys", request);

        // Should pass null for tenantId to query all tenants
        verify(eventRepository, atLeastOnce()).listEventIdsInRange(isNull(), any(), any(), anyInt());
    }

    // ---- replay scope_filter conformance (same matcher as live dispatch) ----

    private io.runcycles.admin.model.event.Event scopedEvent(String eventId, String scope) {
        return io.runcycles.admin.model.event.Event.builder()
            .eventId(eventId).eventType(EventType.BUDGET_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.BUDGET)
            .tenantId("tenant-1").source("admin").scope(scope)
            .timestamp(Instant.now()).build();
    }

    @Test
    void replay_scopeFilteredSubscription_deliversOnlyMatchingScopes() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        sub.setScopeFilter("tenant:a/*");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);

        io.runcycles.admin.model.event.Event matching = scopedEvent("evt_match", "tenant:a/workspace:b");
        io.runcycles.admin.model.event.Event otherScope = scopedEvent("evt_other", "tenant:b/workspace:c");
        io.runcycles.admin.model.event.Event unscoped = scopedEvent("evt_null", null);
        stubReplayWindow(List.of(matching, otherScope, unscoped));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        // Only the event whose scope matches the subscription's scope_filter
        // is re-delivered; the foreign-scope and null-scope events are not.
        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService).dispatchToSubscription(matching, sub);
        verify(dispatchService, never()).dispatchToSubscription(eq(otherScope), any());
        verify(dispatchService, never()).dispatchToSubscription(eq(unscoped), any());
    }

    @Test
    void replay_nullScopeEvent_excludedFromScopeFilteredSubscription() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        sub.setScopeFilter("tenant:a/workspace:b");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        stubReplayWindow(List.of(scopedEvent("evt_null", null)));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getEventsQueued()).isEqualTo(0);
        verify(dispatchService, never()).dispatchToSubscription(any(), any());
    }

    @Test
    void replay_bareWildcardSubscription_deliversScopedButNotUnscopedEvents() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        sub.setScopeFilter("*");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);

        io.runcycles.admin.model.event.Event scoped = scopedEvent("evt_scoped", "tenant:a/workspace:b");
        io.runcycles.admin.model.event.Event unscoped = scopedEvent("evt_null", null);
        stubReplayWindow(List.of(scoped, unscoped));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService).dispatchToSubscription(scoped, sub);
        verify(dispatchService, never()).dispatchToSubscription(eq(unscoped), any());
    }

    @Test
    void replay_noScopeFilter_deliversScopedAndUnscopedEvents() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1"); // no scope_filter
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);

        io.runcycles.admin.model.event.Event scoped = scopedEvent("evt_scoped", "tenant:a/workspace:b");
        io.runcycles.admin.model.event.Event unscoped = scopedEvent("evt_null", null);
        stubReplayWindow(List.of(scoped, unscoped));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getEventsQueued()).isEqualTo(2);
        verify(dispatchService).dispatchToSubscription(scoped, sub);
        verify(dispatchService).dispatchToSubscription(unscoped, sub);
    }

    @Test
    void replay_scopeFilterAndEventTypeFilter_bothApply() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        sub.setScopeFilter("tenant:a/*");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);

        // Matches scope but not the requested event type:
        io.runcycles.admin.model.event.Event wrongType = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_wrong_type").eventType(EventType.TENANT_CREATED)
            .category(io.runcycles.admin.model.event.EventCategory.TENANT)
            .tenantId("tenant-1").source("admin").scope("tenant:a/workspace:b")
            .timestamp(Instant.now()).build();
        // Matches both:
        io.runcycles.admin.model.event.Event matchesBoth = scopedEvent("evt_both", "tenant:a/workspace:b");
        stubReplayWindow(List.of(wrongType, matchesBoth));

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .eventTypes(List.of(EventType.BUDGET_CREATED))
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getEventsQueued()).isEqualTo(1);
        verify(dispatchService).dispatchToSubscription(matchesBoth, sub);
        verify(dispatchService, never()).dispatchToSubscription(eq(wrongType), any());
    }

    // #209 finding 3: /test is a NARROW, DOCUMENTED exception to the webhook
    // category boundary. It POSTs a spec-defined system.webhook_test ping
    // DIRECTLY to a (possibly tenant-owned) endpoint. This is intentional: the
    // spec defines the test event as the admin-category `system.webhook_test`,
    // the payload is a synthetic owner-triggered probe carrying no governance
    // telemetry, and it bypasses the dispatch queue entirely. This test pins the
    // documented envelope so the exception stays explicit.
    @Test
    void test_concreteTenantSub_usesSpecDefinedSystemWebhookTestEnvelope_documentedException() throws Exception {
        WebhookSubscription concrete = buildSubscription("whsub_1", "tenant-1"); // concrete tenant
        when(webhookRepository.findById("whsub_1")).thenReturn(concrete);
        when(webhookRepository.getSigningSecret("whsub_1")).thenReturn("test-secret");
        org.mockito.ArgumentCaptor<io.runcycles.admin.model.event.Event> evt =
            org.mockito.ArgumentCaptor.forClass(io.runcycles.admin.model.event.Event.class);
        when(objectMapper.writeValueAsString(evt.capture())).thenReturn("{\"test\":true}");

        webhookService.test("whsub_1");

        io.runcycles.admin.model.event.Event pinged = evt.getValue();
        assertThat(pinged.getEventType())
            .isEqualTo(io.runcycles.admin.model.event.EventType.SYSTEM_WEBHOOK_TEST);
        assertThat(pinged.getCategory())
            .isEqualTo(io.runcycles.admin.model.event.EventCategory.SYSTEM);
        // Synthetic, owner-triggered probe — carries no real governance telemetry.
        assertThat(pinged.getData()).containsEntry("test", true);
    }

    @Test
    void test_withNoSigningSecret_stillWorks() throws Exception {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.getSigningSecret("whsub_1")).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":true}");

        WebhookTestResponse response = webhookService.test("whsub_1");

        assertThat(response).isNotNull();
        assertThat(response.getEventId()).startsWith("evt_test_");
    }

    @Test
    void test_serializationError_returnsFailure() throws Exception {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
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
    void test_currentUrlSecurityRejection_blocksOutboundSend() throws Exception {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        sub.setUrl("https://127.0.0.1/webhook");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.getSigningSecret("whsub_1")).thenReturn("secret");
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":true}");
        doThrow(GovernanceException.webhookUrlInvalid(sub.getUrl(), "Resolves to blocked IP: 127.0.0.1"))
            .when(urlValidator).validate(sub.getUrl());

        WebhookTestResponse response = webhookService.test("whsub_1");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorMessage()).isEqualTo("Webhook URL rejected by current security policy");
        assertThat(response.getEventId()).startsWith("evt_test_");
    }

    @Test
    void replay_withEventTypeFilter_filtersCorrectly() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);

        io.runcycles.admin.model.event.Event evt1 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(EventType.BUDGET_CREATED).tenantId("tenant-1")
            .timestamp(Instant.now()).build();
        io.runcycles.admin.model.event.Event evt2 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_2").eventType(EventType.TENANT_CREATED).tenantId("tenant-1")
            .timestamp(Instant.now()).build();

        stubReplayWindow(List.of(evt1, evt2));

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
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        // Subscribe to both budget events so both pass the #209 selector filter.
        sub.setEventTypes(List.of(EventType.BUDGET_CREATED, EventType.BUDGET_FUNDED));
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);

        io.runcycles.admin.model.event.Event evt1 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_1").eventType(EventType.BUDGET_CREATED).tenantId("tenant-1")
            .timestamp(Instant.now()).build();
        io.runcycles.admin.model.event.Event evt2 = io.runcycles.admin.model.event.Event.builder()
            .eventId("evt_2").eventType(EventType.BUDGET_FUNDED).tenantId("tenant-1")
            .timestamp(Instant.now()).build();

        stubReplayWindow(List.of(evt1, evt2));
        doThrow(new RuntimeException("dispatch error")).when(dispatchService)
            .dispatchToSubscription(eq(evt1), any());

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        // evt1 failed, evt2 succeeded
        assertThat(response.getEventsQueued()).isEqualTo(1);
    }

    @Test
    void replay_lockAlreadyHeld_throws409() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(false);

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        assertThatThrownBy(() -> webhookService.replay("whsub_1", request))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("Replay already in progress");
        verify(eventRepository, never()).list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void replay_releasesLockAfterCompletion() {
        WebhookSubscription sub = buildSubscription("whsub_1", "tenant-1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        when(webhookRepository.acquireReplayLock(eq("whsub_1"), any())).thenReturn(true);
        stubReplayWindow(List.of());

        ReplayRequest request = ReplayRequest.builder()
            .from(Instant.now().minusSeconds(3600))
            .to(Instant.now())
            .build();

        webhookService.replay("whsub_1", request);

        verify(webhookRepository).releaseReplayLock(eq("whsub_1"), any());
    }

    @Test
    void update_statusDisabled_throws() {
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
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
        WebhookSubscription existing = buildSubscription("whsub_1", "tenant-1");
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
        WebhookSubscription sub1 = buildSubscription("whsub_1", "tenant-1");
        WebhookSubscription sub2 = buildSubscription("whsub_2", "tenant-1");
        when(webhookRepository.listByTenant(eq("tenant-1"), isNull(), isNull(), isNull(), eq(2), any(), any()))
            .thenReturn(List.of(sub1, sub2));

        WebhookListResponse response = webhookService.listAll("tenant-1", null, null, null, 2);

        assertThat(response.getSubscriptions()).hasSize(2);
        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getNextCursor()).isEqualTo("whsub_2");
        verify(webhookRepository, never()).listAll(any(), any(), any(), anyInt(), any());
    }

    // ========== classifyDeliveryError ==========

    @Test
    void classifyDeliveryError_unknownHost() {
        assertThat(WebhookService.classifyDeliveryError(
                new java.net.UnknownHostException("bad.example.com")))
                .isEqualTo("DNS resolution failed: bad.example.com");
    }

    @Test
    void classifyDeliveryError_connectException() {
        assertThat(WebhookService.classifyDeliveryError(
                new java.net.ConnectException("Connection refused")))
                .isEqualTo("Connection refused");
    }

    @Test
    void classifyDeliveryError_sslHandshake() {
        assertThat(WebhookService.classifyDeliveryError(
                new javax.net.ssl.SSLHandshakeException("certificate expired")))
                .isEqualTo("TLS/SSL handshake failed");
    }

    @Test
    void classifyDeliveryError_socketTimeout() {
        assertThat(WebhookService.classifyDeliveryError(
                new java.net.SocketTimeoutException("Read timed out")))
                .isEqualTo("Socket timed out");
    }

    @Test
    void classifyDeliveryError_wrappedCause() {
        // HttpClient wraps real errors in IOException
        Exception wrapped = new java.io.IOException("wrapper",
                new java.net.UnknownHostException("nested.example.com"));
        assertThat(WebhookService.classifyDeliveryError(wrapped))
                .isEqualTo("DNS resolution failed: nested.example.com");
    }

    @Test
    void classifyDeliveryError_unknownException_includesClassName() {
        assertThat(WebhookService.classifyDeliveryError(
                new IllegalStateException("something broke")))
                .isEqualTo("IllegalStateException: something broke");
    }

    @Test
    void classifyDeliveryError_unknownException_noMessage() {
        assertThat(WebhookService.classifyDeliveryError(
                new NullPointerException()))
                .isEqualTo("NullPointerException");
    }

    @Test
    void httpClient_usesHttp1_1() throws Exception {
        // Webhook receivers are standard HTTP/1.1 endpoints — HTTP/2 upgrade
        // requests (Upgrade: h2c) break on many receivers.
        java.lang.reflect.Field field = WebhookService.class.getDeclaredField("HTTP_CLIENT");
        field.setAccessible(true);
        java.net.http.HttpClient client = (java.net.http.HttpClient) field.get(null);
        assertThat(client.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }
}
