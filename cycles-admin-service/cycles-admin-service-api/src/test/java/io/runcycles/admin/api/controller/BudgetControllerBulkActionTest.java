package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.contract.ContractValidationConfig;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.TerminalOwnerMutationGuard;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.idempotency.IdempotencyStore;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetListFilters;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.budget.BudgetBulkActionResponse;
import io.runcycles.admin.model.budget.BudgetFundingRequest;
import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
import io.runcycles.admin.model.budget.FundingOperation;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.BulkActionRowOutcome;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.UnitEnum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for {@code POST /v1/admin/budgets/bulk-action} (spec
 * v0.1.25.26). Covers the envelope flow end-to-end under the controller
 * layer — happy paths per action, all three row-outcome buckets, the
 * four envelope-level 4xx gates (validation 400s, COUNT_MISMATCH 409,
 * LIMIT_EXCEEDED 400), idempotency replay, per-row classifier mapping,
 * audit metadata, and auth enforcement.
 */
@WebMvcTest(BudgetController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class BudgetControllerBulkActionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private BudgetRepository budgetRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;
    @MockitoBean private EventService eventService;
    @MockitoBean private IdempotencyStore idempotencyStore;
    @MockitoBean private TerminalOwnerMutationGuard mutationGuard;

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String TENANT = "acme";
    private static final String BULK_NS = "budgets-bulk";

    private static BudgetLedger ledger(String scope, long allocated, long spent, Long debt) {
        BudgetLedger.BudgetLedgerBuilder b = BudgetLedger.builder()
                .ledgerId("led-" + scope).tenantId(TENANT)
                .scope(scope).unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, allocated))
                .spent(new Amount(UnitEnum.USD_MICROCENTS, spent))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, allocated - spent))
                .status(BudgetStatus.ACTIVE);
        if (debt != null) b.debt(new Amount(UnitEnum.USD_MICROCENTS, debt));
        return b.build();
    }

    private static BudgetLedger ledger(String scope) {
        return ledger(scope, 1_000_000L, 0L, 0L);
    }

    private static String body(String filterJson, String actionBlock, String idemKey, String extra) {
        StringBuilder sb = new StringBuilder("{\"filter\":").append(filterJson)
                .append(",\"action\":\"").append(actionBlock).append("\"");
        if (extra != null && !extra.isEmpty()) sb.append(",").append(extra);
        sb.append(",\"idempotency_key\":\"").append(idemKey).append("\"}");
        return sb.toString();
    }

    private static String filter(String extras) {
        String base = "{\"tenant_id\":\"" + TENANT + "\"";
        if (extras != null && !extras.isEmpty()) base += "," + extras;
        return base + "}";
    }

    private void noReplay() {
        when(idempotencyStore.lookup(eq(BULK_NS), anyString(), eq(BudgetBulkActionResponse.class)))
                .thenReturn(Optional.empty());
    }

    // -------- Happy paths: one per FundingOperation ---------------------

    @Test
    void bulkAction_credit_happyPath_returns200_withSucceededRow() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("CREDIT"))
                .andExpect(jsonPath("$.total_matched").value(1))
                .andExpect(jsonPath("$.succeeded.length()").value(1))
                .andExpect(jsonPath("$.succeeded[0].id").value("led-tenant:acme/workspace:eng"))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.idempotency_key").value("k1"));

        ArgumentCaptor<BudgetFundingRequest> fundArg = ArgumentCaptor.forClass(BudgetFundingRequest.class);
        verify(budgetRepository).fund(eq(TENANT), eq("tenant:acme/workspace:eng"),
                eq(UnitEnum.USD_MICROCENTS), fundArg.capture());
        // derived per-row idempotency key = bulkKey:scope:unit
        assertEquals("k1:tenant:acme/workspace:eng:USD_MICROCENTS",
                fundArg.getValue().getIdempotencyKey());
        assertEquals(FundingOperation.CREDIT, fundArg.getValue().getOperation());
        verify(idempotencyStore).store(eq(BULK_NS), eq("k1"), any(BudgetBulkActionResponse.class));
    }

    @Test
    void bulkAction_debit_happyPath_returns200() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng", 1_000L, 100L, 0L)));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "DEBIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DEBIT"))
                .andExpect(jsonPath("$.succeeded.length()").value(1));
    }

    @Test
    void bulkAction_reset_happyPath_returns200() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "RESET", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESET"))
                .andExpect(jsonPath("$.succeeded.length()").value(1));
    }

    @Test
    void bulkAction_repayDebt_happyPath_returns200() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng", 1_000L, 0L, 250L)));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "REPAY_DEBT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":250}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("REPAY_DEBT"))
                .andExpect(jsonPath("$.succeeded.length()").value(1));
    }

    @Test
    void bulkAction_resetSpent_happyPath_returns200_withSpentPayload() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng", 1_000L, 500L, 0L)));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "RESET_SPENT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},"
                                        + "\"spent\":{\"unit\":\"USD_MICROCENTS\",\"amount\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESET_SPENT"))
                .andExpect(jsonPath("$.succeeded.length()").value(1));

        ArgumentCaptor<BudgetFundingRequest> fundArg = ArgumentCaptor.forClass(BudgetFundingRequest.class);
        verify(budgetRepository).fund(eq(TENANT), anyString(), eq(UnitEnum.USD_MICROCENTS), fundArg.capture());
        assertEquals(0L, fundArg.getValue().getSpent().getAmount());
    }

    // -------- Per-row classification ------------------------------------

    @Test
    void bulkAction_unitMismatch_landsInFailed_INVALID_TRANSITION() throws Exception {
        noReplay();
        BudgetLedger row = BudgetLedger.builder()
                .ledgerId("led-a").tenantId(TENANT).scope("tenant:acme/workspace:eu")
                .unit(UnitEnum.TOKENS)
                .allocated(new Amount(UnitEnum.TOKENS, 1000L))
                .status(BudgetStatus.ACTIVE).build();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(row));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].id").value("led-a"))
                .andExpect(jsonPath("$.failed[0].error_code").value("INVALID_TRANSITION"))
                .andExpect(jsonPath("$.failed[0].message",
                        org.hamcrest.Matchers.containsString("unit mismatch")));

        verify(budgetRepository, never()).fund(anyString(), anyString(), any(), any());
    }

    @Test
    void bulkAction_repayDebtOnZeroDebt_landsInSkipped_ALREADY_IN_TARGET_STATE() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng", 1_000L, 0L, 0L)));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "REPAY_DEBT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value("led-tenant:acme/workspace:eng"))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_IN_TARGET_STATE"))
                .andExpect(jsonPath("$.succeeded.length()").value(0));

        verify(budgetRepository, never()).fund(anyString(), anyString(), any(), any());
    }

    @Test
    void bulkAction_debitIntoNegative_landsInFailed_BUDGET_EXCEEDED() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng", 100L, 50L, 0L)));
        when(budgetRepository.fund(eq(TENANT), anyString(), any(), any()))
                .thenThrow(GovernanceException.insufficientFunds("tenant:acme/workspace:eng"));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "DEBIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].error_code").value("BUDGET_EXCEEDED"));
    }

    @Test
    void bulkAction_frozenLedger_landsInFailed_INVALID_TRANSITION() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));
        when(budgetRepository.fund(eq(TENANT), anyString(), any(), any()))
                .thenThrow(new GovernanceException(ErrorCode.BUDGET_FROZEN,
                        "Budget is frozen: tenant:acme/workspace:eng", 409));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("INVALID_TRANSITION"));
    }

    @Test
    void bulkAction_closedLedger_landsInFailed_INVALID_TRANSITION() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));
        when(budgetRepository.fund(eq(TENANT), anyString(), any(), any()))
                .thenThrow(new GovernanceException(ErrorCode.BUDGET_CLOSED,
                        "Budget is closed", 409));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("INVALID_TRANSITION"));
    }

    @Test
    void bulkAction_ledgerRaceDeleted_landsInFailed_NOT_FOUND() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));
        when(budgetRepository.fund(eq(TENANT), anyString(), any(), any()))
                .thenThrow(new GovernanceException(ErrorCode.BUDGET_NOT_FOUND, "gone", 404));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("NOT_FOUND"));
    }

    @Test
    void bulkAction_permissionDenied_classifiedAsPERMISSION_DENIED() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));
        when(budgetRepository.fund(eq(TENANT), anyString(), any(), any()))
                .thenThrow(new GovernanceException(ErrorCode.INSUFFICIENT_PERMISSIONS, "denied", 403));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("PERMISSION_DENIED"));
    }

    @Test
    void bulkAction_genericRuntimeException_classifiedAsINTERNAL_ERROR() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));
        when(budgetRepository.fund(eq(TENANT), anyString(), any(), any()))
                .thenThrow(new RuntimeException("redis-down"));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("INTERNAL_ERROR"));
    }

    @Test
    void bulkAction_unknownGovernanceErrorCode_classifiedAsINTERNAL_ERROR() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));
        // ErrorCode not in the classifier switch → default branch
        when(budgetRepository.fund(eq(TENANT), anyString(), any(), any()))
                .thenThrow(new GovernanceException(ErrorCode.INTERNAL_ERROR, "boom", 500));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("INTERNAL_ERROR"));
    }

    // -------- Envelope gates: LIMIT_EXCEEDED, COUNT_MISMATCH, validation -

    @Test
    void bulkAction_over500Matches_returns400_LIMIT_EXCEEDED_noWrites() throws Exception {
        noReplay();
        List<BudgetLedger> oversized = new ArrayList<>();
        for (int i = 0; i < 501; i++) oversized.add(ledger("tenant:acme/workspace:s" + i));
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(oversized);

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.total_matched").value(501));

        verify(budgetRepository, never()).fund(anyString(), anyString(), any(), any());
        verify(idempotencyStore, never()).store(anyString(), anyString(), any());
    }

    @Test
    void bulkAction_expectedCountMismatch_returns409_COUNT_MISMATCH_noWrites() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:a"), ledger("tenant:acme/workspace:b")));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500},"
                                        + "\"expected_count\":5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("COUNT_MISMATCH"))
                .andExpect(jsonPath("$.details.total_matched").value(2));

        verify(budgetRepository, never()).fund(anyString(), anyString(), any(), any());
        verify(idempotencyStore, never()).store(anyString(), anyString(), any());
    }

    @Test
    void bulkAction_missingAmount_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(budgetRepository, never()).matchForBulk(anyString(), any(), anyInt());
    }

    @Test
    void bulkAction_spentOnNonResetSpentAction_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500},"
                                        + "\"spent\":{\"unit\":\"USD_MICROCENTS\",\"amount\":0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void bulkAction_resetSpentWithNegativeSpent_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "RESET_SPENT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},"
                                        + "\"spent\":{\"unit\":\"USD_MICROCENTS\",\"amount\":-1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void bulkAction_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{},\"action\":\"CREDIT\","
                                + "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500},"
                                + "\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(budgetRepository, never()).matchForBulk(anyString(), any(), anyInt());
    }

    @Test
    void bulkAction_utilizationMinGreaterThanMax_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter("\"utilization_min\":0.9,\"utilization_max\":0.1"),
                                "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(budgetRepository, never()).matchForBulk(anyString(), any(), anyInt());
    }

    @Test
    void bulkAction_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":" + filter("") + ",\"action\":\"CREDIT\","
                                + "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void bulkAction_searchOver128Chars_returns400() throws Exception {
        String over = "x".repeat(129);
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter("\"search\":\"" + over + "\""),
                                "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(budgetRepository, never()).matchForBulk(anyString(), any(), anyInt());
    }

    // -------- Idempotency replay ----------------------------------------

    @Test
    void bulkAction_idempotencyReplay_returnsCachedEnvelope_noRepoWork() throws Exception {
        BudgetBulkActionResponse cached = BudgetBulkActionResponse.builder()
                .action(FundingOperation.CREDIT)
                .totalMatched(3)
                .succeeded(List.of(BulkActionRowOutcome.builder().id("s1").build()))
                .failed(List.of())
                .skipped(List.of())
                .idempotencyKey("k1")
                .build();
        when(idempotencyStore.lookup(eq(BULK_NS), eq("k1"), eq(BudgetBulkActionResponse.class)))
                .thenReturn(Optional.of(cached));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_matched").value(3))
                .andExpect(jsonPath("$.succeeded[0].id").value("s1"));

        verify(budgetRepository, never()).matchForBulk(anyString(), any(), anyInt());
        verify(budgetRepository, never()).fund(anyString(), anyString(), any(), any());
        verify(idempotencyStore, never()).store(anyString(), anyString(), any());
        verify(auditRepository, never()).log(any());
    }

    // -------- Audit metadata --------------------------------------------

    @Test
    void bulkAction_emitsAuditLogEntryWithMetadataKeys() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogEntry> auditArg = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(auditArg.capture());
        AuditLogEntry entry = auditArg.getValue();
        assertEquals("bulkActionBudgets", entry.getOperation());
        assertEquals("budget", entry.getResourceType());
        assertEquals("bulk-action", entry.getResourceId());
        assertEquals(TENANT, entry.getTenantId());
        assertEquals("CREDIT", entry.getMetadata().get("action"));
        assertEquals(1, entry.getMetadata().get("total_matched"));
        assertEquals(1, entry.getMetadata().get("succeeded"));
        assertEquals(0, entry.getMetadata().get("failed"));
        assertEquals(0, entry.getMetadata().get("skipped"));
        assertEquals("k1", entry.getMetadata().get("idempotency_key"));
        // v0.1.25.30 enrichment — per-row outcomes + filter echo + wall-clock
        assertEquals(List.of("led-tenant:acme/workspace:eng"), entry.getMetadata().get("succeeded_ids"));
        assertEquals(List.of(), entry.getMetadata().get("failed_rows"));
        assertEquals(List.of(), entry.getMetadata().get("skipped_rows"));
        assertThat(entry.getMetadata()).containsKey("filter");
        assertThat((Long) entry.getMetadata().get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    // -------- Auth ------------------------------------------------------

    @Test
    void bulkAction_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isUnauthorized());

        verify(budgetRepository, never()).matchForBulk(anyString(), any(), anyInt());
    }

    @Test
    void bulkAction_tenantKeyRejected_returns401() throws Exception {
        // Spec v0.1.25.26: AdminKeyAuth only. Tenant API key header must not
        // grant access — interceptor routes POST /v1/admin/budgets/bulk-action
        // through the admin path, which requires X-Admin-API-Key.
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId(TENANT).keyId("key_1")
                        .permissions(List.of("budgets:write")).build());

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k1",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isUnauthorized());

        verify(budgetRepository, never()).matchForBulk(anyString(), any(), anyInt());
    }

    // -------- Cascade Rule 2: closed-owner per-row rejection -----------
    // v0.1.25.36 (spec v0.1.25.29 Rule 2): any ledger whose owning tenant
    // is CLOSED lands in failed[] with TENANT_CLOSED; other rows proceed.
    @Test
    void bulkAction_closedTenantRow_landsInFailed_tenantClosed() throws Exception {
        noReplay();
        when(budgetRepository.matchForBulk(eq(TENANT), any(BudgetListFilters.class), eq(500)))
                .thenReturn(List.of(ledger("tenant:acme/workspace:eng")));
        doThrow(new GovernanceException(ErrorCode.TENANT_CLOSED,
            "Tenant " + TENANT + " is closed; owned objects are read-only", 409))
            .when(mutationGuard).assertTenantOpen(TENANT);

        mockMvc.perform(post("/v1/admin/budgets/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(filter(""), "CREDIT", "k_tc",
                                "\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].error_code").value("TENANT_CLOSED"))
                .andExpect(jsonPath("$.succeeded.length()").value(0));

        verify(budgetRepository, never()).fund(anyString(), anyString(), any(), any());
    }
}
