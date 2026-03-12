package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.budget.*;
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

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("balances:read")).build());
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

        mockMvc.perform(post("/v1/admin/budgets/org_team1/USD_MICROCENTS/fund")
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

        mockMvc.perform(post("/v1/admin/budgets/scope/USD_MICROCENTS/fund")
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

        mockMvc.perform(post("/v1/admin/budgets/scope/USD_MICROCENTS/fund")
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

        mockMvc.perform(post("/v1/admin/budgets/scope/USD_MICROCENTS/fund")
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
}
