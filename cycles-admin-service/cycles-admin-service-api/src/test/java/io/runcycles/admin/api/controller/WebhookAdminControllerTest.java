package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.idempotency.IdempotencyStore;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.event.Actor;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.webhook.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

@WebMvcTest(WebhookAdminController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class WebhookAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebhookService webhookService;
    @MockitoBean private WebhookRepository webhookRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;
    @MockitoBean private IdempotencyStore idempotencyStore;
    @MockitoBean private TerminalOwnerMutationGuard mutationGuard;
    @MockitoBean private EventService eventService;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void createWebhook_returns201() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookCreateResponse response = WebhookCreateResponse.builder()
            .subscription(sub).signingSecret("whsec_abc").build();
        when(webhookService.create(eq("tenant-1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
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
        when(webhookService.listAll(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").isArray());
    }

    @Test
    void getWebhook_returns200() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
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
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").name("Old")
            .url("https://example.com/wh").status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").name("Updated")
            .url("https://example.com/wh").status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));

        verify(auditRepository).log(argThat(entry ->
                "updateWebhookSubscription".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                entry.getStatus() == 200));
    }

    // v0.1.25.50: the tenant-plane event_categories boundary is NOT a global
    // tightening — the admin plane (/v1/admin/webhooks) may create/update
    // subscriptions with admin-only categories. Proves the boundary is
    // scoped to the tenant endpoint, not applied to every WebhookService call.
    @Test
    void createWebhook_withAdminOnlyEventCategory_returns201() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_admincat").tenantId("tenant-1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED))
            .eventCategories(List.of(EventCategory.API_KEY)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookCreateResponse response = WebhookCreateResponse.builder()
            .subscription(sub).signingSecret("whsec_abc").build();
        when(webhookService.create(eq("tenant-1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"],\"event_categories\":[\"api_key\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscription.subscription_id").value("whsub_admincat"));

        verify(webhookService).create(eq("tenant-1"), any());
    }

    @Test
    void updateWebhook_withAdminOnlyEventCategory_returns200() throws Exception {
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .eventCategories(List.of(EventCategory.SYSTEM)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_categories\":[\"system\"]}"))
                .andExpect(status().isOk());

        verify(webhookService).update(eq("whsub_1"), any());
    }

    @Test
    void deleteWebhook_returns204() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);

        mockMvc.perform(delete("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        verify(webhookService).delete("whsub_1");
        verify(auditRepository).log(argThat(entry ->
                "deleteWebhookSubscription".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
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

        verify(auditRepository).log(argThat(entry ->
                "testWebhookSubscription".equals(entry.getOperation()) &&
                entry.getStatus() == 200));
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
        when(webhookService.listAll(any(), any(), any(), any(), eq(100), any(), any())).thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(webhookService).listAll(any(), any(), any(), any(), eq(100), any(), any());
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

        verify(auditRepository).log(argThat(entry ->
                "replayEvents".equals(entry.getOperation()) &&
                entry.getStatus() == 202));
    }

    // --- Sort contract tests (spec v0.1.25.20 §V4 server-side sort) ---

    @Test
    void listWebhooks_defaultsToConsecutiveFailuresDesc() throws Exception {
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listAll(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(webhookService).listAll(any(), any(), any(), any(), anyInt(), captor.capture(), any());
        SortSpec sort = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("consecutive_failures", sort.field());
        org.junit.jupiter.api.Assertions.assertEquals(SortDirection.DESC, sort.direction());
    }

    @Test
    void listWebhooks_acceptsValidSortByAndDir() throws Exception {
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listAll(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "url")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(webhookService).listAll(any(), any(), any(), any(), anyInt(), captor.capture(), any());
        SortSpec sort = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("url", sort.field());
        org.junit.jupiter.api.Assertions.assertEquals(SortDirection.ASC, sort.direction());
    }

    @Test
    void listWebhooks_acceptsAllWhitelistedFields() throws Exception {
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listAll(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(response);

        for (String field : List.of("url", "tenant_id", "status", "consecutive_failures")) {
            mockMvc.perform(get("/v1/admin/webhooks")
                            .header("X-Admin-API-Key", ADMIN_KEY)
                            .param("sort_by", field))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void listWebhooks_unknownSortBy_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listWebhooks_unknownSortDir_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "url")
                        .param("sort_dir", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listWebhooks_sortDirDefaultsToDesc_whenOmittedWithSortBy() throws Exception {
        // Spec v0.1.25.20: omitted sort_dir → DESC (newest/worst first).
        WebhookListResponse response = WebhookListResponse.builder()
            .subscriptions(List.of()).hasMore(false).build();
        when(webhookService.listAll(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "url"))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(webhookService).listAll(any(), any(), any(), any(), anyInt(), captor.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(SortDirection.DESC, captor.getValue().direction());
    }

    @Test
    void listWebhookSubscriptions_searchOver128Chars_returns400() throws Exception {
        String over = "x".repeat(129);
        mockMvc.perform(get("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("search", over))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(webhookService, never()).listAll(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // --- Bulk-action contract tests (spec v0.1.25.21) ---

    private static WebhookSubscription webhookRow(String id, WebhookStatus status) {
        return WebhookSubscription.builder()
                .subscriptionId(id).tenantId("tenant-1")
                .url("https://example.com/" + id)
                .status(status).createdAt(Instant.now()).build();
    }

    @Test
    void bulkActionWebhooks_pause_happyPath_returns200() throws Exception {
        when(idempotencyStore.lookup(eq("webhooks-bulk"), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(isNull(), eq(WebhookStatus.ACTIVE), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.ACTIVE), webhookRow("w2", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.ACTIVE));
        when(webhookRepository.findById("w2")).thenReturn(webhookRow("w2", WebhookStatus.ACTIVE));
        when(webhookService.update(anyString(), any())).thenReturn(webhookRow("w1", WebhookStatus.PAUSED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("PAUSE"))
                .andExpect(jsonPath("$.total_matched").value(2))
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.idempotency_key").value("k1"));

        verify(webhookService, times(2)).update(anyString(), any());
        verify(idempotencyStore).store(eq("webhooks-bulk"), eq("k1"), any(WebhookBulkActionResponse.class));
    }

    @Test
    void bulkActionWebhooks_emptyFilter_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(webhookRepository, never()).matchForBulk(any(), any(), any(), any(), anyInt());
    }

    @Test
    void bulkActionWebhooks_searchOver128Chars_returns400() throws Exception {
        String over = "x".repeat(129);
        String body = "{\"filter\":{\"search\":\"" + over + "\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}";
        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(webhookRepository, never()).matchForBulk(any(), any(), any(), any(), anyInt());
    }

    @Test
    void bulkActionWebhooks_expectedCountMismatch_returns409_noWrites() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.ACTIVE), webhookRow("w2", WebhookStatus.ACTIVE)));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\","
                                + "\"expected_count\":5,\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("COUNT_MISMATCH"))
                .andExpect(jsonPath("$.details.total_matched").value(2));

        verify(webhookService, never()).update(anyString(), any());
        verify(webhookService, never()).delete(anyString());
    }

    @Test
    void bulkActionWebhooks_over500Matches_returns400_limitExceeded() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        List<WebhookSubscription> oversized = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) oversized.add(webhookRow("w" + i, WebhookStatus.ACTIVE));
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500))).thenReturn(oversized);

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.total_matched").value(501));

        verify(webhookService, never()).update(anyString(), any());
        verify(webhookService, never()).delete(anyString());
    }

    @Test
    void bulkActionWebhooks_idempotencyReplay_returnsCachedEnvelope_noWrites() throws Exception {
        WebhookBulkActionResponse cached = WebhookBulkActionResponse.builder()
                .action(WebhookBulkAction.PAUSE)
                .totalMatched(3)
                .succeeded(List.of())
                .failed(List.of())
                .skipped(List.of())
                .idempotencyKey("k1")
                .build();
        when(idempotencyStore.lookup(eq("webhooks-bulk"), eq("k1"), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.of(cached));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_matched").value(3));

        verify(webhookRepository, never()).matchForBulk(any(), any(), any(), any(), anyInt());
        verify(webhookService, never()).update(anyString(), any());
        verify(idempotencyStore, never()).store(anyString(), anyString(), any());
    }

    @Test
    void bulkActionWebhooks_resume_fromDisabled_landsInFailed() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.DISABLED)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.DISABLED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"DISABLED\"},\"action\":\"RESUME\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].id").value("w1"))
                .andExpect(jsonPath("$.failed[0].error_code").value("INVALID_TRANSITION"));

        verify(webhookService, never()).update(anyString(), any());
    }

    @Test
    void bulkActionWebhooks_pause_alreadyPaused_landsInSkipped() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.PAUSED)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.PAUSED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"PAUSED\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value("w1"))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_IN_TARGET_STATE"))
                .andExpect(jsonPath("$.succeeded.length()").value(0));

        verify(webhookService, never()).update(anyString(), any());
    }

    @Test
    void bulkActionWebhooks_delete_missingRow_landsInSkipped() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.ACTIVE));
        // Concurrent delete between match and apply — service now 404s.
        doThrow(GovernanceException.webhookNotFound("w1"))
                .when(webhookService).delete("w1");

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"tenant_id\":\"tenant-1\"},\"action\":\"DELETE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value("w1"))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_DELETED"))
                .andExpect(jsonPath("$.succeeded.length()").value(0));
    }

    @Test
    void bulkActionWebhooks_delete_happyPath_succeeds() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.ACTIVE));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"tenant_id\":\"tenant-1\"},\"action\":\"DELETE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DELETE"))
                .andExpect(jsonPath("$.succeeded[0].id").value("w1"))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(0));

        verify(webhookService).delete("w1");
    }

    @Test
    void bulkActionWebhooks_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void bulkActionWebhooks_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isUnauthorized());

        verify(webhookRepository, never()).matchForBulk(any(), any(), any(), any(), anyInt());
    }

    @Test
    void bulkActionWebhooks_resumeFromPaused_happyPath_succeeds() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("whsub_1", WebhookStatus.PAUSED)));
        when(webhookRepository.findById("whsub_1"))
                .thenReturn(webhookRow("whsub_1", WebhookStatus.PAUSED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"PAUSED\"},\"action\":\"RESUME\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded[0].id").value("whsub_1"));

        ArgumentCaptor<WebhookUpdateRequest> captor = ArgumentCaptor.forClass(WebhookUpdateRequest.class);
        verify(webhookService).update(eq("whsub_1"), captor.capture());
        assert captor.getValue().getStatus() == WebhookStatus.ACTIVE;
    }

    @Test
    void bulkActionWebhooks_resumeAlreadyActive_landsInSkipped() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("whsub_1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("whsub_1"))
                .thenReturn(webhookRow("whsub_1", WebhookStatus.ACTIVE));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"RESUME\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].id").value("whsub_1"))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_IN_TARGET_STATE"));

        verify(webhookService, never()).update(anyString(), any());
    }

    @Test
    void bulkActionWebhooks_concurrentDeleteDuringPause_landsInSkipped() throws Exception {
        // findById throws GovernanceException — concurrent delete between
        // matchForBulk and apply. Spec: lands in skipped[] ALREADY_DELETED.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("whsub_1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("whsub_1"))
                .thenThrow(new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.WEBHOOK_NOT_FOUND,
                        "gone", 404));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].id").value("whsub_1"))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_DELETED"));

        verify(webhookService, never()).update(anyString(), any());
    }

    @Test
    void bulkActionWebhooks_deleteNonNotFoundGovernanceException_landsInFailed() throws Exception {
        // DELETE path where the service rejects with a non-NOT_FOUND error —
        // rethrown up to the outer GovernanceException catch.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("whsub_1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("whsub_1")).thenReturn(webhookRow("whsub_1", WebhookStatus.ACTIVE));
        doThrow(new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.INSUFFICIENT_PERMISSIONS,
                        "nope", 403))
                .when(webhookService).delete("whsub_1");

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"DELETE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].id").value("whsub_1"))
                .andExpect(jsonPath("$.failed[0].error_code").value("PERMISSION_DENIED"));
    }

    @Test
    void bulkActionWebhooks_auditMetadata_carriesV030EnrichmentKeys() throws Exception {
        // v0.1.25.30: audit metadata now carries per-row outcomes + filter
        // echo + duration_ms so post-incident triage needs only the audit log.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(isNull(), eq(WebhookStatus.ACTIVE), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(webhookRow("whsub_1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("whsub_1")).thenReturn(webhookRow("whsub_1", WebhookStatus.ACTIVE));
        when(webhookService.update(anyString(), any())).thenReturn(webhookRow("whsub_1", WebhookStatus.PAUSED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<io.runcycles.admin.model.audit.AuditLogEntry> auditArg =
                ArgumentCaptor.forClass(io.runcycles.admin.model.audit.AuditLogEntry.class);
        verify(auditRepository).log(auditArg.capture());
        java.util.Map<String, Object> meta = auditArg.getValue().getMetadata();
        org.junit.jupiter.api.Assertions.assertEquals("bulkActionWebhooks", auditArg.getValue().getOperation());
        org.junit.jupiter.api.Assertions.assertEquals("PAUSE", meta.get("action"));
        org.junit.jupiter.api.Assertions.assertEquals(1, meta.get("total_matched"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("whsub_1"), meta.get("succeeded_ids"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), meta.get("failed_rows"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), meta.get("skipped_rows"));
        org.junit.jupiter.api.Assertions.assertEquals("k1", meta.get("idempotency_key"));
        org.assertj.core.api.Assertions.assertThat(meta).containsKey("filter");
        org.assertj.core.api.Assertions.assertThat((Long) meta.get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    // v0.1.25.36 — Cascade Rule 2 on bulk-action: closed-owner rows land in failed[] with TENANT_CLOSED.
    @Test
    void bulkActionWebhooks_closedTenantRow_landsInFailed_tenantClosed() throws Exception {
        when(idempotencyStore.lookup(eq("webhooks-bulk"), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("whsub_1", WebhookStatus.ACTIVE)));
        doThrow(new GovernanceException(ErrorCode.TENANT_CLOSED,
            "Tenant tenant-1 is closed; owned objects are read-only", 409))
            .when(mutationGuard).assertTenantOpen("tenant-1");

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k_tc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].error_code").value("TENANT_CLOSED"))
                .andExpect(jsonPath("$.succeeded.length()").value(0));

        verify(webhookService, never()).update(anyString(), any());
        verify(webhookService, never()).delete(anyString());
    }

    // v0.1.25.36 — Cascade Rule 2 on update: CLOSED owner → 409.
    @Test
    void updateWebhook_closedTenant_returns409_tenantClosed() throws Exception {
        doThrow(new GovernanceException(ErrorCode.TENANT_CLOSED,
            "Tenant tenant-1 is closed; owned objects are read-only", 409))
            .when(mutationGuard).assertOpenForWebhook("whsub_1");

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TENANT_CLOSED"));

        verify(webhookService, never()).update(anyString(), any());
    }

    // --- Webhook lifecycle event-emission contract (spec v0.1.25.33) ---

    @Test
    void createWebhook_emitsWebhookCreatedEvent() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookCreateResponse response = WebhookCreateResponse.builder()
            .subscription(sub).signingSecret("whsec_abc").build();
        when(webhookService.create(eq("tenant-1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        ArgumentCaptor<String> corr = ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_CREATED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), payload.capture(), corr.capture(), any());
        java.util.Map<String, Object> p = payload.getValue();
        org.assertj.core.api.Assertions.assertThat(p)
            .containsEntry("subscription_id", "whsub_1")
            .containsEntry("tenant_id", "tenant-1")
            .containsEntry("new_status", "ACTIVE");
        org.assertj.core.api.Assertions.assertThat(corr.getValue())
            .isEqualTo("webhook_create:whsub_1");
    }

    @Test
    void updateWebhook_statusFlip_active_to_paused_emitsWebhookPaused() throws Exception {
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.PAUSED).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        ArgumentCaptor<String> corr = ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_PAUSED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), payload.capture(), corr.capture(), any());
        java.util.Map<String, Object> p = payload.getValue();
        org.assertj.core.api.Assertions.assertThat(p)
            .containsEntry("previous_status", "ACTIVE")
            .containsEntry("new_status", "PAUSED");
        org.assertj.core.api.Assertions.assertThat(corr.getValue())
            .startsWith("webhook_update:whsub_1:");
    }

    @Test
    void updateWebhook_statusFlip_paused_to_active_emitsWebhookResumed() throws Exception {
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.PAUSED).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.WEBHOOK_RESUMED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), any(), anyString(), any());
    }

    @Test
    void updateWebhook_statusOnlyFlip_emitsEmptyChangedFields() throws Exception {
        // Spec §6278: changed_fields lists the non-status mutations. A pure
        // status flip (ACTIVE → PAUSED, no other fields in the request body)
        // MUST emit an empty changed_fields array.
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.PAUSED).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_PAUSED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), payload.capture(), anyString(), any());
        @SuppressWarnings("unchecked")
        java.util.List<String> changed = (java.util.List<String>) payload.getValue().get("changed_fields");
        org.assertj.core.api.Assertions.assertThat(changed).isEmpty();
    }

    @Test
    void updateWebhook_propertyOnly_emitsWebhookUpdated() throws Exception {
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/new-wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/new-wh\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_UPDATED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), payload.capture(), anyString(), any());
        java.util.Map<String, Object> p = payload.getValue();
        @SuppressWarnings("unchecked")
        java.util.List<String> changed = (java.util.List<String>) p.get("changed_fields");
        org.assertj.core.api.Assertions.assertThat(changed).contains("url");
    }

    @Test
    void updateWebhook_noop_doesNotEmit() throws Exception {
        // Spec v0.1.25.33: a PATCH that mutates zero fields AND does not flip
        // status MUST NOT produce an Event. Empty body here.
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(prior);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(eventService, never()).emit(any(EventType.class), anyString(), any(),
            anyString(), any(Actor.class), any(), anyString(), any());
    }

    @Test
    void updateWebhook_statusFlip_disabled_to_active_emitsWebhookResumed() throws Exception {
        // Spec v0.1.25.33: operator re-enable of an auto-disabled subscription
        // (DISABLED → ACTIVE) emits webhook.resumed, matching the PAUSED →
        // ACTIVE transition semantics. The dispatcher owns webhook.disabled.
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.DISABLED).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_RESUMED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), payload.capture(), anyString(), any());
        java.util.Map<String, Object> p = payload.getValue();
        org.assertj.core.api.Assertions.assertThat(p)
            .containsEntry("previous_status", "DISABLED")
            .containsEntry("new_status", "ACTIVE");
    }

    @Test
    void deleteWebhook_emitsWebhookDeleted() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(sub);

        mockMvc.perform(delete("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        ArgumentCaptor<String> corr = ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_DELETED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), payload.capture(), corr.capture(), any());
        java.util.Map<String, Object> p = payload.getValue();
        org.assertj.core.api.Assertions.assertThat(p)
            .containsEntry("subscription_id", "whsub_1")
            .containsEntry("previous_status", "ACTIVE");
        org.assertj.core.api.Assertions.assertThat(corr.getValue())
            .isEqualTo("webhook_delete:whsub_1");
    }

    @Test
    void bulkActionWebhooks_pause_emitsWebhookPausedEventPerRow() throws Exception {
        when(idempotencyStore.lookup(eq("webhooks-bulk"), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(isNull(), eq(WebhookStatus.ACTIVE), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.ACTIVE), webhookRow("w2", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.ACTIVE));
        when(webhookRepository.findById("w2")).thenReturn(webhookRow("w2", WebhookStatus.ACTIVE));
        when(webhookService.update(anyString(), any())).thenReturn(webhookRow("w1", WebhookStatus.PAUSED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k_emit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(2));

        ArgumentCaptor<String> corr = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(eventService, times(2)).emit(eq(EventType.WEBHOOK_PAUSED),
                eq("tenant-1"), isNull(), eq("cycles-admin"), any(Actor.class),
                payload.capture(), corr.capture(), any());
        // One correlation_id per invocation, shared across all per-row emits.
        org.junit.jupiter.api.Assertions.assertEquals(1, new java.util.HashSet<>(corr.getAllValues()).size());
        org.assertj.core.api.Assertions.assertThat(corr.getValue())
            .startsWith("webhook_bulk_action:pause:");
        java.util.Map<String, Object> p = payload.getValue();
        org.assertj.core.api.Assertions.assertThat(p)
            .containsEntry("tenant_id", "tenant-1")
            .containsEntry("previous_status", "ACTIVE")
            .containsEntry("new_status", "PAUSED");
    }

    @Test
    void bulkActionWebhooks_resume_emitsWebhookResumedEventPerRow() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.PAUSED)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.PAUSED));
        when(webhookService.update(anyString(), any())).thenReturn(webhookRow("w1", WebhookStatus.ACTIVE));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"PAUSED\"},\"action\":\"RESUME\",\"idempotency_key\":\"k_emit_r\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> corr = ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_RESUMED),
                eq("tenant-1"), isNull(), eq("cycles-admin"), any(Actor.class),
                any(), corr.capture(), any());
        org.assertj.core.api.Assertions.assertThat(corr.getValue())
            .startsWith("webhook_bulk_action:resume:");
    }

    @Test
    void bulkActionWebhooks_delete_emitsWebhookDeletedEventPerRow() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.ACTIVE)));
        // Fresh live-read parity with PAUSE/RESUME — bulk DELETE now calls
        // findById to capture live previous_status at time of deletion.
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.ACTIVE));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"tenant_id\":\"tenant-1\"},\"action\":\"DELETE\",\"idempotency_key\":\"k_emit_d\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> corr = ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_DELETED),
                eq("tenant-1"), isNull(), eq("cycles-admin"), any(Actor.class),
                any(), corr.capture(), any());
        org.assertj.core.api.Assertions.assertThat(corr.getValue())
            .startsWith("webhook_bulk_action:delete:");
    }

    @Test
    void bulkActionWebhooks_delete_liveReadMissing_rowSkipped_doesNotEmit() throws Exception {
        // Concurrent delete between match and apply: findById throws, row
        // lands in skipped[] with ALREADY_DELETED and MUST NOT emit.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("w1"))
                .thenThrow(new GovernanceException(ErrorCode.WEBHOOK_NOT_FOUND, "gone", 404));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"tenant_id\":\"tenant-1\"},\"action\":\"DELETE\",\"idempotency_key\":\"k_race\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_DELETED"));

        verify(eventService, never()).emit(any(EventType.class), anyString(), any(),
                anyString(), any(), any(), any(), any());
    }

    @Test
    void bulkActionWebhooks_skippedRow_doesNotEmit() throws Exception {
        // Skipped rows (ALREADY_IN_TARGET_STATE / ALREADY_DELETED) MUST NOT
        // emit — emission is bound to actual state transition.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.PAUSED)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.PAUSED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"PAUSED\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k_skip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped.length()").value(1));

        verify(eventService, never()).emit(any(EventType.class), anyString(), any(),
                anyString(), any(), any(), any(), any());
    }

    @Test
    void bulkActionWebhooks_failedRow_doesNotEmit() throws Exception {
        // Failed rows (INVALID_TRANSITION, TENANT_CLOSED, INTERNAL_ERROR)
        // MUST NOT emit a lifecycle event — state did not transition.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("w1", WebhookStatus.DISABLED)));
        when(webhookRepository.findById("w1")).thenReturn(webhookRow("w1", WebhookStatus.DISABLED));

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"DISABLED\"},\"action\":\"RESUME\",\"idempotency_key\":\"k_fail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed.length()").value(1));

        verify(eventService, never()).emit(any(EventType.class), anyString(), any(),
                anyString(), any(), any(), any(), any());
    }

    @Test
    void webhookEmit_exceptionInEventService_doesNotBreakResponse() throws Exception {
        // Belt-and-suspenders: emit failure is swallowed and logged. Operator
        // response must still be 201/200/204 on the underlying mutation.
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        WebhookCreateResponse response = WebhookCreateResponse.builder()
            .subscription(sub).signingSecret("whsec_abc").build();
        when(webhookService.create(eq("tenant-1"), any())).thenReturn(response);
        doThrow(new RuntimeException("event bus down"))
            .when(eventService).emit(any(EventType.class), anyString(), any(),
                anyString(), any(), any(), any(), any());

        mockMvc.perform(post("/v1/admin/webhooks")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void bulkActionWebhooks_genericException_classifiedAsInternalError() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(WebhookBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(webhookRepository.matchForBulk(any(), any(), any(), any(), eq(500)))
                .thenReturn(List.of(webhookRow("whsub_1", WebhookStatus.ACTIVE)));
        when(webhookRepository.findById("whsub_1"))
                .thenReturn(webhookRow("whsub_1", WebhookStatus.ACTIVE));
        doThrow(new RuntimeException("redis-down"))
                .when(webhookService).update(eq("whsub_1"), any());

        mockMvc.perform(post("/v1/admin/webhooks/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"PAUSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("INTERNAL_ERROR"));
    }

    // v0.1.25.40 B2/B3: changed_fields is a true diff, not request-presence.
    // PATCHing a field with its current value is a no-op for that field.
    @Test
    void updateWebhook_patchWithSameUrlValue_excludesUrlFromChangedFields() throws Exception {
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .name("unchanged-name")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .name("new-name")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"name\":\"new-name\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_UPDATED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), payload.capture(), anyString(), any());
        @SuppressWarnings("unchecked")
        java.util.List<String> changed =
            (java.util.List<String>) payload.getValue().get("changed_fields");
        org.assertj.core.api.Assertions.assertThat(changed).containsExactly("name");
    }

    // v0.1.25.40 B2/B3: a PATCH that resends every field with its current
    // value and does not flip status is a no-op — MUST NOT emit.
    @Test
    void updateWebhook_patchAllFieldsWithSameValues_doesNotEmit() throws Exception {
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1")
            .url("https://example.com/wh").name("fixed")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(prior);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"name\":\"fixed\"}"))
                .andExpect(status().isOk());

        verify(eventService, never()).emit(any(EventType.class), anyString(), any(),
            anyString(), any(Actor.class), any(), anyString(), any());
    }

    // v0.1.25.40 B1: single-op lifecycle emits now attribute to the
    // authenticated API key, matching bulk-path actor parity.
    @Test
    void createWebhook_emitCarriesAuthenticatedKeyIdOnActor() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .eventTypes(List.of(EventType.BUDGET_CREATED)).status(WebhookStatus.ACTIVE)
            .createdAt(Instant.now()).build();
        when(webhookService.create(eq("tenant-1"), any()))
            .thenReturn(WebhookCreateResponse.builder().subscription(sub).signingSecret("whsec_abc").build());

        mockMvc.perform(post("/v1/admin/webhooks?tenant_id=tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .requestAttr("authenticated_key_id", "cyc_ak_test_key_42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/wh\",\"event_types\":[\"budget.created\"]}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<Actor> actorCaptor = ArgumentCaptor.forClass(Actor.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_CREATED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), actorCaptor.capture(), any(), anyString(), any());
        org.assertj.core.api.Assertions.assertThat(actorCaptor.getValue().getKeyId())
            .isEqualTo("cyc_ak_test_key_42");
    }

    // v0.1.25.40 B4: the "no-req" literal fallback is gone. In the normal
    // path RequestIdFilter populates a valid request_id which we pass through
    // verbatim; the UUID fallback only fires if the filter chain is bypassed,
    // which is unreachable through MockMvc.
    @Test
    void updateWebhook_correlationIdNeverContainsNoReqLiteral() throws Exception {
        WebhookSubscription prior = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/wh")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_1").tenantId("tenant-1").url("https://example.com/new")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        when(webhookService.get("whsub_1")).thenReturn(prior);
        when(webhookService.update(eq("whsub_1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/webhooks/whsub_1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/new\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(eq(EventType.WEBHOOK_UPDATED), eq("tenant-1"), isNull(),
            eq("cycles-admin"), any(Actor.class), any(), correlationCaptor.capture(), any());
        org.assertj.core.api.Assertions.assertThat(correlationCaptor.getValue())
            .startsWith("webhook_update:whsub_1:")
            .doesNotContain("no-req");
    }
}
