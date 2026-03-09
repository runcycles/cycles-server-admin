package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.UnitEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @InjectMocks private BudgetRepository repository;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
    }

    @Test
    void create_newBudget_returnsLedger() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L));

        BudgetLedger result = repository.create("tenant1", request);

        assertThat(result.getScope()).isEqualTo("org/team1");
        assertThat(result.getUnit()).isEqualTo(UnitEnum.USD_MICROCENTS);
        assertThat(result.getAllocated().getAmount()).isEqualTo(1000000L);
        assertThat(result.getRemaining().getAmount()).isEqualTo(1000000L);
        assertThat(result.getStatus()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(result.getLedgerId()).isNotNull();
    }

    @Test
    void create_duplicateBudget_throwsDuplicateResource() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L));

        assertThatThrownBy(() -> repository.create("tenant1", request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    @Test
    void create_withOverdraftLimit_setsOverdraftLimit() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.TOKENS);
        request.setAllocated(new Amount(UnitEnum.TOKENS, 5000L));
        request.setOverdraftLimit(new Amount(UnitEnum.TOKENS, 1000L));

        BudgetLedger result = repository.create("tenant1", request);

        assertThat(result.getOverdraftLimit().getAmount()).isEqualTo(1000L);
    }

    @Test
    void fund_credit_increasesAllocatedAndRemaining() {
        List<String> luaResult = List.of("OK", "1000", "2000", "1000", "2000", "0", "0", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getOperation()).isEqualTo(FundingOperation.CREDIT);
        assertThat(response.getPreviousAllocated().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewAllocated().getAmount()).isEqualTo(2000L);
        assertThat(response.getPreviousRemaining().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(2000L);
    }

    @Test
    void fund_debit_decreasesRemaining() {
        List<String> luaResult = List.of("OK", "2000", "1500", "2000", "1500", "0", "0", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.DEBIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 500L));

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1500L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(1500L);
    }

    @Test
    void fund_budgetNotFound_throwsException() {
        List<String> luaResult = List.of("NOT_FOUND");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.fund("t1", "missing", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void fund_insufficientFunds_throwsException() {
        List<String> luaResult = List.of("INSUFFICIENT_FUNDS");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.DEBIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 99999L));

        assertThatThrownBy(() -> repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED));
    }

    @Test
    void fund_frozenBudget_throwsException() {
        List<String> luaResult = List.of("BUDGET_FROZEN");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.BUDGET_FROZEN));
    }

    @Test
    void fund_closedBudget_throwsException() {
        List<String> luaResult = List.of("BUDGET_CLOSED");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.BUDGET_FROZEN));
    }

    @Test
    void fund_forbidden_throwsException() {
        List<String> luaResult = List.of("FORBIDDEN");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void fund_withIdempotencyKey_cachesMiss_executesNormally() {
        when(jedis.get("idempotency:idem-1")).thenReturn(null);
        List<String> luaResult = List.of("OK", "0", "1000", "0", "1000", "0", "0", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setIdempotencyKey("idem-1");

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
        verify(jedis).setex(eq("idempotency:idem-1"), eq(86400L), anyString());
    }

    @Test
    void fund_withIdempotencyKey_cacheHit_returnsCached() throws Exception {
        BudgetFundingResponse cached = BudgetFundingResponse.builder()
                .operation(FundingOperation.CREDIT)
                .previousAllocated(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .previousRemaining(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .newRemaining(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .build();
        String cachedJson = objectMapper.writeValueAsString(cached);
        when(jedis.get("idempotency:idem-1")).thenReturn(cachedJson);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setIdempotencyKey("idem-1");

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void fund_repayDebt_returnsDebtValues() {
        List<String> luaResult = List.of("OK", "1000", "1000", "0", "500", "1000", "500", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.REPAY_DEBT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getPreviousDebt().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewDebt().getAmount()).isEqualTo(500L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(500L);
    }

    @Test
    void list_returnsBudgetsForTenant() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:org/team1:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("ledger_id", "led-1");
        hash.put("tenant_id", "t1");
        hash.put("scope", "org/team1");
        hash.put("unit", "USD_MICROCENTS");
        hash.put("allocated", "1000");
        hash.put("remaining", "800");
        hash.put("reserved", "100");
        hash.put("spent", "100");
        hash.put("debt", "0");
        hash.put("overdraft_limit", "0");
        hash.put("is_over_limit", "false");
        hash.put("status", "ACTIVE");
        hash.put("created_at", String.valueOf(System.currentTimeMillis()));
        when(jedis.hgetAll("budget:org/team1:USD_MICROCENTS")).thenReturn(hash);

        List<BudgetLedger> result = repository.list("t1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("org/team1");
        assertThat(result.get(0).getAllocated().getAmount()).isEqualTo(1000L);
    }

    @Test
    void list_filtersByScopePrefix() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:org/team1:USD_MICROCENTS", "budget:other/team2:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        Map<String, String> hash1 = createBudgetHash("led-1", "org/team1", "USD_MICROCENTS");
        Map<String, String> hash2 = createBudgetHash("led-2", "other/team2", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:org/team1:USD_MICROCENTS")).thenReturn(hash1);
        when(jedis.hgetAll("budget:other/team2:USD_MICROCENTS")).thenReturn(hash2);

        List<BudgetLedger> result = repository.list("t1", "org/", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("org/team1");
    }

    @Test
    void list_filtersByUnit() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:scope:USD_MICROCENTS", "budget:scope:TOKENS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        Map<String, String> hash1 = createBudgetHash("led-1", "scope", "USD_MICROCENTS");
        Map<String, String> hash2 = createBudgetHash("led-2", "scope", "TOKENS");
        when(jedis.hgetAll("budget:scope:USD_MICROCENTS")).thenReturn(hash1);
        when(jedis.hgetAll("budget:scope:TOKENS")).thenReturn(hash2);

        List<BudgetLedger> result = repository.list("t1", null, UnitEnum.TOKENS, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUnit()).isEqualTo(UnitEnum.TOKENS);
    }

    @Test
    void list_filtersByStatus() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        Map<String, String> hash1 = createBudgetHash("led-1", "a", "USD_MICROCENTS");
        Map<String, String> hash2 = createBudgetHash("led-2", "b", "USD_MICROCENTS");
        hash2.put("status", "FROZEN");
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(hash1);
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hash2);

        List<BudgetLedger> result = repository.list("t1", null, null, BudgetStatus.ACTIVE, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("a");
    }

    private Map<String, String> createBudgetHash(String ledgerId, String scope, String unit) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("ledger_id", ledgerId);
        hash.put("tenant_id", "t1");
        hash.put("scope", scope);
        hash.put("unit", unit);
        hash.put("allocated", "1000");
        hash.put("remaining", "1000");
        hash.put("reserved", "0");
        hash.put("spent", "0");
        hash.put("debt", "0");
        hash.put("overdraft_limit", "0");
        hash.put("is_over_limit", "false");
        hash.put("status", "ACTIVE");
        hash.put("created_at", String.valueOf(System.currentTimeMillis()));
        return hash;
    }
}
