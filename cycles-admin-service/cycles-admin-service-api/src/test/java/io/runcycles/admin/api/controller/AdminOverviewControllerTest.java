package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.AdminOverviewService;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.shared.AdminOverviewResponse;
import io.runcycles.admin.model.shared.AdminOverviewResponse.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import io.runcycles.admin.api.contract.ContractValidationConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminOverviewController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class AdminOverviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AdminOverviewService overviewService;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void getOverview_withAdminKey_returnsOverview() throws Exception {
        AdminOverviewResponse overview = AdminOverviewResponse.builder()
                .asOf(Instant.parse("2026-04-08T10:30:00Z"))
                .eventWindowSeconds(3600)
                .tenantCounts(TenantCounts.builder().total(3).active(2).suspended(1).closed(0).build())
                .budgetCounts(BudgetCounts.builder().total(5).active(4).frozen(1).closed(0).overLimit(1).withDebt(1).byUnit(Map.of("USD_MICROCENTS", 3, "TOKENS", 2)).build())
                .overLimitScopes(List.of(OverLimitScope.builder().scope("tenant:acme/agent:bot").unit(io.runcycles.admin.model.shared.UnitEnum.USD_MICROCENTS).allocated(10000).remaining(-500).debt(500).build()))
                .debtScopes(List.of(DebtScope.builder().scope("tenant:acme/agent:bot").unit(io.runcycles.admin.model.shared.UnitEnum.TOKENS).debt(1500).overdraftLimit(5000).build()))
                .webhookCounts(WebhookCounts.builder().total(2).active(1).disabled(1).withFailures(1).build())
                .failingWebhooks(List.of(FailingWebhook.builder().subscriptionId("wh_1").url("https://example.com/hook").consecutiveFailures(5).build()))
                .eventCounts(EventCounts.builder().totalRecent(42).byCategory(Map.of("budget", 20, "reservation", 15)).build())
                .recentDenials(List.of())
                .recentExpiries(List.of())
                .build();

        when(overviewService.buildOverview()).thenReturn(overview);

        mockMvc.perform(get("/v1/admin/overview")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_window_seconds").value(3600))
                .andExpect(jsonPath("$.tenant_counts.total").value(3))
                .andExpect(jsonPath("$.tenant_counts.active").value(2))
                .andExpect(jsonPath("$.budget_counts.total").value(5))
                .andExpect(jsonPath("$.budget_counts.over_limit").value(1))
                .andExpect(jsonPath("$.budget_counts.with_debt").value(1))
                .andExpect(jsonPath("$.over_limit_scopes[0].scope").value("tenant:acme/agent:bot"))
                .andExpect(jsonPath("$.debt_scopes[0].debt").value(1500))
                .andExpect(jsonPath("$.webhook_counts.with_failures").value(1))
                .andExpect(jsonPath("$.failing_webhooks[0].consecutive_failures").value(5))
                .andExpect(jsonPath("$.event_counts.total_recent").value(42))
                .andExpect(jsonPath("$.event_counts.by_category.budget").value(20));
    }

    @Test
    void getOverview_withoutAdminKey_returns401() throws Exception {
        mockMvc.perform(get("/v1/admin/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOverview_withApiKey_returns401() throws Exception {
        // Overview is AdminKeyAuth only — API key should not work
        mockMvc.perform(get("/v1/admin/overview")
                        .header("X-Cycles-API-Key", "some-key"))
                .andExpect(status().isUnauthorized());
    }
}
