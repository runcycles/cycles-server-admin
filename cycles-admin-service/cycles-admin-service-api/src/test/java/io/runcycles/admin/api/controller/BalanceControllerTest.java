package io.runcycles.admin.api.controller;

import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.UnitEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BalanceController.class)
class BalanceControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BudgetRepository budgetRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("balances:read")).build());
    }

    @Test
    void queryBalances_returns200() throws Exception {
        setupApiKeyAuth();
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").scope("org/team1").unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 800L))
                .status(BudgetStatus.ACTIVE).createdAt(Instant.now()).build();
        when(budgetRepository.list(eq("t1"), any(), any(), eq(BudgetStatus.ACTIVE), isNull(), eq(50)))
                .thenReturn(List.of(ledger));

        mockMvc.perform(get("/v1/balances")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances").isArray())
                .andExpect(jsonPath("$.balances[0].ledger_id").value("led-1"));
    }

    @Test
    void queryBalances_emptyResult_returnsEmptyList() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), eq(BudgetStatus.ACTIVE), isNull(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/balances")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    void queryBalances_noApiKey_returns401() throws Exception {
        mockMvc.perform(get("/v1/balances"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queryBalances_usesScopePrefixFilter() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), eq("org/"), any(), eq(BudgetStatus.ACTIVE), isNull(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/balances")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("scope_prefix", "org/"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), eq("org/"), any(), eq(BudgetStatus.ACTIVE), isNull(), eq(50));
    }

    @Test
    void queryBalances_usesAuthenticatedTenantId_ignoresUserSupplied() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), eq(BudgetStatus.ACTIVE), isNull(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/balances")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("tenant_id", "attacker-tenant"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), any(), eq(BudgetStatus.ACTIVE), isNull(), eq(50));
    }

    @Test
    void queryBalances_emptyResult_nextCursorIsNull() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), any(), eq(BudgetStatus.ACTIVE), isNull(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/balances")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void queryBalances_withUnitFilter_passesUnitToRepository() throws Exception {
        setupApiKeyAuth();
        when(budgetRepository.list(eq("t1"), any(), eq(UnitEnum.TOKENS), eq(BudgetStatus.ACTIVE), isNull(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/balances")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("unit", "TOKENS"))
                .andExpect(status().isOk());

        verify(budgetRepository).list(eq("t1"), any(), eq(UnitEnum.TOKENS), eq(BudgetStatus.ACTIVE), isNull(), eq(50));
    }
}
