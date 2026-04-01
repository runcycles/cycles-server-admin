package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.UnitEnum;
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

@WebMvcTest(BudgetController.class)
class BudgetControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private BudgetRepository budgetRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;
    @MockitoBean private EventService eventService;

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("admin:read", "admin:write", "balances:read")).build());
    }

    @Test
    void createBudget_returns201() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("org/team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("t1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"org/team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ledger_id").value("led-1"))
                .andExpect(jsonPath("$.scope").value("org/team1"));
    }

    @Test
    void createBudget_duplicate_returns409() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.create(eq("t1"), any()))
                .thenThrow(GovernanceException.duplicateResource("Budget", "org/team1"));

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"org/team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void createBudget_noApiKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"org/team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listBudgets_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("org/team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 800L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.list(eq("t1"), any(), any(), any(), any(), anyInt())).thenReturn(List.of(ledger));

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgers").isArray())
                .andExpect(jsonPath("$.ledgers[0].ledger_id").value("led-1"));
    }

    @Test
    void createBudget_scopeFilterDenied_returns403() throws Exception {
        when(apiKeyRepository.validate("restricted-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("admin:read", "admin:write", "balances:read"))
                        .scopeFilter(List.of("workspace:eng"))
                        .build());

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "restricted-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:acme/workspace:sales\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void createBudget_scopeFilterAllowed_returns201() throws Exception {
        when(apiKeyRepository.validate("restricted-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("admin:read", "admin:write", "balances:read"))
                        .scopeFilter(List.of("workspace:eng"))
                        .build());
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:acme/workspace:eng").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("t1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "restricted-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:acme/workspace:eng\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated());
    }

    // ========== POST /v1/admin/budgets/fund ==========

    @Test
    void fundBudget_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("org_team1"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "org_team1")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("CREDIT"))
                .andExpect(jsonPath("$.new_allocated.amount").value(2000));
    }

    @Test
    void fundBudget_frozen_returns409() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetFrozen("scope"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUDGET_FROZEN"));
    }

    @Test
    void fundBudget_insufficientFunds_returns409() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.insufficientFunds("scope"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DEBIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":999999}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUDGET_EXCEEDED"));
    }

    @Test
    void createBudget_logsAuditEntry() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("org/team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("t1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"org/team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                "createBudget".equals(entry.getOperation()) &&
                "t1".equals(entry.getTenantId()) &&
                entry.getStatus() == 201));
    }

    @Test
    void fundBudget_logsAuditEntry() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "fundBudget".equals(entry.getOperation()) &&
                entry.getStatus() == 200));
    }

    @Test
    void listBudgets_usesAuthenticatedTenantId_ignoresUserSupplied() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("tenant_id", "attacker-tenant"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), any(), any(), any(), anyInt());
    }

    @Test
    void listBudgets_emptyResult_hasMoreFalseAndNoCursor() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgers").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listBudgets_limitClampedToMax100() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), any(), any(), eq(100))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), any(), any(), any(), eq(100));
    }

    @Test
    void listBudgets_limitClampedToMin1() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), any(), any(), eq(1))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), any(), any(), any(), eq(1));
    }

    @Test
    void fundBudget_notFound_returns404() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.fund(eq("t1"), eq("missing"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetNotFound("missing"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "missing")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BUDGET_NOT_FOUND"));
    }

    @Test
    void listBudgets_withCursorParam_passesToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), any(), eq("led-abc"), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("cursor", "led-abc"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), any(), any(), eq("led-abc"), anyInt());
    }

    @Test
    void listBudgets_withStatusFilter_passesToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), eq(BudgetStatus.FROZEN), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("status", "FROZEN"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), any(), eq(BudgetStatus.FROZEN), any(), anyInt());
    }

    @Test
    void createBudget_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("org/team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("t1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"org/team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getRequestId() != null &&
                entry.getRequestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") &&
                "createBudget".equals(entry.getOperation())));
    }

    @Test
    void fundBudget_auditEntry_includesKeyId() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "fundBudget".equals(entry.getOperation()) &&
                "t1".equals(entry.getTenantId()) &&
                "key_1".equals(entry.getKeyId())));
    }

    @Test
    void fundBudget_debitOperation_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.DEBIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DEBIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("DEBIT"));
    }

    @Test
    void fundBudget_resetOperation_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.RESET)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 5000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1500L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 5000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":5000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("RESET"));
    }

    @Test
    void fundBudget_repayDebtOperation_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.REPAY_DEBT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1500L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, -200L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 300L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"REPAY_DEBT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("REPAY_DEBT"));
    }

    @Test
    void listBudgets_withScopePrefixFilter_passesToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), eq("org/"), any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("scope_prefix", "org/"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), eq("org/"), any(), any(), any(), anyInt());
    }

    @Test
    void listBudgets_withUnitFilter_passesToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), eq(UnitEnum.TOKENS), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("unit", "TOKENS"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), eq(UnitEnum.TOKENS), any(), any(), anyInt());
    }

    @Test
    void listBudgets_resultCountEqualsLimit_hasMoreTrueWithCursor() throws Exception {
        setupApiKeyAuth();
        BudgetLedger l1 = BudgetLedger.builder()
                .ledgerId("led-1").scope("a").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 800L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        BudgetLedger l2 = BudgetLedger.builder()
                .ledgerId("led-2").scope("b").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1500L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.list(eq("t1"), any(), any(), any(), any(), eq(2))).thenReturn(List.of(l1, l2));

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("led-2"));
    }

    @Test
    void fundBudget_budgetClosed_returns409() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetClosed("scope"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUDGET_CLOSED"));
    }

    @Test
    void fundBudget_workspaceScope_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 500000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 500000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("tenant:acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:acme/workspace:prod")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("CREDIT"))
                .andExpect(jsonPath("$.new_allocated.amount").value(500000));

        verify(budgetRepository).fund(eq("t1"), eq("tenant:acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any());
    }

    // ========== PATCH /v1/admin/budgets ==========

    @Test
    void updateBudget_returns200() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("org_team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 500000L))
                .overdraftLimit(new Amount(UnitEnum.USD_MICROCENTS, 50000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.update(isNull(), eq("org_team1"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(ledger);

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "org_team1")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":50000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledger_id").value("led-1"))
                .andExpect(jsonPath("$.overdraft_limit.amount").value(50000));
    }

    @Test
    void updateBudget_notFound_returns404() throws Exception {
        when(budgetRepository.update(isNull(), eq("missing"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetNotFound("missing:USD_MICROCENTS"));

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "missing")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":50000}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BUDGET_NOT_FOUND"));
    }

    @Test
    void updateBudget_closed_returns409() throws Exception {
        when(budgetRepository.update(isNull(), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetClosed("scope"));

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commit_overage_policy\":\"REJECT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUDGET_CLOSED"));
    }

    @Test
    void updateBudget_logsAuditEntry() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("scope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.update(isNull(), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(ledger);

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updateBudget".equals(entry.getOperation()) &&
                entry.getStatus() == 200));
    }

    @Test
    void updateBudget_noAdminKey_returns401() throws Exception {
        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateBudget_workspaceScope_returns200() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:acme/workspace:prod").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 500000L))
                .overdraftLimit(new Amount(UnitEnum.USD_MICROCENTS, 50000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.update(isNull(), eq("tenant:acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenReturn(ledger);

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "tenant:acme/workspace:prod")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":50000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("tenant:acme/workspace:prod"));

        verify(budgetRepository).update(isNull(), eq("tenant:acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any());
    }

    // ========== Unit mismatch validation ==========

    @Test
    void createBudget_unitMismatch_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"org/team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"TOKENS\",\"amount\":1000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNIT_MISMATCH"));
    }

    @Test
    void fundBudget_unitMismatch_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"TOKENS\",\"amount\":1000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNIT_MISMATCH"));
    }

    @Test
    void updateBudget_unitMismatch_returns400() throws Exception {
        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"TOKENS\",\"amount\":100}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNIT_MISMATCH"));
    }

    // ========== Event emission ==========

    @Test
    void createBudget_emitsEvent() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("org/team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("t1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"org/team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated());

        verify(eventService).emit(eq(EventType.BUDGET_CREATED), eq("t1"), eq("org/team1"), any(), any(), any(), any(), any());
    }

    @Test
    void updateBudget_emitsEvent() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("scope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.update(isNull(), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(ledger);

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.BUDGET_UPDATED), any(), eq("scope"), any(), any(), any(), any(), any());
    }

    @Test
    void fundBudget_emitsCreditEvent() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("t1"), eq("scope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "scope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.BUDGET_FUNDED), eq("t1"), eq("scope"), any(), any(), any(), any(), any());
    }
}
