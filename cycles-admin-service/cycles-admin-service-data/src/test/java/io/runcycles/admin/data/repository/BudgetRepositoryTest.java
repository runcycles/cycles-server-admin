package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.budget.FundingOperation;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
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
    void countForBulk_returnsExactCountAndSkipsMissingOrMalformedRows() {
        when(jedis.smembers("budgets:tenant-1")).thenReturn(
            new LinkedHashSet<>(List.of("budget:good", "budget:missing", "budget:bad")));
        when(jedis.hgetAll("budget:good"))
            .thenReturn(createBudgetHash("led-good", "tenant:tenant-1", "USD_MICROCENTS"));
        when(jedis.hgetAll("budget:missing")).thenReturn(Map.of());
        when(jedis.hgetAll("budget:bad")).thenReturn(Map.of(
            "ledger_id", "led-bad", "scope", "bad", "unit", "NOT_A_UNIT"));

        assertThat(repository.countForBulk("tenant-1", null)).isEqualTo(1);
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

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

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

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

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

        assertThatThrownBy(() -> repository.fund("tenant-1", "missing", UnitEnum.USD_MICROCENTS, request))
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

        assertThatThrownBy(() -> repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request))
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

        assertThatThrownBy(() -> repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request))
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

        assertThatThrownBy(() -> repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request))
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

        assertThatThrownBy(() -> repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request))
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

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

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

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

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

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getPreviousDebt().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewDebt().getAmount()).isEqualTo(500L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(500L);
    }

    @Test
    void list_returnsBudgetsForTenant() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:org/team1:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("ledger_id", "led-1");
        hash.put("tenant_id", "tenant-1");
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

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("org/team1");
        assertThat(result.get(0).getAllocated().getAmount()).isEqualTo(1000L);
    }

    @Test
    void list_filtersByScopePrefix() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:org/team1:USD_MICROCENTS", "budget:other/team2:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        Map<String, String> hash1 = createBudgetHash("led-1", "org/team1", "USD_MICROCENTS");
        Map<String, String> hash2 = createBudgetHash("led-2", "other/team2", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:org/team1:USD_MICROCENTS")).thenReturn(hash1);
        when(jedis.hgetAll("budget:other/team2:USD_MICROCENTS")).thenReturn(hash2);

        List<BudgetLedger> result = repository.list("tenant-1", "org/", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("org/team1");
    }

    @Test
    void list_filtersByUnit() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:scope:USD_MICROCENTS", "budget:scope:TOKENS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        Map<String, String> hash1 = createBudgetHash("led-1", "scope", "USD_MICROCENTS");
        Map<String, String> hash2 = createBudgetHash("led-2", "scope", "TOKENS");
        when(jedis.hgetAll("budget:scope:USD_MICROCENTS")).thenReturn(hash1);
        when(jedis.hgetAll("budget:scope:TOKENS")).thenReturn(hash2);

        List<BudgetLedger> result = repository.list("tenant-1", null, UnitEnum.TOKENS, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUnit()).isEqualTo(UnitEnum.TOKENS);
    }

    @Test
    void list_filtersByStatus() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        Map<String, String> hash1 = createBudgetHash("led-1", "a", "USD_MICROCENTS");
        Map<String, String> hash2 = createBudgetHash("led-2", "b", "USD_MICROCENTS");
        hash2.put("status", "FROZEN");
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(hash1);
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hash2);

        List<BudgetLedger> result = repository.list("tenant-1", null, null, BudgetStatus.ACTIVE, null, 50);

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

        assertThatThrownBy(() -> repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request))
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

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getOperation()).isEqualTo(FundingOperation.RESET);
        assertThat(response.getPreviousAllocated().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewAllocated().getAmount()).isEqualTo(5000L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(4500L);
    }

    // ---- RESET_SPENT (billing-period boundary) — v0.1.25.17+ ---------------

    /**
     * Default case: no explicit `spent` → Lua defaults to 0.
     * Simulates the canonical "new billing period" flow on an exhausted budget.
     * Lua return array has 10 elements in v0.1.25.17+: adds prev_spent/new_spent.
     */
    @Test
    void fund_resetSpent_defaultClearsSpent() {
        // Pre-state: allocated=1000, remaining=0, spent=1000 (exhausted). Post:
        // allocated=1000, remaining=1000, spent=0. Mock echoes back what Lua
        // would compute.
        List<String> luaResult = List.of("OK",
            "1000", "1000",       // prev_allocated, new_allocated
            "0", "1000",          // prev_remaining, new_remaining
            "0", "0",             // prev_debt, new_debt
            "false",              // is_over
            "1000", "0");         // prev_spent, new_spent
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.RESET_SPENT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        // No spent field — defaults to 0.

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getOperation()).isEqualTo(FundingOperation.RESET_SPENT);
        assertThat(response.getPreviousAllocated().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
        assertThat(response.getPreviousRemaining().getAmount()).isEqualTo(0L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(1000L);
        assertThat(response.getPreviousSpent()).isNotNull();
        assertThat(response.getPreviousSpent().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewSpent()).isNotNull();
        assertThat(response.getNewSpent().getAmount()).isEqualTo(0L);
    }

    /**
     * Explicit spent override: migration / proration / compensation use cases.
     * Tests that the request's spent field is plumbed through to ARGV[7] and
     * that the response reflects the explicit value.
     */
    @Test
    void fund_resetSpent_withExplicitSpentOverride() {
        // Pre: allocated=1000, spent=1000, remaining=0. Request: amount=1000,
        // spent=400 (migration). Post: allocated=1000, spent=400, remaining=600.
        List<String> luaResult = List.of("OK",
            "1000", "1000",
            "0", "600",
            "0", "0",
            "false",
            "1000", "400");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.RESET_SPENT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setSpent(new Amount(UnitEnum.USD_MICROCENTS, 400L));

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getOperation()).isEqualTo(FundingOperation.RESET_SPENT);
        assertThat(response.getNewSpent().getAmount()).isEqualTo(400L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(600L);

        // Verify the Lua call received the spent override at ARGV[7]. Using
        // ArgumentCaptor for the ARGV list.
        org.mockito.ArgumentCaptor<List<String>> argsCap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), anyList(), argsCap.capture());
        List<String> argv = argsCap.getValue();
        assertThat(argv).hasSize(7);
        assertThat(argv.get(6)).isEqualTo("400");
    }

    /**
     * Negative spent: Lua validates and returns INVALID_REQUEST. Java maps
     * to GovernanceException with 400. This guards the runtime-side check in
     * case Bean Validation gets bypassed (e.g., direct controller invocation
     * in a test harness).
     */
    @Test
    void fund_resetSpent_rejectsNegativeSpent() {
        List<String> luaResult = List.of("INVALID_REQUEST", "spent must be >= 0");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.RESET_SPENT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setSpent(new Amount(UnitEnum.USD_MICROCENTS, -1L));

        assertThatThrownBy(() -> repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request))
            .isInstanceOf(io.runcycles.admin.data.exception.GovernanceException.class)
            .hasMessageContaining("spent must be >= 0");
    }

    /**
     * Overdraft-style negative remaining: if the supplied spent plus reserved
     * and debt exceed allocated, remaining goes negative. No error — matches
     * existing overdraft ledger semantics. Caller sees the negative value.
     */
    @Test
    void fund_resetSpent_allowsNegativeRemainingWhenOverflow() {
        // allocated=100, reserved=50, debt=20, spent=200 → remaining=-170
        List<String> luaResult = List.of("OK",
            "1000", "100",
            "0", "-170",
            "20", "20",
            "false",
            "0", "200");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.RESET_SPENT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 100L));
        request.setSpent(new Amount(UnitEnum.USD_MICROCENTS, 200L));

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewRemaining().getAmount()).isEqualTo(-170L);
        assertThat(response.getNewSpent().getAmount()).isEqualTo(200L);
    }

    /**
     * Verify ARGV[7] is EMPTY when no spent override is supplied (default-0
     * path). This is important because the Lua script distinguishes empty
     * from present — an accidental "0" string from a null-unsafe call site
     * would silently work on a RESET_SPENT but might leak to other ops.
     */
    @Test
    void fund_resetSpent_emptyArgvForDefaultSpent() {
        List<String> luaResult = List.of("OK",
            "1000", "1000",
            "0", "1000",
            "0", "0",
            "false",
            "1000", "0");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.RESET_SPENT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        // spent field intentionally null

        repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        org.mockito.ArgumentCaptor<List<String>> argsCap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), anyList(), argsCap.capture());
        assertThat(argsCap.getValue().get(6)).isEqualTo("");
    }

    /**
     * Idempotent cache replay against the v2 cache format. The cached string
     * now carries 9 pipe-delimited fields: ...|is_over|prev_spent|new_spent.
     * Ensure the parser reads prev_spent and new_spent into the response.
     */
    @Test
    void fund_idempotentHit_parsesV2CacheFormatWithSpent() {
        // v2 cache format: prev_allocated|allocated|prev_remaining|remaining|
        //                  prev_debt|debt|is_over|prev_spent|new_spent
        String cached = "1000|1000|0|1000|0|0|false|1000|0";
        List<String> luaResult = List.of("IDEMPOTENT_HIT", cached);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(luaResult);

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.RESET_SPENT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));
        request.setIdempotencyKey("same-key-retry");

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getPreviousSpent().getAmount()).isEqualTo(1000L);
        assertThat(response.getNewSpent().getAmount()).isEqualTo(0L);
    }

    @Test
    void list_cursorPagination_skipsEntriesBeforeCursor() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS", "budget:c:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        Map<String, String> hashA = createBudgetHash("led-a", "a", "USD_MICROCENTS");
        Map<String, String> hashB = createBudgetHash("led-b", "b", "USD_MICROCENTS");
        Map<String, String> hashC = createBudgetHash("led-c", "c", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(hashA);
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hashB);
        when(jedis.hgetAll("budget:c:USD_MICROCENTS")).thenReturn(hashC);

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, "led-a", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getLedgerId()).isEqualTo("led-b");
        assertThat(result.get(1).getLedgerId()).isEqualTo("led-c");
    }

    @Test
    void list_emptyHashData_cleansIndexAndSkips() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:stale:USD_MICROCENTS", "budget:valid:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        when(jedis.hgetAll("budget:stale:USD_MICROCENTS")).thenReturn(Collections.emptyMap());
        Map<String, String> validHash = createBudgetHash("led-1", "valid", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:valid:USD_MICROCENTS")).thenReturn(validHash);

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("valid");
        verify(jedis).srem("budgets:tenant-1", "budget:stale:USD_MICROCENTS");
    }

    @Test
    void list_respectsLimit() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS", "budget:c:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(createBudgetHash("led-a", "a", "USD_MICROCENTS"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(createBudgetHash("led-b", "b", "USD_MICROCENTS"));

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, null, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_noKeys_returnsEmptyList() {
        when(jedis.smembers("budgets:tenant-1")).thenReturn(Collections.emptySet());

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, null, 50);

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
        when(jedis.smembers("budgets:tenant-1")).thenReturn(Collections.emptySet());

        List<BudgetLedger> result = repository.list("tenant-1");

        assertThat(result).isEmpty();
        verify(jedis).smembers("budgets:tenant-1");
    }

    @Test
    void list_corruptHashMissingScopeOrUnit_skipsGracefully() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:corrupt:USD_MICROCENTS", "budget:valid:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        // Corrupt hash missing 'scope' and 'unit' fields
        Map<String, String> corruptHash = new LinkedHashMap<>();
        corruptHash.put("ledger_id", "led-bad");
        corruptHash.put("allocated", "1000");
        when(jedis.hgetAll("budget:corrupt:USD_MICROCENTS")).thenReturn(corruptHash);

        Map<String, String> validHash = createBudgetHash("led-1", "valid", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:valid:USD_MICROCENTS")).thenReturn(validHash);

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, null, 50);

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

        assertThatThrownBy(() -> repository.create("tenant-1", request))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void list_deserializationFailure_skipsGracefully() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:bad:USD_MICROCENTS", "budget:valid:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        // Return a hash that will cause an exception in hashToBudgetLedger (bad unit value)
        Map<String, String> badHash = new LinkedHashMap<>();
        badHash.put("ledger_id", "led-bad");
        badHash.put("scope", "bad");
        badHash.put("unit", "INVALID_UNIT_ENUM");
        when(jedis.hgetAll("budget:bad:USD_MICROCENTS")).thenReturn(badHash);

        Map<String, String> validHash = createBudgetHash("led-1", "valid", "USD_MICROCENTS");
        when(jedis.hgetAll("budget:valid:USD_MICROCENTS")).thenReturn(validHash);

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("valid");
    }

    @Test
    void fund_genericException_wrappedInRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000L));

        assertThatThrownBy(() -> repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request))
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
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        Map<String, String> hash = createBudgetHash("led-full", "org/full", "USD_MICROCENTS");
        hash.put("commit_overage_policy", "ALLOW_WITH_OVERDRAFT");
        hash.put("rollover_policy", "CARRY_FORWARD");
        hash.put("period_start", String.valueOf(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()));
        hash.put("period_end", String.valueOf(java.time.Instant.parse("2026-12-31T23:59:59Z").toEpochMilli()));
        hash.put("updated_at", String.valueOf(System.currentTimeMillis()));
        when(jedis.hgetAll("budget:org/full:USD_MICROCENTS")).thenReturn(hash);

        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, null, 50);

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

        assertThatThrownBy(() -> repository.fund("tenant-1", "test", UnitEnum.USD_MICROCENTS, request))
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

        assertThatThrownBy(() -> repository.create("tenant-1", request))
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

        BudgetFundingResponse response = repository.fund("tenant-1", "scope", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1000L);
    }

    @Test
    void list_cursorNotFound_includesAllEntries() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(createBudgetHash("led-a", "a", "USD_MICROCENTS"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(createBudgetHash("led-b", "b", "USD_MICROCENTS"));

        // Cursor "nonexistent" won't match any ledger_id, so nothing is returned after the cursor
        List<BudgetLedger> result = repository.list("tenant-1", null, null, null, "nonexistent", 50);

        assertThat(result).isEmpty();
    }

    private Map<String, String> createBudgetHash(String ledgerId, String scope, String unit) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("ledger_id", ledgerId);
        hash.put("tenant_id", "tenant-1");
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

    @Test
    void create_lowercasesMixedCaseScope() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("tenant:Rider/app:riderApp");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L));

        BudgetLedger result = repository.create("tenant1", request);

        assertThat(result.getScope()).isEqualTo("tenant:rider/app:riderapp");
    }

    @Test
    void fund_lowercasesMixedCaseScope() {
        when(jedis.eval(anyString(), anyList(), anyList()))
                .thenReturn(Arrays.asList("OK", "1000000", "2000000", "0", "0", "0", "0", "false"));

        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 1000000L));

        repository.fund("tenant1", "tenant:Rider/app:RiderApp", UnitEnum.USD_MICROCENTS, request);

        // Verify the Redis key uses lowercased scope
        verify(jedis).eval(anyString(),
                argThat((List<String> keys) -> keys.get(0).equals("budget:tenant:rider/app:riderapp:USD_MICROCENTS")),
                anyList());
    }

    // ========== freeze / unfreeze ==========

    @Test
    void freeze_activeBudget_returnsLedgerWithFrozenStatus() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK"));
        Map<String, String> hash = createBudgetHash("led-1", "org/team1", "USD_MICROCENTS");
        hash.put("status", "FROZEN");
        when(jedis.hgetAll("budget:org/team1:USD_MICROCENTS")).thenReturn(hash);

        BudgetLedger result = repository.freeze("org/team1", UnitEnum.USD_MICROCENTS);

        assertThat(result.getStatus()).isEqualTo(BudgetStatus.FROZEN);
        assertThat(result.getLedgerId()).isEqualTo("led-1");
    }

    @Test
    void freeze_alreadyFrozen_throws409() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("ALREADY_FROZEN"));

        assertThatThrownBy(() -> repository.freeze("scope", UnitEnum.USD_MICROCENTS))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void freeze_closedBudget_throws409() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("BUDGET_CLOSED"));

        assertThatThrownBy(() -> repository.freeze("scope", UnitEnum.USD_MICROCENTS))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.BUDGET_CLOSED);
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void freeze_notFound_throws404() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("NOT_FOUND"));

        assertThatThrownBy(() -> repository.freeze("missing", UnitEnum.USD_MICROCENTS))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.BUDGET_NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
    }

    @Test
    void unfreeze_frozenBudget_returnsLedgerWithActiveStatus() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK"));
        Map<String, String> hash = createBudgetHash("led-1", "org/team1", "USD_MICROCENTS");
        hash.put("status", "ACTIVE");
        when(jedis.hgetAll("budget:org/team1:USD_MICROCENTS")).thenReturn(hash);

        BudgetLedger result = repository.unfreeze("org/team1", UnitEnum.USD_MICROCENTS);

        assertThat(result.getStatus()).isEqualTo(BudgetStatus.ACTIVE);
    }

    @Test
    void unfreeze_alreadyActive_throws409() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("ALREADY_ACTIVE"));

        assertThatThrownBy(() -> repository.unfreeze("scope", UnitEnum.USD_MICROCENTS))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void unfreeze_closedBudget_throws409() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("BUDGET_CLOSED"));

        assertThatThrownBy(() -> repository.unfreeze("scope", UnitEnum.USD_MICROCENTS))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.BUDGET_CLOSED);
                });
    }

    // --- v0.1.25.22 BudgetListFilters + cross-tenant listAllTenants ---

    private Map<String, String> hashWith(String ledgerId, String scope, String unit,
                                          long allocated, long spent, long debt, boolean overLimit,
                                          String tenantId) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("ledger_id", ledgerId);
        h.put("tenant_id", tenantId);
        h.put("scope", scope);
        h.put("unit", unit);
        h.put("allocated", String.valueOf(allocated));
        h.put("remaining", String.valueOf(allocated - spent - debt));
        h.put("reserved", "0");
        h.put("spent", String.valueOf(spent));
        h.put("debt", String.valueOf(debt));
        h.put("overdraft_limit", "0");
        h.put("is_over_limit", String.valueOf(overLimit));
        h.put("status", "ACTIVE");
        h.put("created_at", String.valueOf(System.currentTimeMillis()));
        return h;
    }

    @Test
    void budgetListFilters_empty_matchesEverything() {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").tenantId("tenant-1").scope("tenant:tenant-1")
                .unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 1000L))
                .spent(new Amount(UnitEnum.USD_MICROCENTS, 500L))
                .debt(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .isOverLimit(false)
                .status(BudgetStatus.ACTIVE)
                .build();

        assertThat(BudgetListFilters.empty().matches(ledger)).isTrue();
    }

    @Test
    void budgetListFilters_utilization_allocatedZero_treatedAsZero() {
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1").tenantId("tenant-1").scope("tenant:tenant-1")
                .unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .spent(new Amount(UnitEnum.USD_MICROCENTS, 500L))
                .debt(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .isOverLimit(false)
                .status(BudgetStatus.ACTIVE)
                .build();

        // utilization treated as 0 regardless of spent
        BudgetListFilters min0 = new BudgetListFilters(null, null, null, null, null, 0.0, null);
        BudgetListFilters min01 = new BudgetListFilters(null, null, null, null, null, 0.1, null);
        assertThat(min0.matches(ledger)).isTrue();
        assertThat(min01.matches(ledger)).isFalse();
    }

    @Test
    void budgetListFilters_overLimit_filtersCorrectly() {
        BudgetLedger over = BudgetLedger.builder()
                .ledgerId("led-over").tenantId("t").scope("s").unit(UnitEnum.TOKENS)
                .allocated(new Amount(UnitEnum.TOKENS, 100L))
                .spent(new Amount(UnitEnum.TOKENS, 0L))
                .debt(new Amount(UnitEnum.TOKENS, 50L))
                .isOverLimit(true).status(BudgetStatus.ACTIVE).build();
        BudgetLedger notOver = BudgetLedger.builder()
                .ledgerId("led-ok").tenantId("t").scope("s").unit(UnitEnum.TOKENS)
                .allocated(new Amount(UnitEnum.TOKENS, 100L))
                .spent(new Amount(UnitEnum.TOKENS, 0L))
                .debt(new Amount(UnitEnum.TOKENS, 0L))
                .isOverLimit(false).status(BudgetStatus.ACTIVE).build();

        BudgetListFilters onlyOver = new BudgetListFilters(null, null, null, true, null, null, null);
        assertThat(onlyOver.matches(over)).isTrue();
        assertThat(onlyOver.matches(notOver)).isFalse();
    }

    @Test
    void budgetListFilters_hasDebt_filtersCorrectly() {
        BudgetLedger withDebt = BudgetLedger.builder()
                .ledgerId("led-d").tenantId("t").scope("s").unit(UnitEnum.TOKENS)
                .allocated(new Amount(UnitEnum.TOKENS, 100L))
                .spent(new Amount(UnitEnum.TOKENS, 0L))
                .debt(new Amount(UnitEnum.TOKENS, 10L))
                .isOverLimit(false).status(BudgetStatus.ACTIVE).build();
        BudgetLedger noDebt = BudgetLedger.builder()
                .ledgerId("led-0").tenantId("t").scope("s").unit(UnitEnum.TOKENS)
                .allocated(new Amount(UnitEnum.TOKENS, 100L))
                .spent(new Amount(UnitEnum.TOKENS, 0L))
                .debt(new Amount(UnitEnum.TOKENS, 0L))
                .isOverLimit(false).status(BudgetStatus.ACTIVE).build();

        BudgetListFilters wantDebt = new BudgetListFilters(null, null, null, null, true, null, null);
        assertThat(wantDebt.matches(withDebt)).isTrue();
        assertThat(wantDebt.matches(noDebt)).isFalse();
    }

    @Test
    void budgetListFilters_scopePrefix_andUnit_andStatus_combined() {
        BudgetLedger l = BudgetLedger.builder()
                .ledgerId("led-1").tenantId("t").scope("tenant:acme/workspace:eng")
                .unit(UnitEnum.USD_MICROCENTS)
                .allocated(new Amount(UnitEnum.USD_MICROCENTS, 100L))
                .spent(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .debt(new Amount(UnitEnum.USD_MICROCENTS, 0L))
                .isOverLimit(false).status(BudgetStatus.ACTIVE).build();

        assertThat(new BudgetListFilters("tenant:acme", UnitEnum.USD_MICROCENTS,
                BudgetStatus.ACTIVE, null, null, null, null).matches(l)).isTrue();
        assertThat(new BudgetListFilters("tenant:other", null, null, null, null, null, null)
                .matches(l)).isFalse();
        assertThat(new BudgetListFilters(null, UnitEnum.TOKENS, null, null, null, null, null)
                .matches(l)).isFalse();
        assertThat(new BudgetListFilters(null, null, BudgetStatus.FROZEN, null, null, null, null)
                .matches(l)).isFalse();
    }

    @Test
    void listAllTenants_walksAllTenantsInSortedOrder() {
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-b", "tenant-a")));
        when(jedis.smembers("budgets:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:a1:USD_MICROCENTS")));
        when(jedis.smembers("budgets:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:b1:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a1", "a1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        when(jedis.hgetAll("budget:b1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b1", "b1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-b"));

        List<BudgetLedger> result = repository.listAllTenants(BudgetListFilters.empty(), null, 50);

        assertThat(result).extracting(BudgetLedger::getLedgerId).containsExactly("led-a1", "led-b1");
    }

    @Test
    void listAllTenants_withCursor_resumesInsideMatchingTenant() {
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        when(jedis.smembers("budgets:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:a1:USD_MICROCENTS", "budget:a2:USD_MICROCENTS")));
        when(jedis.smembers("budgets:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:b1:USD_MICROCENTS")));
        // a1 is the cursor and must be skipped; a2 and b1 should appear.
        lenient().when(jedis.hgetAll("budget:a1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a1", "a1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        when(jedis.hgetAll("budget:a2:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a2", "a2", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        when(jedis.hgetAll("budget:b1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b1", "b1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-b"));

        List<BudgetLedger> result = repository.listAllTenants(
                BudgetListFilters.empty(), "tenant-a|led-a1", 50);

        assertThat(result).extracting(BudgetLedger::getLedgerId).containsExactly("led-a2", "led-b1");
    }

    @Test
    void listAllTenants_filterApplies_beforePagination() {
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a")));
        when(jedis.smembers("budgets:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a1:USD_MICROCENTS", "budget:a2:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a1", "a1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        when(jedis.hgetAll("budget:a2:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a2", "a2", "USD_MICROCENTS", 100, 0, 20, true, "tenant-a"));

        BudgetListFilters onlyOver = new BudgetListFilters(null, null, null, true, null, null, null);
        List<BudgetLedger> result = repository.listAllTenants(onlyOver, null, 50);

        assertThat(result).extracting(BudgetLedger::getLedgerId).containsExactly("led-a2");
    }

    @Test
    void listAllTenants_respectsLimit() {
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        when(jedis.smembers("budgets:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a1:USD_MICROCENTS", "budget:a2:USD_MICROCENTS")));
        lenient().when(jedis.smembers("budgets:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:b1:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a1", "a1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        when(jedis.hgetAll("budget:a2:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a2", "a2", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));

        List<BudgetLedger> result = repository.listAllTenants(BudgetListFilters.empty(), null, 2);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-a1", "led-a2");
    }

    @Test
    void listAllTenants_emptyTenantsSet_returnsEmpty() {
        when(jedis.smembers("tenants")).thenReturn(Collections.emptySet());

        List<BudgetLedger> result = repository.listAllTenants(BudgetListFilters.empty(), null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void listAllTenants_cursorTenantDeleted_skipsForwardToNextTenant() {
        // Cursor points at tenant-b, but tenant-b has been deleted between pages.
        // Must not stall at empty: should resume at tenant-c (sorts strictly after)
        // and serve tenant-c from the start.
        when(jedis.smembers("tenants"))
                .thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-c")));
        when(jedis.smembers("budgets:tenant-c"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:c1:USD_MICROCENTS", "budget:c2:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:c1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-c1", "c1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-c"));
        when(jedis.hgetAll("budget:c2:USD_MICROCENTS"))
                .thenReturn(hashWith("led-c2", "c2", "USD_MICROCENTS", 100, 0, 0, false, "tenant-c"));

        List<BudgetLedger> result = repository.listAllTenants(
                BudgetListFilters.empty(), "tenant-b|led-b1", 50);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-c1", "led-c2");
    }

    @Test
    void list_filterBased_overLimit_filters() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a", "a", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b", "b", "USD_MICROCENTS", 100, 0, 10, true, "tenant-1"));

        List<BudgetLedger> result = repository.list(
                "tenant-1",
                new BudgetListFilters(null, null, null, true, null, null, null),
                null, 50);

        assertThat(result).extracting(BudgetLedger::getLedgerId).containsExactly("led-b");
    }

    @Test
    void list_filterBased_utilizationRange_filters() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS",
                        "budget:c:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a", "a", "USD_MICROCENTS", 100, 10, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b", "b", "USD_MICROCENTS", 100, 50, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:c:USD_MICROCENTS"))
                .thenReturn(hashWith("led-c", "c", "USD_MICROCENTS", 100, 90, 0, false, "tenant-1"));

        List<BudgetLedger> result = repository.list(
                "tenant-1",
                new BudgetListFilters(null, null, null, null, null, 0.25, 0.75),
                null, 50);

        assertThat(result).extracting(BudgetLedger::getLedgerId).containsExactly("led-b");
    }

    // --- v0.1.25.24 server-side sort (SortSpec) ---

    private Map<String, String> hashFull(String ledgerId, String scope, String unit,
                                          long allocated, long spent, long debt, boolean overLimit,
                                          String tenantId, String status, String policy) {
        Map<String, String> h = hashWith(ledgerId, scope, unit, allocated, spent, debt, overLimit, tenantId);
        h.put("status", status);
        if (policy != null) h.put("commit_overage_policy", policy);
        return h;
    }

    @Test
    void list_sortByScopeAscending_returnsScopeSorted() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:z:USD_MICROCENTS", "budget:a:USD_MICROCENTS",
                        "budget:m:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:z:USD_MICROCENTS"))
                .thenReturn(hashWith("led-z", "z", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a", "a", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:m:USD_MICROCENTS"))
                .thenReturn(hashWith("led-m", "m", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));

        SortSpec sort = SortSpec.of("scope", SortDirection.ASC);
        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, sort);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-a", "led-m", "led-z");
    }

    @Test
    void list_sortByUtilizationDescending_usesComputedUtilization() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:low:USD_MICROCENTS", "budget:high:USD_MICROCENTS",
                        "budget:mid:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:low:USD_MICROCENTS"))
                .thenReturn(hashWith("led-low", "low", "USD_MICROCENTS", 100, 10, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:high:USD_MICROCENTS"))
                .thenReturn(hashWith("led-high", "high", "USD_MICROCENTS", 100, 90, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:mid:USD_MICROCENTS"))
                .thenReturn(hashWith("led-mid", "mid", "USD_MICROCENTS", 100, 50, 0, false, "tenant-1"));

        SortSpec sort = SortSpec.of("utilization", SortDirection.DESC);
        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, sort);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-high", "led-mid", "led-low");
    }

    @Test
    void list_sortByDebtAscending_nullDebtTreatedAsZero() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS",
                        "budget:c:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a", "a", "USD_MICROCENTS", 100, 0, 50, true, "tenant-1"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b", "b", "USD_MICROCENTS", 100, 0, 10, true, "tenant-1"));
        when(jedis.hgetAll("budget:c:USD_MICROCENTS"))
                .thenReturn(hashWith("led-c", "c", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));

        SortSpec sort = SortSpec.of("debt", SortDirection.ASC);
        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, sort);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-c", "led-b", "led-a");
    }

    @Test
    void list_sortByStatus_nullCommitPolicyIsNullsLast() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashFull("led-a", "a", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1", "FROZEN", null));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS"))
                .thenReturn(hashFull("led-b", "b", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1", "ACTIVE", null));

        SortSpec sort = SortSpec.of("status", SortDirection.ASC);
        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, sort);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-b", "led-a");
    }

    @Test
    void list_sortByCommitOveragePolicy_nullsLast() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS",
                        "budget:c:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashFull("led-a", "a", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1", "ACTIVE", "ALLOW_IF_AVAILABLE"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS"))
                .thenReturn(hashFull("led-b", "b", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1", "ACTIVE", null));
        when(jedis.hgetAll("budget:c:USD_MICROCENTS"))
                .thenReturn(hashFull("led-c", "c", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1", "ACTIVE", "REJECT"));

        SortSpec sort = SortSpec.of("commit_overage_policy", SortDirection.ASC);
        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, sort);

        // ALLOW_IF_AVAILABLE < REJECT lexicographically; null last
        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-a", "led-c", "led-b");
    }

    @Test
    void list_sortByUnit_ascending() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:x:USD_MICROCENTS", "budget:y:TOKENS")));
        when(jedis.hgetAll("budget:x:USD_MICROCENTS"))
                .thenReturn(hashWith("led-x", "x", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:y:TOKENS"))
                .thenReturn(hashWith("led-y", "y", "TOKENS", 100, 0, 0, false, "tenant-1"));

        SortSpec sort = SortSpec.of("unit", SortDirection.ASC);
        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, sort);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-y", "led-x"); // TOKENS < USD_MICROCENTS
    }

    @Test
    void list_sortWithCursor_resumesInSortedOrder() {
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:z:USD_MICROCENTS", "budget:a:USD_MICROCENTS",
                        "budget:m:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:z:USD_MICROCENTS"))
                .thenReturn(hashWith("led-z", "z", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a", "a", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:m:USD_MICROCENTS"))
                .thenReturn(hashWith("led-m", "m", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));

        SortSpec sort = SortSpec.of("scope", SortDirection.ASC);
        List<BudgetLedger> page2 = repository.list(
                "tenant-1", BudgetListFilters.empty(), "led-a", 50, sort);

        assertThat(page2).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-m", "led-z");
    }

    @Test
    void list_sortUnknownField_fallsBackToLedgerIdTieBreaker() {
        // resolve() would 400 in the controller, but the repo must stay
        // total — unknown fields fall through to the ledger_id tie-breaker.
        SortSpec bogus = SortSpec.of("not_a_real_field", SortDirection.ASC);
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:z:USD_MICROCENTS", "budget:a:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:z:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b", "z", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a", "a", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));

        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, bogus);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-a", "led-b");
    }

    @Test
    void list_nullSortSpec_usesLegacyKeyPath() {
        // No sortSpec → legacy cursor-on-raw-key path (collectForTenant).
        when(jedis.smembers("budgets:tenant-1"))
                .thenReturn(new LinkedHashSet<>(List.of(
                        "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a", "a", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b", "b", "USD_MICROCENTS", 100, 0, 0, false, "tenant-1"));

        List<BudgetLedger> result = repository.list(
                "tenant-1", BudgetListFilters.empty(), null, 50, null);

        assertThat(result).hasSize(2);
    }

    @Test
    void listAllTenants_sortByTenantIdAscending_crossesTenantBoundary() {
        when(jedis.smembers("tenants"))
                .thenReturn(new LinkedHashSet<>(List.of("tenant-b", "tenant-a")));
        when(jedis.smembers("budgets:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:a1:USD_MICROCENTS")));
        when(jedis.smembers("budgets:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:b1:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a1", "a1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        when(jedis.hgetAll("budget:b1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b1", "b1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-b"));

        SortSpec sort = SortSpec.of("tenant_id", SortDirection.ASC);
        List<BudgetLedger> result = repository.listAllTenants(
                BudgetListFilters.empty(), null, 50, sort);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-a1", "led-b1");
    }

    @Test
    void listAllTenants_sortedCursor_resumesAtCompositeStrictlyNext() {
        when(jedis.smembers("tenants"))
                .thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        when(jedis.smembers("budgets:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:a1:USD_MICROCENTS")));
        when(jedis.smembers("budgets:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:b1:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:a1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-a1", "a1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        when(jedis.hgetAll("budget:b1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-b1", "b1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-b"));

        SortSpec sort = SortSpec.of("tenant_id", SortDirection.ASC);
        List<BudgetLedger> page2 = repository.listAllTenants(
                BudgetListFilters.empty(), "tenant-a|led-a1", 50, sort);

        assertThat(page2).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-b1");
    }

    @Test
    void listAllTenants_nullSortSpec_preservesLegacySkipForward() {
        when(jedis.smembers("tenants"))
                .thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-c")));
        when(jedis.smembers("budgets:tenant-c"))
                .thenReturn(new LinkedHashSet<>(List.of("budget:c1:USD_MICROCENTS")));
        when(jedis.hgetAll("budget:c1:USD_MICROCENTS"))
                .thenReturn(hashWith("led-c1", "c1", "USD_MICROCENTS", 100, 0, 0, false, "tenant-c"));

        // Cursor tenant-b was deleted between pages; legacy path skips forward.
        List<BudgetLedger> result = repository.listAllTenants(
                BudgetListFilters.empty(), "tenant-b|led-b1", 50, null);

        assertThat(result).extracting(BudgetLedger::getLedgerId)
                .containsExactly("led-c1");
    }

    @Test
    void listAllTenants_sorted_hydratesCompletePopulation() {
        // Regression: the former 2,000-row cap silently hid later budgets.
        int cap = 2000;
        int total = cap + 10;
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            String ledgerId = String.format("led-%05d", i);
            String scope = String.format("scope-%05d", i);
            String key = "budget:" + scope + ":USD_MICROCENTS";
            keys.add(key);
            lenient().when(jedis.hgetAll(key))
                    .thenReturn(hashWith(ledgerId, scope, "USD_MICROCENTS", 100, 0, 0, false, "tenant-a"));
        }
        when(jedis.smembers("tenants"))
                .thenReturn(new LinkedHashSet<>(List.of("tenant-a")));
        when(jedis.smembers("budgets:tenant-a")).thenReturn(keys);

        SortSpec sort = SortSpec.of("tenant_id", SortDirection.ASC);
        List<BudgetLedger> result = repository.listAllTenants(
                BudgetListFilters.empty(), null, 5, sort);

        assertThat(result).hasSize(5);
        verify(jedis, times(total)).hgetAll(anyString());
    }

    // ========== cascadeClose (spec v0.1.25.29 Rule 1) ==========

    @Test
    void cascadeClose_noOwnedBudgets_returnsEmpty() {
        when(jedis.smembers("budgets:tenant-1")).thenReturn(Collections.emptySet());

        List<BudgetRepository.CascadeCloseBudgetOutcome> outcomes = repository.cascadeClose("tenant-1").succeeded();

        assertThat(outcomes).isEmpty();
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void cascadeClose_nullKeySet_returnsEmpty() {
        when(jedis.smembers("budgets:tenant-1")).thenReturn(null);

        List<BudgetRepository.CascadeCloseBudgetOutcome> outcomes = repository.cascadeClose("tenant-1").succeeded();

        assertThat(outcomes).isEmpty();
    }

    @Test
    void cascadeClose_mixedStatuses_transitionsActiveAndFrozenSkipsClosed() {
        Set<String> keys = new LinkedHashSet<>(List.of(
            "budget:a:USD_MICROCENTS",
            "budget:b:USD_MICROCENTS",
            "budget:c:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        // Script returns {'OK', priorStatus, reserved} or {'ALREADY_CLOSED'}/{'NOT_FOUND'}
        when(jedis.eval(anyString(), eq(List.of("budget:a:USD_MICROCENTS",
                TenantCloseWorkRepository.outboxKey("tenant-1"))), anyList()))
            .thenReturn(List.of("OK", "ACTIVE", "0"));
        when(jedis.eval(anyString(), eq(List.of("budget:b:USD_MICROCENTS",
                TenantCloseWorkRepository.outboxKey("tenant-1"))), anyList()))
            .thenReturn(List.of("OK", "FROZEN", "250"));
        when(jedis.eval(anyString(), eq(List.of("budget:c:USD_MICROCENTS",
                TenantCloseWorkRepository.outboxKey("tenant-1"))), anyList()))
            .thenReturn(List.of("ALREADY_CLOSED"));

        Map<String, String> hashA = createBudgetHash("led-a", "a", "USD_MICROCENTS");
        hashA.put("status", "CLOSED");
        Map<String, String> hashB = createBudgetHash("led-b", "b", "USD_MICROCENTS");
        hashB.put("status", "CLOSED");
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(hashA);
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hashB);

        List<BudgetRepository.CascadeCloseBudgetOutcome> outcomes = repository.cascadeClose("tenant-1").succeeded();

        assertThat(outcomes).hasSize(2);
        BudgetRepository.CascadeCloseBudgetOutcome first = outcomes.get(0);
        assertThat(first.priorStatus()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(first.releasedReservedAmount()).isZero();
        assertThat(first.unit()).isEqualTo(UnitEnum.USD_MICROCENTS);

        BudgetRepository.CascadeCloseBudgetOutcome second = outcomes.get(1);
        assertThat(second.priorStatus()).isEqualTo(BudgetStatus.FROZEN);
        assertThat(second.releasedReservedAmount()).isEqualTo(250L);

        // Record's accessors already exercised above; also run `toString` for
        // equals/hashCode/toString coverage on the generated record class.
        assertThat(first.toString()).contains("led-a");
    }

    @Test
    void cascadeClose_luaException_skipsAndContinues() {
        Set<String> keys = new LinkedHashSet<>(List.of(
            "budget:bad:USD_MICROCENTS",
            "budget:good:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);

        when(jedis.eval(anyString(), eq(List.of("budget:bad:USD_MICROCENTS",
                TenantCloseWorkRepository.outboxKey("tenant-1"))), anyList()))
            .thenThrow(new RuntimeException("boom"));
        when(jedis.eval(anyString(), eq(List.of("budget:good:USD_MICROCENTS",
                TenantCloseWorkRepository.outboxKey("tenant-1"))), anyList()))
            .thenReturn(List.of("OK", "ACTIVE", "0"));

        Map<String, String> hashGood = createBudgetHash("led-good", "good", "USD_MICROCENTS");
        hashGood.put("status", "CLOSED");
        when(jedis.hgetAll("budget:good:USD_MICROCENTS")).thenReturn(hashGood);

        var report = repository.cascadeClose("tenant-1");
        List<BudgetRepository.CascadeCloseBudgetOutcome> outcomes = report.succeeded();

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).scope()).isEqualTo("good");
        assertThat(report.failed()).extracting(f -> f.resourceId())
            .containsExactly("budget:bad:USD_MICROCENTS");
    }

    @Test
    void cascadeClose_unparseableReservedAmount_coercesToZero() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS"));
        when(jedis.smembers("budgets:tenant-1")).thenReturn(keys);
        when(jedis.eval(anyString(), eq(List.of("budget:a:USD_MICROCENTS",
                TenantCloseWorkRepository.outboxKey("tenant-1"))), anyList()))
            .thenReturn(List.of("OK", "FROZEN", "not-a-number"));

        Map<String, String> hash = createBudgetHash("led-a", "a", "USD_MICROCENTS");
        hash.put("status", "CLOSED");
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(hash);

        List<BudgetRepository.CascadeCloseBudgetOutcome> outcomes = repository.cascadeClose("tenant-1").succeeded();

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).releasedReservedAmount()).isZero();
    }
}
