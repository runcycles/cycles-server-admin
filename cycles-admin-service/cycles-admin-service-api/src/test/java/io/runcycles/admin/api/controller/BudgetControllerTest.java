package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.BudgetListFilters;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.shared.UnitEnum;
import org.mockito.ArgumentCaptor;
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

@WebMvcTest(BudgetController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
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
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("budgets:read", "budgets:write", "balances:read")).build());
    }

    @Test
    void createBudget_returns201() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("tenant-1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ledger_id").value("led-1"))
                .andExpect(jsonPath("$.scope").value("tenant:tenant-1/workspace:team1"));
    }

    @Test
    void createBudget_duplicate_returns409() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.create(eq("tenant-1"), any()))
                .thenThrow(GovernanceException.duplicateResource("Budget", "tenant:tenant-1/workspace:team1"));

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void createBudget_noApiKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lookupBudget_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 800L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.getByExactScope("tenant:tenant-1/workspace:team1", UnitEnum.USD_MICROCENTS)).thenReturn(ledger);

        mockMvc.perform(get("/v1/admin/budgets/lookup")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("scope", "tenant:tenant-1/workspace:team1")
                        .param("unit", "USD_MICROCENTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledger_id").value("led-1"))
                .andExpect(jsonPath("$.scope").value("tenant:tenant-1/workspace:team1"));
    }

    @Test
    void listBudgets_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 800L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any())).thenReturn(List.of(ledger));

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
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("budgets:read", "budgets:write", "balances:read"))
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
                        .valid(true).tenantId("tenant-1").keyId("key_1")
                        .permissions(List.of("budgets:read", "budgets:write", "balances:read"))
                        .scopeFilter(List.of("workspace:eng"))
                        .build());
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:eng").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("tenant-1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "restricted-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:eng\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
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
        when(budgetRepository.fund(eq("tenant-1"), eq("org_team1"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetFrozen("scope"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.insufficientFunds("scope"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("tenant-1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                "createBudget".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("tenant_id", "attacker-tenant"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any());
    }

    @Test
    void listBudgets_emptyResult_hasMoreFalseAndNoCursor() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any())).thenReturn(List.of());

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
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), eq(100), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"), any(BudgetListFilters.class), any(), eq(100), any());
    }

    @Test
    void listBudgets_limitClampedToMin1() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), eq(1), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"), any(BudgetListFilters.class), any(), eq(1), any());
    }

    @Test
    void fundBudget_notFound_returns404() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:missing"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetNotFound("missing"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:missing")
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
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), eq("led-abc"), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("cursor", "led-abc"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"), any(BudgetListFilters.class), eq("led-abc"), anyInt(), any());
    }

    @Test
    void listBudgets_withStatusFilter_passesToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && f.status() == BudgetStatus.FROZEN),
                any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("status", "FROZEN"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && f.status() == BudgetStatus.FROZEN),
                any(), anyInt(), any());
    }

    @Test
    void createBudget_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("tenant-1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "fundBudget".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":5000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("RESET"));
    }

    /** RESET_SPENT default flow (v0.1.25.17+): no `spent` field → period reset to 0. */
    @Test
    void fundBudget_resetSpentDefault_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.RESET_SPENT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousSpent(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newSpent(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET_SPENT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("RESET_SPENT"))
                .andExpect(jsonPath("$.previous_spent.amount").value(1000))
                .andExpect(jsonPath("$.new_spent.amount").value(0));
    }

    /** RESET_SPENT with explicit spent override (migration / proration / compensation). */
    @Test
    void fundBudget_resetSpentWithOverride_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.RESET_SPENT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 600L))
                .previousSpent(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newSpent(new Amount(UnitEnum.USD_MICROCENTS, 400L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET_SPENT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},\"spent\":{\"unit\":\"USD_MICROCENTS\",\"amount\":400}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.new_spent.amount").value(400));
    }

    /** Sending `spent` on a non-RESET_SPENT operation surfaces as a 400 — client bug. */
    @Test
    void fundBudget_spentFieldOnWrongOperation_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},\"spent\":{\"unit\":\"USD_MICROCENTS\",\"amount\":400}}"))
                .andExpect(status().isBadRequest());
    }

    /** Negative spent on RESET_SPENT — 400 from the controller-side validation. */
    @Test
    void fundBudget_negativeSpent_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET_SPENT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},\"spent\":{\"unit\":\"USD_MICROCENTS\",\"amount\":-1}}"))
                .andExpect(status().isBadRequest());
    }

    /** Mismatched spent unit vs budget unit — 400 unit mismatch. */
    @Test
    void fundBudget_spentUnitMismatch_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET_SPENT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},\"spent\":{\"unit\":\"TOKENS\",\"amount\":400}}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Audit + event payload enrichment for RESET_SPENT: the audit entry
     * MUST carry previous_spent / new_spent and the spent_override_provided
     * flag (true when the request supplied an explicit spent value, false on
     * default-to-zero). This is what compliance dashboards use to filter
     * routine rollovers from operator-set consumption adjustments.
     */
    @Test
    void fundBudget_resetSpentExplicit_auditMetadataIncludesSpentOverrideFlag() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.RESET_SPENT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 600L))
                .previousSpent(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newSpent(new Amount(UnitEnum.USD_MICROCENTS, 400L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET_SPENT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},\"spent\":{\"unit\":\"USD_MICROCENTS\",\"amount\":400}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "fundBudget".equals(entry.getOperation())
                && entry.getStatus() == 200
                && entry.getMetadata() != null
                && "RESET_SPENT".equals(entry.getMetadata().get("funding_operation"))
                && Long.valueOf(1000L).equals(entry.getMetadata().get("previous_spent"))
                && Long.valueOf(400L).equals(entry.getMetadata().get("new_spent"))
                && Boolean.TRUE.equals(entry.getMetadata().get("spent_override_provided"))));
    }

    /**
     * Audit metadata for the default-no-override case sets
     * spent_override_provided=false. This is the routine-rollover signal
     * that compliance dashboards can rely on to filter out high-frequency,
     * low-scrutiny billing-period boundaries from the operator-adjustments
     * stream.
     */
    @Test
    void fundBudget_resetSpentDefault_auditMetadataMarksOverrideFalse() throws Exception {
        setupApiKeyAuth();
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.RESET_SPENT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousSpent(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newSpent(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"RESET_SPENT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                entry.getMetadata() != null
                && "RESET_SPENT".equals(entry.getMetadata().get("funding_operation"))
                && Boolean.FALSE.equals(entry.getMetadata().get("spent_override_provided"))));
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
        when(budgetRepository.list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && "org/".equals(f.scopePrefix())),
                any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("scope_prefix", "org/"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && "org/".equals(f.scopePrefix())),
                any(), anyInt(), any());
    }

    @Test
    void listBudgets_withUnitFilter_passesToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && f.unit() == UnitEnum.TOKENS),
                any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("unit", "TOKENS"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && f.unit() == UnitEnum.TOKENS),
                any(), anyInt(), any());
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
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), eq(2), any())).thenReturn(List.of(l1, l2));

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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetClosed("tenant:tenant-1/workspace:tscope"));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-acme/workspace:prod")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":500000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("CREDIT"))
                .andExpect(jsonPath("$.new_allocated.amount").value(500000));

        verify(budgetRepository).fund(eq("tenant-1"), eq("tenant:tenant-acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any());
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
        when(budgetRepository.update(isNull(), eq("tenant:tenant-1/workspace:missing"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetNotFound("tenant:tenant-1/workspace:missing:USD_MICROCENTS"));

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "tenant:tenant-1/workspace:missing")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":50000}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BUDGET_NOT_FOUND"));
    }

    @Test
    void updateBudget_closed_returns409() throws Exception {
        when(budgetRepository.update(isNull(), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(GovernanceException.budgetClosed("tenant:tenant-1/workspace:tscope"));

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:tscope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .tenantId("tenant-1")
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.update(isNull(), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(ledger);

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updateBudget".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void updateBudget_noAdminKey_returns401() throws Exception {
        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateBudget_workspaceScope_returns200() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-acme/workspace:prod").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 500000L))
                .overdraftLimit(new Amount(UnitEnum.USD_MICROCENTS, 50000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.update(isNull(), eq("tenant:tenant-acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenReturn(ledger);

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "tenant:tenant-acme/workspace:prod")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":50000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("tenant:tenant-acme/workspace:prod"));

        verify(budgetRepository).update(isNull(), eq("tenant:tenant-acme/workspace:prod"), eq(UnitEnum.USD_MICROCENTS), any());
    }

    // ========== Unit mismatch validation ==========

    @Test
    void createBudget_unitMismatch_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"TOKENS\",\"amount\":1000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNIT_MISMATCH"));
    }

    @Test
    void fundBudget_unitMismatch_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
                        .param("scope", "tenant:tenant-1/workspace:tscope")
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
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.create(eq("tenant-1"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isCreated());

        verify(eventService).emit(eq(EventType.BUDGET_CREATED), eq("tenant-1"), eq("tenant:tenant-1/workspace:team1"), any(), any(), any(), any(), any());
    }

    @Test
    void updateBudget_emitsEvent() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:tscope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.update(isNull(), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(ledger);

        mockMvc.perform(patch("/v1/admin/budgets")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overdraft_limit\":{\"unit\":\"USD_MICROCENTS\",\"amount\":100}}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.BUDGET_UPDATED), any(), eq("tenant:tenant-1/workspace:tscope"), any(), any(), any(), any(), any());
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
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.BUDGET_FUNDED), eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), any(), any(), any(), any(), any());
    }

    // ========== INSUFFICIENT_PERMISSIONS ==========

    @Test
    void createBudget_insufficientPermissions_returns403() throws Exception {
        // API key with only balances:read — lacks admin:write required for POST /v1/admin/budgets
        when(apiKeyRepository.validate("readonly-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_ro")
                        .permissions(List.of("balances:read")).build());

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "readonly-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-1/workspace:team1\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000000}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSIONS"));
    }

    @Test
    void fundBudget_insufficientPermissions_returns403() throws Exception {
        // API key with only budgets:read — lacks budgets:write required for POST /v1/admin/budgets/fund
        when(apiKeyRepository.validate("read-only-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_ro")
                        .permissions(List.of("budgets:read")).build());

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "read-only-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSIONS"));
    }

    @Test
    void listBudgets_insufficientPermissions_returns403() throws Exception {
        // API key with only balances:read — lacks admin:read required for GET /v1/admin/budgets
        when(apiKeyRepository.validate("balance-only-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("tenant-1").keyId("key_bo")
                        .permissions(List.of("balances:read")).build());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "balance-only-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSIONS"));
    }

    // ========== IDEMPOTENCY_MISMATCH (controller level) ==========

    @Test
    void fundBudget_idempotencyMismatch_returns409() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any()))
                .thenThrow(new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.IDEMPOTENCY_MISMATCH,
                        "Idempotency key already used with different payload", 409));

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000},\"idempotency_key\":\"dup-key\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_MISMATCH"));
    }

    // ========== ApiKey hash not exposed in responses ==========

    @Test
    void listBudgets_apiKeyHashNeverInResponse() throws Exception {
        // Verify the ApiKey internal model is never directly serialized — controllers use ApiKeyResponse DTO
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any())).thenReturn(List.of());

        String responseBody = mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(responseBody).doesNotContain("key_hash");
    }

    // ========== POST /v1/admin/budgets/freeze ==========

    @Test
    void freezeBudget_returns200() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 500000L))
                .tenantId("tenant-1")
                .status(BudgetStatus.FROZEN).createdAt(Instant.now()).build();
        when(budgetRepository.freeze("tenant:tenant-1/workspace:team1", UnitEnum.USD_MICROCENTS)).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:team1")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.ledger_id").value("led-1"));
    }

    @Test
    void freezeBudget_withReason_returns200() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:tscope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .tenantId("tenant-1")
                .status(BudgetStatus.FROZEN).createdAt(Instant.now()).build();
        when(budgetRepository.freeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS)).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Suspicious activity\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void freezeBudget_alreadyFrozen_returns409() throws Exception {
        when(budgetRepository.freeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS))
                .thenThrow(new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                        "Budget is already frozen", 409));

        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void freezeBudget_closed_returns409() throws Exception {
        when(budgetRepository.freeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS))
                .thenThrow(GovernanceException.budgetClosed("tenant:tenant-1/workspace:tscope"));

        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUDGET_CLOSED"));
    }

    @Test
    void freezeBudget_notFound_returns404() throws Exception {
        when(budgetRepository.freeze("tenant:tenant-1/workspace:missing", UnitEnum.USD_MICROCENTS))
                .thenThrow(GovernanceException.budgetNotFound("tenant:tenant-1/workspace:missing:USD_MICROCENTS"));

        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:missing")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BUDGET_NOT_FOUND"));
    }

    @Test
    void freezeBudget_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void freezeBudget_logsAuditEntry() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:tscope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .tenantId("tenant-1")
                .status(BudgetStatus.FROZEN).createdAt(Instant.now()).build();
        when(budgetRepository.freeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS)).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "freezeBudget".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void freezeBudget_emitsEvent() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:tscope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .tenantId("tenant-1")
                .status(BudgetStatus.FROZEN).createdAt(Instant.now()).build();
        when(budgetRepository.freeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS)).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets/freeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.BUDGET_FROZEN), eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), any(), any(), any(), any(), any());
    }

    // ========== POST /v1/admin/budgets/unfreeze ==========

    @Test
    void unfreezeBudget_returns200() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 500000L))
                .tenantId("tenant-1")
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.unfreeze("tenant:tenant-1/workspace:team1", UnitEnum.USD_MICROCENTS)).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets/unfreeze")
                        .param("scope", "tenant:tenant-1/workspace:team1")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void unfreezeBudget_alreadyActive_returns409() throws Exception {
        when(budgetRepository.unfreeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS))
                .thenThrow(new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                        "Budget is not frozen", 409));

        mockMvc.perform(post("/v1/admin/budgets/unfreeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isConflict());
    }

    @Test
    void unfreezeBudget_closed_returns409() throws Exception {
        when(budgetRepository.unfreeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS))
                .thenThrow(GovernanceException.budgetClosed("tenant:tenant-1/workspace:tscope"));

        mockMvc.perform(post("/v1/admin/budgets/unfreeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUDGET_CLOSED"));
    }

    @Test
    void unfreezeBudget_emitsEvent() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("tenant:tenant-1/workspace:tscope").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .tenantId("tenant-1")
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.unfreeze("tenant:tenant-1/workspace:tscope", UnitEnum.USD_MICROCENTS)).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets/unfreeze")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.BUDGET_UNFROZEN), eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), any(), any(), any(), any(), any());
    }

    // ========== POST /v1/admin/budgets/fund with AdminKeyAuth ==========

    @Test
    void fundBudget_withAdminKey_returns200() throws Exception {
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("tenant_id", "tenant-1")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("CREDIT"));
    }

    @Test
    void fundBudget_withAdminKey_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void fundBudget_withAdminKey_logsAuditWithTenantId() throws Exception {
        BudgetFundingResponse funding = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 2000L))
                .timestamp(Instant.now()).build();
        when(budgetRepository.fund(eq("tenant-1"), eq("tenant:tenant-1/workspace:tscope"), eq(UnitEnum.USD_MICROCENTS), any())).thenReturn(funding);

        mockMvc.perform(post("/v1/admin/budgets/fund")
                        .param("tenant_id", "tenant-1")
                        .param("scope", "tenant:tenant-1/workspace:tscope")
                        .param("unit", "USD_MICROCENTS")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CREDIT\",\"amount\":{\"unit\":\"USD_MICROCENTS\",\"amount\":1000}}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "fundBudget".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                entry.getStatus() == 200));
    }

    // ========== Admin-on-behalf-of createBudget (v0.1.25.14, spec v0.1.25.13) ==========

    @Test
    void createBudget_withAdminKey_andTenantIdInBody_returns201() throws Exception {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-admin").scope("tenant:tenant-acme/workspace:prod").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 5000000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 5000000L))
                .tenantId("tenant-acme")
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        // Admin path: controller forwards body's tenant_id to repository.create
        when(budgetRepository.create(eq("tenant-acme"), any())).thenReturn(ledger);

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"tenant-acme\",\"scope\":\"tenant:tenant-acme/workspace:prod\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":5000000}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ledger_id").value("led-admin"));

        // Audit entry MUST be tagged actor_type=admin_on_behalf_of so security
        // review can tell admin-driven creates apart from tenant self-service.
        verify(auditRepository).log(argThat(entry ->
                "createBudget".equals(entry.getOperation()) &&
                "tenant-acme".equals(entry.getTenantId()) &&
                entry.getMetadata() != null &&
                "admin_on_behalf_of".equals(entry.getMetadata().get("actor_type"))));
    }

    @Test
    void createBudget_withAdminKey_missingTenantIdInBody_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"tenant:tenant-acme/workspace:prod\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":5000000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tenant_id is required")));
    }

    @Test
    void createBudget_withApiKey_andTenantIdInBody_returns400() throws Exception {
        // Tenant-key callers MUST NOT send tenant_id (would let a tenant
        // claim they're someone else). Server rejects.
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"some-other-tenant\",\"scope\":\"tenant:tenant-acme/workspace:prod\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":5000000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("MUST NOT be set")));
    }

    @Test
    void createBudget_withAdminKey_andJsonNullTenantId_returns400() throws Exception {
        // Explicit coverage for {"tenant_id": null} — Jackson deserializes
        // to Java null which the !=null guard correctly catches as "missing".
        // Verifies the bidirectional contract handles JSON null distinctly
        // from JSON-missing (both should fail; both should fail with the
        // same "tenant_id is required" error).
        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":null,\"scope\":\"tenant:tenant-acme/workspace:prod\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":5000000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tenant_id is required")));
    }

    @Test
    void createBudget_withApiKey_andBlankTenantIdInBody_returns400() throws Exception {
        // Defensive: a blank tenant_id from a tenant caller is still wrong
        // (signals intent to override, even if empty).
        setupApiKeyAuth();

        mockMvc.perform(post("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"\",\"scope\":\"tenant:tenant-acme/workspace:prod\",\"unit\":\"USD_MICROCENTS\",\"allocated\":{\"unit\":\"USD_MICROCENTS\",\"amount\":5000000}}"))
                .andExpect(status().isBadRequest());
    }

    // --- v0.1.25.22 cross-tenant listBudgets + new filter params ---

    @Test
    void listBudgets_adminKey_withoutTenantId_dispatchesCrossTenant() throws Exception {
        BudgetLedger l = BudgetLedger.builder()
                .ledgerId("led-x").tenantId("tenant-a").scope("tenant:tenant-a/workspace:team1")
                .unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 800L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.listAllTenants(any(BudgetListFilters.class), any(), anyInt(), any()))
                .thenReturn(List.of(l));

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgers[0].ledger_id").value("led-x"));

        verify(budgetRepository).listAllTenants(any(BudgetListFilters.class), any(), anyInt(), any());
        verify(budgetRepository, never()).list(anyString(), any(BudgetListFilters.class), any(), anyInt(), any());
    }

    @Test
    void listBudgets_adminKey_withTenantId_dispatchesPerTenant() throws Exception {
        when(budgetRepository.list(eq("tenant-abc"), any(BudgetListFilters.class), any(), anyInt(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .param("tenant_id", "tenant-abc"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-abc"), any(BudgetListFilters.class), any(), anyInt(), any());
        verify(budgetRepository, never()).listAllTenants(any(), any(), anyInt(), any());
    }

    @Test
    void listBudgets_crossTenant_fullPage_nextCursorIsTenantLedgerComposite() throws Exception {
        List<BudgetLedger> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fullPage.add(BudgetLedger.builder()
                    .ledgerId("led-" + i).tenantId("tenant-" + (i / 25))
                    .scope("tenant:tenant-" + (i / 25) + "/workspace:w" + i)
                    .unit(UnitEnum.USD_MICROCENTS)
                    .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                    .remaining(new Amount(UnitEnum.USD_MICROCENTS, 500L))
                    .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build());
        }
        when(budgetRepository.listAllTenants(any(BudgetListFilters.class), any(), anyInt(), any()))
                .thenReturn(fullPage);

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("tenant-1|led-49"));
    }

    @Test
    void listBudgets_perTenant_fullPage_nextCursorIsBareLedgerId() throws Exception {
        setupApiKeyAuth();
        List<BudgetLedger> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fullPage.add(BudgetLedger.builder()
                    .ledgerId("led-" + i).tenantId("tenant-1")
                    .scope("tenant:tenant-1/workspace:w" + i).unit(UnitEnum.USD_MICROCENTS)
                    .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                    .remaining(new Amount(UnitEnum.USD_MICROCENTS, 500L))
                    .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build());
        }
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any()))
                .thenReturn(fullPage);

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("led-49"));
    }

    @Test
    void listBudgets_utilizationMinGreaterThanMax_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .param("utilization_min", "0.8")
                        .param("utilization_max", "0.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "utilization_min must be <= utilization_max")));
    }

    @Test
    void listBudgets_utilizationMinBelowZero_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .param("utilization_min", "-0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "utilization_min must be in [0, 1]")));
    }

    @Test
    void listBudgets_utilizationMaxAboveOne_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .param("utilization_max", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "utilization_max must be in [0, 1]")));
    }

    @Test
    void listBudgets_overLimitFilter_propagatedToFilters() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && Boolean.TRUE.equals(f.overLimit())),
                any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("over_limit", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void listBudgets_hasDebtFilter_propagatedToFilters() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null && Boolean.TRUE.equals(f.hasDebt())),
                any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("has_debt", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void listBudgets_utilizationRange_propagatedToFilters() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"),
                argThat((BudgetListFilters f) -> f != null
                        && f.utilizationMin() != null && f.utilizationMin() == 0.25
                        && f.utilizationMax() != null && f.utilizationMax() == 0.75),
                any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("utilization_min", "0.25")
                        .param("utilization_max", "0.75"))
                .andExpect(status().isOk());
    }

    @Test
    void listBudgets_apiKeyAuth_ignoresUserSuppliedTenantId_evenForCrossTenantLook() throws Exception {
        // Under ApiKeyAuth, the authenticated tenant ALWAYS wins — even if the
        // caller attempts to omit tenant_id (which under AdminKeyAuth would be
        // cross-tenant, but here must stay scoped to tenant-1).
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any());
        verify(budgetRepository, never()).listAllTenants(any(), any(), anyInt(), any());
    }

    // --- v0.1.25.24 server-side sort contract ---

    @Test
    void listBudgets_withValidSortByAndSortDir_passesSortSpecToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any(SortSpec.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("sort_by", "debt")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(budgetRepository).list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), captor.capture());
        SortSpec captured = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.field()).isEqualTo("debt");
        org.assertj.core.api.Assertions.assertThat(captured.direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void listBudgets_missingSortParams_defaultsToUtilizationDesc() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any(SortSpec.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(budgetRepository).list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), captor.capture());
        SortSpec captured = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.field()).isEqualTo("utilization");
        org.assertj.core.api.Assertions.assertThat(captured.direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void listBudgets_unknownSortBy_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("sort_by", "not_a_real_field"))
                .andExpect(status().isBadRequest());

        verify(budgetRepository, never()).list(anyString(), any(BudgetListFilters.class), any(), anyInt(), any());
    }

    @Test
    void listBudgets_unknownSortDir_returns400() throws Exception {
        setupApiKeyAuth();

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("sort_dir", "sideways"))
                .andExpect(status().isBadRequest());

        verify(budgetRepository, never()).list(anyString(), any(BudgetListFilters.class), any(), anyInt(), any());
    }

    @Test
    void listBudgets_allWhitelistedSortFields_accepted() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("tenant-1"), any(BudgetListFilters.class), any(), anyInt(), any(SortSpec.class)))
                .thenReturn(List.of());

        for (String field : List.of("tenant_id", "scope", "unit", "status",
                "commit_overage_policy", "utilization", "debt")) {
            mockMvc.perform(get("/v1/admin/budgets")
                            .header("X-Cycles-API-Key", "valid-api-key")
                            .param("sort_by", field))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void listBudgets_adminKeyCrossTenant_passesSortSpecToListAllTenants() throws Exception {
        when(budgetRepository.listAllTenants(any(BudgetListFilters.class), any(), anyInt(), any(SortSpec.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/budgets")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .param("sort_by", "tenant_id")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(budgetRepository).listAllTenants(any(BudgetListFilters.class), any(), anyInt(), captor.capture());
        SortSpec captured = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.field()).isEqualTo("tenant_id");
        org.assertj.core.api.Assertions.assertThat(captured.direction()).isEqualTo(SortDirection.ASC);
    }
}
