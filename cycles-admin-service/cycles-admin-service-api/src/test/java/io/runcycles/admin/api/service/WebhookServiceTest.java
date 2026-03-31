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
    void test_returnsWebhookTestResponse() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);

        WebhookTestResponse response = webhookService.test("whsub_1");

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getEventId()).startsWith("evt_test_");
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
    void listAll_delegatesAndFilters() {
        WebhookSubscription sub1 = buildSubscription("whsub_1", "t1");
        WebhookSubscription sub2 = buildSubscription("whsub_2", "t2");
        when(webhookRepository.listAll(null, null, null, 50))
            .thenReturn(List.of(sub1, sub2));

        WebhookListResponse response = webhookService.listAll("t1", null, null, null, 50);

        assertThat(response.getSubscriptions()).hasSize(1);
        assertThat(response.getSubscriptions().get(0).getTenantId()).isEqualTo("t1");
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
    void replay_returnsReplayResponse() {
        WebhookSubscription sub = buildSubscription("whsub_1", "t1");
        when(webhookRepository.findById("whsub_1")).thenReturn(sub);
        ReplayRequest request = ReplayRequest.builder().build();

        ReplayResponse response = webhookService.replay("whsub_1", request);

        assertThat(response.getReplayId()).startsWith("replay_");
        assertThat(response.getEventsQueued()).isEqualTo(0);
    }
}
