package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookAdminController.class)
class WebhookAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebhookService webhookService;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void createWebhook_returns201() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("t1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookCreateResponse response = WebhookCreateResponse.builder()
            .subscription(sub).signingSecret("whsec_abc").build();
        when(webhookService.create(eq("t1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscription.subscription_id").value("whsub_1"))
                .andExpect(jsonPath("$.signing_secret").value("whsec_abc"));

        verify(auditRepository).log(argThat(entry ->
                "createWebhookSubscription".equals(entry.getOperation()) &&
                entry.getStatus() == 201));
    }

    @Test
    void createWebhook_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listWebhooks_returns200() throws Exception {
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listAll(any(), any(), any(), any(), anyInt())).thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").isArray());
    }

    @Test
    void getWebhook_returns200() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("t1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);

        mockMvc.perform(get("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription_id").value("whsub_1"));
    }

    @Test
    void getWebhook_notFound_returns404() throws Exception {
        when(webhookService.get("whsub_missing"))
            .thenThrow(GovernanceException.webhookNotFound("whsub_missing"));

        mockMvc.perform(get("/v1/admin/webhooks/whsub_missing")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WEBHOOK_NOT_FOUND"));
    }

    @Test
    void updateWebhook_returns200() throws Exception {
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("t1").name("Updated")
            .url("https://example.com/wh").status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));

        verify(auditRepository).log(argThat(entry ->
                "updateWebhookSubscription".equals(entry.getOperation()) &&
                entry.getStatus() == 200));
    }

    @Test
    void deleteWebhook_returns204() throws Exception {
        mockMvc.perform(delete("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        verify(webhookService).delete("whsub_1");
        verify(auditRepository).log(argThat(entry ->
                "deleteWebhookSubscription".equals(entry.getOperation()) &&
                entry.getStatus() == 204));
    }

    @Test
    void testWebhook_returns200() throws Exception {
        WebhookTestResponse testResponse = WebhookTestResponse.builder()
            .success(true).responseStatus(200).responseTimeMs(42).eventId("evt_test_1").build();
        when(webhookService.test("whsub_1")).thenReturn(testResponse);

        mockMvc.perform(post("/v1/admin/webhooks/whsub_1/test")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.event_id").value("evt_test_1"));
    }

    @Test
    void listDeliveries_returns200() throws Exception {
        WebhookDeliveryListResponse response = WebhookDeliveryListResponse.builder()
            .deliveries(List.of()).hasMore(false).build();
        when(webhookService.listDeliveries(eq("whsub_1"), any(), any(), any(), any(), anyInt()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks/whsub_1/deliveries")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries").isArray());
    }

    @Test
    void listWebhooks_clampsLimitTo100() throws Exception {
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listAll(any(), any(), any(), any(), eq(100))).thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(webhookService).listAll(any(), any(), any(), any(), eq(100));
    }

    @Test
    void listDeliveries_clampsLimitTo100() throws Exception {
        WebhookDeliveryListResponse response = WebhookDeliveryListResponse.builder()
            .deliveries(List.of()).hasMore(false).build();
        when(webhookService.listDeliveries(eq("whsub_1"), any(), any(), any(), any(), eq(100)))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks/whsub_1/deliveries")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(webhookService).listDeliveries(eq("whsub_1"), any(), any(), any(), any(), eq(100));
    }

    @Test
    void replay_returns202() throws Exception {
        ReplayResponse response = ReplayResponse.builder()
            .replayId("replay_1").eventsQueued(5).estimatedCompletionSeconds(10).build();
        when(webhookService.replay(eq("whsub_1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/webhooks/whsub_1/replay")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_events\":100}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replay_id").value("replay_1"))
                .andExpect(jsonPath("$.events_queued").value(5));
    }
}
