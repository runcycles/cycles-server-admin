package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
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

@WebMvcTest(WebhookTenantController.class)
class WebhookTenantControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebhookService webhookService;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("webhooks:read", "webhooks:write", "events:read")).build());
    }

    @Test
    void createWebhook_withValidTenantEventTypes_returns201() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("t1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookCreateResponse response = WebhookCreateResponse.builder()
            .subscription(sub).signingSecret("whsec_abc").build();
        when(webhookService.create(eq("t1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscription.subscription_id").value("whsub_1"));
    }

    @Test
    void createWebhook_withAdminOnlyEventType_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"api_key.created\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WEBHOOK_URL_INVALID"));
    }

    @Test
    void getWebhook_ownWebhook_returns200() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("t1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);

        mockMvc.perform(get("/v1/webhooks/whsub_1")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription_id").value("whsub_1"));
    }

    @Test
    void getWebhook_otherTenantWebhook_returns404() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_other").tenantId("t2").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_other")).thenReturn(sub);

        mockMvc.perform(get("/v1/webhooks/whsub_other")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WEBHOOK_NOT_FOUND"));
    }

    @Test
    void deleteWebhook_ownWebhook_returns204() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("t1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);

        mockMvc.perform(delete("/v1/webhooks/whsub_1")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isNoContent());

        verify(webhookService).delete("whsub_1");
    }

    @Test
    void createWebhook_noApiKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listWebhooks_returns200() throws Exception {
        setupApiKeyAuth();
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listByTenant(eq("t1"), any(), any(), any(), anyInt())).thenReturn(response);

        mockMvc.perform(get("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").isArray());
    }
}
