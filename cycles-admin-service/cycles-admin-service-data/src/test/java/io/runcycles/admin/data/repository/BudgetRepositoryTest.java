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
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.BUDGET_NOT_FOUND));
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
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.BUDGET_CLOSED));
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
        // Idempotency is now handled inside the Lua script; cache miss = normal OK result
        List<String> luaResult = List.of("OK", "0", "1000", "0", "1000", "0", "0", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setIdempotencyKey("idem-1");

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void fund_withIdempotencyKey_cacheHit_returnsCached() throws Exception {
        // Idempotency cache hit is now returned by Lua as IDEMPOTENT_HIT with pipe-delimited values
        List<String> luaResult = List.of("IDEMPOTENT_HIT", "0|1000|0|1000|0|0|false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setIdempotencyKey("idem-1");

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
        assertThat(response.getPreviousAllocated().getAmount()).isEqualTo(0L);
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

    @Test
    void fund_idempotencyMismatch_throws409() {
        List<String> luaResult = List.of("IDEMPOTENCY_MISMATCH");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setIdempotencyKey("idem-1");

        assertThatThrownBy(() -> repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_MISMATCH));
    }

    @Test
    void fund_reset_returnsResetValues() {
        List<String> luaResult = List.of("OK", "1000", "5000", "500", "4500", "0", "0", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.RESET);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 5000L));

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getOperation()).isEqualTo(FundingOperation.RESET);
        assertThat(response.getPreviousAllocated().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewAllocated().getAmount()).isEqualTo(5000L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(4500L);
    }

    @Test
    void list_cursorPagination_skipsEntriesBeforeCursor() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS", "budget:c:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        Map<String, String> hashA = createBudgetHash("led-a", "a", "USD_MICROCENTS");
        Map<String, String> hashB = createBudgetHash("led-b", "b", "USD_MICROCENTS");
        Map<String, String> hashC = createBudgetHash("led-c", "c", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(hashA);
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hashB);
        when(jedis.hgetAll("budget:c:USD_MICROCENTS")).thenReturn(hashC);

        List<BudgetLedger> result = repository.list("t1", null, null, null, "led-a", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getLedgerId()).isEqualTo("led-b");
        assertThat(result.get(1).getLedgerId()).isEqualTo("led-c");
    }

    @Test
    void list_emptyHashData_cleansIndexAndSkips() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:stale:USD_MICROCENTS", "budget:valid:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        when(jedis.hgetAll("budget:stale:USD_MICROCENTS")).thenReturn(Collections.emptyMap());
        Map<String, String> validHash = createBudgetHash("led-1", "valid", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:valid:USD_MICROCENTS")).thenReturn(validHash);

        List<BudgetLedger> result = repository.list("t1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("valid");
        verify(jedis).srem("budgets:t1", "budget:stale:USD_MICROCENTS");
    }

    @Test
    void list_respectsLimit() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS", "budget:c:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(createBudgetHash("led-a", "a", "USD_MICROCENTS"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(createBudgetHash("led-b", "b", "USD_MICROCENTS"));

        List<BudgetLedger> result = repository.list("t1", null, null, null, null, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_noKeys_returnsEmptyList() {
        when(jedis.smembers("budgets:t1")).thenReturn(Collections.emptySet());

        List<BudgetLedger> result = repository.list("t1", null, null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void create_withOptionalFields_setsFieldsCorrectly() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.TOKENS);
        request.setAllocated(new Amount(UnitEnum.TOKENS, 5000L));
        request.setRolloverPolicy(RolloverPolicy.CARRY_FORWARD);
        request.setCommitOveragePolicy(io.runcycles.admin.model.shared.CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);

        BudgetLedger result = repository.create("tenant1", request);

        assertThat(result.getRolloverPolicy()).isEqualTo(RolloverPolicy.CARRY_FORWARD);
        assertThat(result.getCommitOveragePolicy()).isEqualTo(io.runcycles.admin.model.shared.CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);
    }

    @Test
    void list_convenienceMethod_callsFullListWithDefaults() {
        when(jedis.smembers("budgets:t1")).thenReturn(Collections.emptySet());

        List<BudgetLedger> result = repository.list("t1");

        assertThat(result).isEmpty();
        verify(jedis).smembers("budgets:t1");
    }

    @Test
    void list_corruptHashMissingScopeOrUnit_skipsGracefully() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:corrupt:USD_MICROCENTS", "budget:valid:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        // Corrupt hash missing 'scope' and 'unit' fields
        Map<String, String> corruptHash = new LinkedHashMap<>();
        corruptHash.put("ledger_id", "led-bad");
        corruptHash.put("allocated", "1000");
        when(jedis.hgetAll("budget:corrupt:USD_MICROCENTS")).thenReturn(corruptHash);

        Map<String, String> validHash = createBudgetHash("led-1", "valid", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:valid:USD_MICROCENTS")).thenReturn(validHash);

        List<BudgetLedger> result = repository.list("t1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("valid");
    }

    @Test
    void create_genericException_wrappedInRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.create("t1", request))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void list_deserializationFailure_skipsGracefully() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:bad:USD_MICROCENTS", "budget:valid:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        // Return a hash that will cause an exception in hashToBudgetLedger (bad unit value)
        Map<String, String> badHash = new LinkedHashMap<>();
        badHash.put("ledger_id", "led-bad");
        badHash.put("scope", "bad");
        badHash.put("unit", "INVALID_UNIT_ENUM");
        when(jedis.hgetAll("budget:bad:USD_MICROCENTS")).thenReturn(badHash);

        Map<String, String> validHash = createBudgetHash("led-1", "valid", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:valid:USD_MICROCENTS")).thenReturn(validHash);

        List<BudgetLedger> result = repository.list("t1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("valid");
    }

    @Test
    void fund_genericException_wrappedInRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void create_withPeriodStartAndEnd_includesPeriodsInArgs() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L));
        request.setPeriodStart(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        request.setPeriodEnd(java.time.Instant.parse("2026-12-31T23:59:59Z"));

        BudgetLedger result = repository.create("tenant1", request);

        assertThat(result.getPeriodStart()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(result.getPeriodEnd()).isEqualTo(java.time.Instant.parse("2026-12-31T23:59:59Z"));
        verify(jedis).eval(anyString(), anyList(), argThat(args ->
                args.contains("period_start") && args.contains("period_end")));
    }

    @Test
    void list_hashWithAllOptionalFields_parsesCorrectly() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:org/full:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        Map<String, String> hash = createBudgetHash("led-full", "org/full", "USD_MICROCENTS");
        hash.put("commit_overage_policy", "ALLOW_WITH_OVERDRAFT");
        hash.put("rollover_policy", "CARRY_FORWARD");
        hash.put("period_start", String.valueOf(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()));
        hash.put("period_end", String.valueOf(java.time.Instant.parse("2026-12-31T23:59:59Z").toEpochMilli()));
        hash.put("updated_at", String.valueOf(System.currentTimeMillis()));
        when(jedis.hgetAll("budget:org/full:USD_MICROCENTS")).thenReturn(hash);

        List<BudgetLedger> result = repository.list("t1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        BudgetLedger ledger = result.get(0);
        assertThat(ledger.getCommitOveragePolicy()).isEqualTo(io.runcycles.admin.model.shared.CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);
        assertThat(ledger.getRolloverPolicy()).isEqualTo(RolloverPolicy.CARRY_FORWARD);
        assertThat(ledger.getPeriodStart()).isNotNull();
        assertThat(ledger.getPeriodEnd()).isNotNull();
        assertThat(ledger.getUpdatedAt()).isNotNull();
    }

    @Test
    void fund_withNullTenantId_passesEmptyString() {
        List<String> luaResult = List.of("OK", "0", "1000", "0", "1000", "0", "0", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        BudgetFundingResponse response = repository.fund(null, "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
        verify(jedis).eval(anyString(), anyList(), argThat(args -> args.contains("")));
    }

    @Test
    void fund_governanceExceptionInCatch_rethrowsDirectly() {
        // Simulate GovernanceException thrown directly (not wrapped)
        GovernanceException expected = GovernanceException.budgetNotFound("test");
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(expected);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.fund("t1", "test", UnitEnum.USD_MICROCENTS, request))
                .isSameAs(expected);
    }

    @Test
    void create_governanceExceptionInCatch_rethrowsDirectly() {
        GovernanceException expected = GovernanceException.duplicateResource("Budget", "test");
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(expected);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("test");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.create("t1", request))
                .isSameAs(expected);
    }

    @Test
    void fund_withBlankIdempotencyKey_treatedAsNoIdempotency() {
        List<String> luaResult = List.of("OK", "0", "1000", "0", "1000", "0", "0", "false");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setIdempotencyKey("   ");

        BudgetFundingResponse response = repository.fund("t1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
    }

    @Test
    void list_cursorNotFound_includesAllEntries() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:t1")).thenReturn(keys);

        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(createBudgetHash("led-a", "a", "USD_MICROCENTS"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(createBudgetHash("led-b", "b", "USD_MICROCENTS"));

        // Cursor "nonexistent" won't match any ledger_id, so nothing is returned after the cursor
        List<BudgetLedger> result = repository.list("t1", null, null, null, "nonexistent", 50);

        assertThat(result).isEmpty();
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

    // ========== update() tests ==========

    @Test
    void update_success_returnsUpdatedLedger() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK"));
        when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(createBudgetHash("led-1", "tenant:acme", "USD_MICROCENTS"));

        BudgetUpdateRequest request = new BudgetUpdateRequest();
        request.setOverdraftLimit(new Amount(UnitEnum.USD_MICROCENTS, 50000L));

        BudgetLedger result = repository.update("acme", "tenant:acme", UnitEnum.USD_MICROCENTS, request);

        assertThat(result).isNotNull();
        assertThat(result.getScope()).isEqualTo("tenant:acme");
    }

    @Test
    void update_notFound_throws404() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("NOT_FOUND"));

        BudgetUpdateRequest request = new BudgetUpdateRequest();
        request.setOverdraftLimit(new Amount(UnitEnum.USD_MICROCENTS, 50000L));

        assertThatThrownBy(() -> repository.update("acme", "tenant:acme", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void update_forbidden_throws403() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("FORBIDDEN"));

        BudgetUpdateRequest request = new BudgetUpdateRequest();

        assertThatThrownBy(() -> repository.update("wrong-tenant", "tenant:acme", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    @Test
    void update_closed_throws409() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("BUDGET_CLOSED"));

        BudgetUpdateRequest request = new BudgetUpdateRequest();

        assertThatThrownBy(() -> repository.update("acme", "tenant:acme", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 409);
    }

    @Test
    void create_tenantNotFound_throwsTenantNotFound() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-1L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.create("missing-tenant", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TENANT_NOT_FOUND)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void create_tenantNotActive_throwsInvalidRequest() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-2L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("org/team1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.create("suspended-tenant", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void update_withCommitOveragePolicy_passesCorrectArgs() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK"));
        when(jedis.hgetAll("budget:tenant:acme:USD_MICROCENTS")).thenReturn(createBudgetHash("led-1", "tenant:acme", "USD_MICROCENTS"));

        BudgetUpdateRequest request = new BudgetUpdateRequest();
        request.setCommitOveragePolicy(io.runcycles.admin.model.shared.CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);

        repository.update("acme", "tenant:acme", UnitEnum.USD_MICROCENTS, request);

        verify(jedis).eval(anyString(), anyList(), argThat((List<String> args) ->
                args.get(2).equals("ALLOW_WITH_OVERDRAFT")));
    }
}
