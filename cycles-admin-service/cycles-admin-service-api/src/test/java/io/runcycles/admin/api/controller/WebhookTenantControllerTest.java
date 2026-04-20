package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
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
import org.springframework.context.annotation.Import;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import io.runcycles.admin.api.contract.ContractValidationConfig;
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
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class WebhookTenantControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebhookService webhookService;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;
    @MockitoBean private TerminalOwnerMutationGuard mutationGuard;

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("webhooks:read", "webhooks:write", "events:read")).build());
    }

    @Test
    void createWebhook_withValidTenantEventTypes_returns201() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookCreateResponse response = WebhookCreateResponse.builder()
            .subscription(sub).signingSecret("whsec_abc").build();
        when(webhookService.create(eq("tenant-1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscription.subscription_id").value("whsub_1"));

        verify(auditRepository).log(argThat(entry ->
                "createTenantWebhook".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 201));
    }

    @Test
    void createWebhook_withAdminOnlyEventType_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"api_key.created\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void getWebhook_ownWebhook_returns200() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
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
            .subscriptionId("whsub_other").tenantId("tenant-2").url("https://example.com/wh")
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
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);

        mockMvc.perform(delete("/v1/webhooks/whsub_1")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isNoContent());

        verify(webhookService).delete("whsub_1");
        verify(auditRepository).log(argThat(entry ->
                "deleteTenantWebhook".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 204));
    }

    @Test
    void createWebhook_noApiKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateWebhook_ownWebhook_returns200() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh2")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/webhooks/whsub_1")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription_id").value("whsub_1"));

        verify(auditRepository).log(argThat(entry ->
                "updateTenantWebhook".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void updateWebhook_withAdminOnlyEventType_returns400() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);

        mockMvc.perform(patch("/v1/webhooks/whsub_1")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_types\":[\"api_key.created\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void updateWebhook_otherTenantWebhook_returns404() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_other").tenantId("tenant-2").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_other")).thenReturn(sub);

        mockMvc.perform(patch("/v1/webhooks/whsub_other")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh2\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testWebhook_ownWebhook_returns200() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);
        WebhookTestResponse testResponse = WebhookTestResponse.builder()
            .success(true).responseStatus(200).build();
        when(webhookService.test("whsub_1")).thenReturn(testResponse);

        mockMvc.perform(post("/v1/webhooks/whsub_1/test")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditRepository).log(argThat(entry ->
                "testTenantWebhook".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void testWebhook_otherTenantWebhook_returns404() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_other").tenantId("tenant-2").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_other")).thenReturn(sub);

        mockMvc.perform(post("/v1/webhooks/whsub_other/test")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listDeliveries_ownWebhook_returns200() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);
        WebhookDeliveryListResponse deliveries = WebhookDeliveryListResponse.builder()
            .deliveries(List.of()).hasMore(false).build();
        when(webhookService.listDeliveries(eq("whsub_1"), any(), any(), any(), any(), anyInt()))
            .thenReturn(deliveries);

        mockMvc.perform(get("/v1/webhooks/whsub_1/deliveries")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries").isArray());
    }

    @Test
    void listDeliveries_otherTenantWebhook_returns404() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_other").tenantId("tenant-2").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_other")).thenReturn(sub);

        mockMvc.perform(get("/v1/webhooks/whsub_other/deliveries")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listWebhooks_clampsLimitTo100() throws Exception {
        setupApiKeyAuth();
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listByTenant(eq("tenant-1"), any(), any(), any(), eq(100))).thenReturn(response);

        mockMvc.perform(get("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(webhookService).listByTenant(eq("tenant-1"), any(), any(), any(), eq(100));
    }

    @Test
    void listDeliveries_clampsLimitTo100() throws Exception {
        setupApiKeyAuth();
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);
        WebhookDeliveryListResponse response = WebhookDeliveryListResponse.builder()
            .deliveries(List.of()).hasMore(false).build();
        when(webhookService.listDeliveries(eq("whsub_1"), any(), any(), any(), any(), eq(100)))
            .thenReturn(response);

        mockMvc.perform(get("/v1/webhooks/whsub_1/deliveries")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(webhookService).listDeliveries(eq("whsub_1"), any(), any(), any(), any(), eq(100));
    }

    @Test
    void listWebhooks_returns200() throws Exception {
        setupApiKeyAuth();
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listByTenant(eq("tenant-1"), any(), any(), any(), anyInt())).thenReturn(response);

        mockMvc.perform(get("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").isArray());
    }

    // ========== Admin-on-behalf-of dual-auth (v0.1.25.16, spec v0.1.25.14) ==========

    @Test
    void listWebhooks_withAdminKey_andTenantQuery_returns200() throws Exception {
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listByTenant(eq("tenant-acme"), any(), any(), any(), anyInt())).thenReturn(response);

        mockMvc.perform(get("/v1/webhooks")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .param("tenant", "tenant-acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").isArray());

        verify(webhookService).listByTenant(eq("tenant-acme"), any(), any(), any(), anyInt());
    }

    @Test
    void listWebhooks_withAdminKey_missingTenantQuery_returns400() throws Exception {
        mockMvc.perform(get("/v1/webhooks")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tenant query parameter is required")));
    }

    @Test
    void listWebhooks_withApiKey_andTenantQuery_returns400() throws Exception {
        // Tenant-key callers MUST NOT set the tenant query param (would let
        // a tenant list a peer's subscriptions by tenant_id guessing).
        setupApiKeyAuth();

        mockMvc.perform(get("/v1/webhooks")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("tenant", "tenant-other"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("MUST NOT be set")));
    }

    @Test
    void getWebhook_withAdminKey_crossTenant_returns200() throws Exception {
        // Admin has no effective tenant — can read any subscription.
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_other").tenantId("tenant-other").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_other")).thenReturn(sub);

        mockMvc.perform(get("/v1/webhooks/whsub_other")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription_id").value("whsub_other"));
    }

    @Test
    void updateWebhook_withAdminKey_crossTenant_returns200_andAuditTaggedAdminOnBehalfOf() throws Exception {
        WebhookSubscription existing = WebhookSubscription.builder()
            .subscriptionId("whsub_x").tenantId("tenant-x").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_x")).thenReturn(existing);
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_x").tenantId("tenant-x").url("https://example.com/wh")
            .status(WebhookStatus.PAUSED).createdAt(Instant.now()).build();
        when(webhookService.update(eq("whsub_x"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/webhooks/whsub_x")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk());

        // Audit subject is the owning tenant (resolved from the subscription),
        // NOT the admin caller (admin has no tenant). actor_type=admin_on_behalf_of
        // so security review can tell admin-driven pauses from tenant self-service.
        verify(auditRepository).log(argThat(entry ->
                "updateTenantWebhook".equals(entry.getOperation()) &&
                "tenant-x".equals(entry.getTenantId()) &&
                entry.getMetadata() != null &&
                "admin_on_behalf_of".equals(entry.getMetadata().get("actor_type"))));
    }

    @Test
    void deleteWebhook_withAdminKey_crossTenant_returns204_andAuditTaggedAdminOnBehalfOf() throws Exception {
        WebhookSubscription existing = WebhookSubscription.builder()
            .subscriptionId("whsub_x").tenantId("tenant-x").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_x")).thenReturn(existing);

        mockMvc.perform(delete("/v1/webhooks/whsub_x")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isNoContent());

        verify(webhookService).delete("whsub_x");
        verify(auditRepository).log(argThat(entry ->
                "deleteTenantWebhook".equals(entry.getOperation()) &&
                "tenant-x".equals(entry.getTenantId()) &&
                entry.getMetadata() != null &&
                "admin_on_behalf_of".equals(entry.getMetadata().get("actor_type"))));
    }

    @Test
    void testWebhook_withAdminKey_crossTenant_returns200() throws Exception {
        WebhookSubscription existing = WebhookSubscription.builder()
            .subscriptionId("whsub_x").tenantId("tenant-x").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_x")).thenReturn(existing);
        when(webhookService.test("whsub_x")).thenReturn(
            WebhookTestResponse.builder().success(true).responseStatus(200).build());

        mockMvc.perform(post("/v1/webhooks/whsub_x/test")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditRepository).log(argThat(entry ->
                "testTenantWebhook".equals(entry.getOperation()) &&
                "tenant-x".equals(entry.getTenantId()) &&
                entry.getMetadata() != null &&
                "admin_on_behalf_of".equals(entry.getMetadata().get("actor_type"))));
    }

    @Test
    void listDeliveries_withAdminKey_crossTenant_returns200() throws Exception {
        WebhookSubscription existing = WebhookSubscription.builder()
            .subscriptionId("whsub_x").tenantId("tenant-x").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_x")).thenReturn(existing);
        WebhookDeliveryListResponse deliveries = WebhookDeliveryListResponse.builder()
            .deliveries(List.of()).hasMore(false).build();
        when(webhookService.listDeliveries(eq("whsub_x"), any(), any(), any(), any(), anyInt()))
            .thenReturn(deliveries);

        mockMvc.perform(get("/v1/webhooks/whsub_x/deliveries")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries").isArray());
    }

    @Test
    void createWebhook_withAdminKey_returns401() throws Exception {
        // createTenantWebhook is NOT in the dual-auth allowlist — admin
        // cannot mint subscriptions on a tenant's behalf. The interceptor
        // falls through to ApiKey validation (no tenant key present) and
        // returns 401. Provenance footgun guard.
        mockMvc.perform(post("/v1/webhooks")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isUnauthorized());
    }
}
