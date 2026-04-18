package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Focused coverage for {@link BudgetRepository#matchForBulk}
 * (spec v0.1.25.26 — bulk-action match phase). Exercises each of the
 * eight filter dimensions on {@link BudgetListFilters}, the cap+1
 * sentinel behaviour, tenant isolation, and the stale-index cleanup
 * branch. Split out from {@code BudgetRepositoryTest} to keep the
 * bulk-action-specific fixtures and hash shapes localised.
 */
@ExtendWith(MockitoExtension.class)
class BudgetRepositoryBulkMatchTest {

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

    private static Map<String, String> hash(
            String ledgerId, String tenantId, String scope, String unit,
            String allocated, String spent, String debt, String status,
            String isOverLimit) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("ledger_id", ledgerId);
        h.put("tenant_id", tenantId);
        h.put("scope", scope);
        h.put("unit", unit);
        h.put("allocated", allocated);
        h.put("remaining", allocated);
        h.put("reserved", "0");
        h.put("spent", spent);
        h.put("debt", debt);
        h.put("overdraft_limit", "0");
        h.put("is_over_limit", isOverLimit);
        h.put("status", status);
        h.put("created_at", "1700000000000");
        return h;
    }

    private static Map<String, String> activeUsd(String scope, String tenant, long allocated, long spent) {
        return hash("led-" + scope, tenant, scope, "USD_MICROCENTS",
                String.valueOf(allocated), String.valueOf(spent), "0",
                BudgetStatus.ACTIVE.name(), "false");
    }

    // -------- Filter dimensions --------

    @Test
    void matchForBulk_scopePrefix_matchesOnlyPrefixedRows() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:tenant:acme/workspace:eng:USD_MICROCENTS",
                "budget:tenant:acme/workspace:ops:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:tenant:acme/workspace:eng:USD_MICROCENTS"))
                .thenReturn(activeUsd("tenant:acme/workspace:eng", "acme", 1000, 0));
        when(jedis.hgetAll("budget:tenant:acme/workspace:ops:USD_MICROCENTS"))
                .thenReturn(activeUsd("tenant:acme/workspace:ops", "acme", 1000, 0));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                new BudgetListFilters("tenant:acme/workspace:eng", null, null, null, null, null, null),
                500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("tenant:acme/workspace:eng");
    }

    @Test
    void matchForBulk_unit_filtersByUnit() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:s1:USD_MICROCENTS", "budget:s2:TOKENS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:s1:USD_MICROCENTS")).thenReturn(
                activeUsd("s1", "acme", 1000, 0));
        when(jedis.hgetAll("budget:s2:TOKENS")).thenReturn(hash(
                "led-s2", "acme", "s2", "TOKENS", "500", "0", "0",
                BudgetStatus.ACTIVE.name(), "false"));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                new BudgetListFilters(null, UnitEnum.TOKENS, null, null, null, null, null),
                500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUnit()).isEqualTo(UnitEnum.TOKENS);
    }

    @Test
    void matchForBulk_status_filtersByStatus() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(
                activeUsd("a", "acme", 1000, 0));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hash(
                "led-b", "acme", "b", "USD_MICROCENTS", "1000", "0", "0",
                BudgetStatus.FROZEN.name(), "false"));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                new BudgetListFilters(null, null, BudgetStatus.FROZEN, null, null, null, null),
                500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("b");
    }

    @Test
    void matchForBulk_overLimit_filtersByOverLimitFlag() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(
                activeUsd("a", "acme", 1000, 0));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hash(
                "led-b", "acme", "b", "USD_MICROCENTS", "1000", "0", "0",
                BudgetStatus.ACTIVE.name(), "true"));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                new BudgetListFilters(null, null, null, Boolean.TRUE, null, null, null),
                500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("b");
    }

    @Test
    void matchForBulk_hasDebt_filtersByDebtPositive() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(hash(
                "led-a", "acme", "a", "USD_MICROCENTS", "1000", "0", "0",
                BudgetStatus.ACTIVE.name(), "false"));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(hash(
                "led-b", "acme", "b", "USD_MICROCENTS", "1000", "0", "250",
                BudgetStatus.ACTIVE.name(), "false"));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                new BudgetListFilters(null, null, null, null, Boolean.TRUE, null, null),
                500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("b");
    }

    @Test
    void matchForBulk_utilizationRange_filtersBySpentOverAllocated() {
        // a: 0% util, b: 50% util, c: 100% util. range [0.4, 0.9] → only b.
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS", "budget:c:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(
                activeUsd("a", "acme", 1000, 0));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(
                activeUsd("b", "acme", 1000, 500));
        when(jedis.hgetAll("budget:c:USD_MICROCENTS")).thenReturn(
                activeUsd("c", "acme", 1000, 1000));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                new BudgetListFilters(null, null, null, null, null, 0.4, 0.9),
                500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("b");
    }

    @Test
    void matchForBulk_search_matchesTenantIdOrScopeSubstring() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:tenant:acme/workspace:eng:USD_MICROCENTS",
                "budget:tenant:acme/workspace:ops:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:tenant:acme/workspace:eng:USD_MICROCENTS"))
                .thenReturn(activeUsd("tenant:acme/workspace:eng", "acme", 1000, 0));
        when(jedis.hgetAll("budget:tenant:acme/workspace:ops:USD_MICROCENTS"))
                .thenReturn(activeUsd("tenant:acme/workspace:ops", "acme", 1000, 0));

        BudgetListFilters filters = new BudgetListFilters(null, null, null, null, null, null, null, "eng");
        List<BudgetLedger> result = repository.matchForBulk("acme", filters, 500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).contains("eng");
    }

    @Test
    void matchForBulk_emptyFilter_returnsAllMatching() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:a:USD_MICROCENTS", "budget:b:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(
                activeUsd("a", "acme", 1000, 0));
        when(jedis.hgetAll("budget:b:USD_MICROCENTS")).thenReturn(
                activeUsd("b", "acme", 1000, 0));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                BudgetListFilters.empty(), 500);

        assertThat(result).hasSize(2);
    }

    @Test
    void matchForBulk_nullFilter_treatedAsEmpty() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(
                activeUsd("a", "acme", 1000, 0));

        List<BudgetLedger> result = repository.matchForBulk("acme", null, 500);

        assertThat(result).hasSize(1);
    }

    // -------- Cap + tenant isolation + cleanup --------

    @Test
    void matchForBulk_capPlusOneSentinel_stopsAfterCapExceeded() {
        // Build 5 matching keys with cap=3 → expect result.size() > cap
        // (the controller reads this as "too wide" and raises LIMIT_EXCEEDED).
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) keys.add("budget:s" + i + ":USD_MICROCENTS");
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        for (int i = 0; i < 5; i++) {
            // Loop breaks after matched.size() > cap, so some of these
            // stubs may go unused — mark lenient to keep strict mode happy.
            lenient().when(jedis.hgetAll("budget:s" + i + ":USD_MICROCENTS"))
                    .thenReturn(activeUsd("s" + i, "acme", 1000, 0));
        }

        List<BudgetLedger> result = repository.matchForBulk("acme",
                BudgetListFilters.empty(), 3);

        // size > cap is the signal; we stop reading after matched.size() > cap.
        assertThat(result.size()).isGreaterThan(3);
        assertThat(result.size()).isLessThanOrEqualTo(5);
    }

    @Test
    void matchForBulk_tenantIsolation_onlyReadsRequestedTenantSet() {
        Set<String> keys = new LinkedHashSet<>(List.of("budget:a:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:a:USD_MICROCENTS")).thenReturn(
                activeUsd("a", "acme", 1000, 0));

        repository.matchForBulk("acme", BudgetListFilters.empty(), 500);

        verify(jedis).smembers("budgets:acme");
        verify(jedis, never()).smembers("budgets:other");
    }

    @Test
    void matchForBulk_emptyHashData_cleansIndexAndSkips() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:stale:USD_MICROCENTS", "budget:valid:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        when(jedis.hgetAll("budget:stale:USD_MICROCENTS")).thenReturn(Collections.emptyMap());
        when(jedis.hgetAll("budget:valid:USD_MICROCENTS")).thenReturn(
                activeUsd("valid", "acme", 1000, 0));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                BudgetListFilters.empty(), 500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("valid");
        verify(jedis).srem("budgets:acme", "budget:stale:USD_MICROCENTS");
    }

    @Test
    void matchForBulk_parseFailure_swallowedAndContinues() {
        // Bad unit → hashToBudgetLedger throws → swallowed by the catch,
        // good row still returned.
        Set<String> keys = new LinkedHashSet<>(List.of(
                "budget:bad:USD_MICROCENTS", "budget:ok:USD_MICROCENTS"));
        when(jedis.smembers("budgets:acme")).thenReturn(keys);
        Map<String, String> bad = activeUsd("bad", "acme", 1000, 0);
        bad.put("unit", "NOT_A_UNIT");
        when(jedis.hgetAll("budget:bad:USD_MICROCENTS")).thenReturn(bad);
        when(jedis.hgetAll("budget:ok:USD_MICROCENTS")).thenReturn(
                activeUsd("ok", "acme", 1000, 0));

        List<BudgetLedger> result = repository.matchForBulk("acme",
                BudgetListFilters.empty(), 500);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).isEqualTo("ok");
    }

    @Test
    void matchForBulk_noKeys_returnsEmptyList() {
        when(jedis.smembers("budgets:acme")).thenReturn(Collections.emptySet());

        List<BudgetLedger> result = repository.matchForBulk("acme",
                BudgetListFilters.empty(), 500);

        assertThat(result).isEmpty();
    }
}
