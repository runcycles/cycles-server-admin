package io.runcycles.admin.data.repository;

import io.runcycles.admin.data.exception.GovernanceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.UnitEnum;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;

import java.time.Instant;
import java.util.*;

/** Budget Governance v0.1.23 - Fixed to use Redis HASH */
@Repository
public class BudgetRepository {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    // Lua script for atomic budget funding — prevents race conditions on concurrent updates.
    // Returns: {status, prev_allocated, new_allocated, prev_remaining, new_remaining, prev_debt, new_debt, is_over_limit}
    private static final String FUND_LUA =
        "local key = KEYS[1]\n" +
        "if redis.call('EXISTS', key) == 0 then return {'NOT_FOUND'} end\n" +
        "local status = redis.call('HGET', key, 'status') or 'ACTIVE'\n" +
        "if status == 'FROZEN' then return {'BUDGET_FROZEN'} end\n" +
        "if status == 'CLOSED' then return {'BUDGET_CLOSED'} end\n" +
        "local op = ARGV[1]\n" +
        "local amount = tonumber(ARGV[2])\n" +
        "local now = ARGV[3]\n" +
        "local allocated = tonumber(redis.call('HGET', key, 'allocated') or '0')\n" +
        "local remaining = tonumber(redis.call('HGET', key, 'remaining') or '0')\n" +
        "local reserved = tonumber(redis.call('HGET', key, 'reserved') or '0')\n" +
        "local spent = tonumber(redis.call('HGET', key, 'spent') or '0')\n" +
        "local debt = tonumber(redis.call('HGET', key, 'debt') or '0')\n" +
        "local overdraft = tonumber(redis.call('HGET', key, 'overdraft_limit') or '0')\n" +
        "local prev_allocated = allocated\n" +
        "local prev_remaining = remaining\n" +
        "local prev_debt = debt\n" +
        "if op == 'CREDIT' then\n" +
        "  allocated = allocated + amount\n" +
        "  remaining = remaining + amount\n" +
        "elseif op == 'DEBIT' then\n" +
        "  if remaining < amount then return {'INSUFFICIENT_FUNDS'} end\n" +
        "  allocated = allocated - amount\n" +
        "  remaining = remaining - amount\n" +
        "elseif op == 'RESET' then\n" +
        "  allocated = amount\n" +
        "  remaining = amount - reserved - spent - debt\n" +
        "elseif op == 'REPAY_DEBT' then\n" +
        "  local repayment = math.min(debt, amount)\n" +
        "  debt = debt - repayment\n" +
        "  if repayment < amount then\n" +
        "    remaining = remaining + (amount - repayment)\n" +
        "  end\n" +
        "end\n" +
        "local is_over = 'false'\n" +
        "if debt > overdraft then is_over = 'true' end\n" +
        "local function i(n) return string.format('%.0f', n) end\n" +
        "redis.call('HMSET', key, 'allocated', i(allocated), 'remaining', i(remaining),\n" +
        "  'debt', i(debt), 'reserved', i(reserved), 'spent', i(spent),\n" +
        "  'is_over_limit', is_over, 'updated_at', now)\n" +
        "return {'OK', i(prev_allocated), i(allocated), i(prev_remaining),\n" +
        "  i(remaining), i(prev_debt), i(debt), is_over}\n";

    // Lua script for atomic budget creation — prevents TOCTOU race on duplicate check.
    private static final String CREATE_BUDGET_LUA =
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end\n" +
        "redis.call('HMSET', KEYS[1], unpack(ARGV))\n" +
        "redis.call('SADD', KEYS[2], KEYS[1])\n" +
        "return 1\n";
    
    public BudgetLedger create(BudgetCreateRequest request) {
        LOG.info("Creating budget: scope={}, unit={}", request.getScope(), request.getUnit());
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + request.getScope() + ":" + request.getUnit();
            String indexKey = "budgets:" + request.getTenantId();

            BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId(UUID.randomUUID().toString())
                .tenantId(request.getTenantId())
                .scope(request.getScope())
                .unit(request.getUnit())
                .allocated(request.getAllocated())
                .remaining(request.getAllocated())
                .reserved(new Amount(request.getUnit(), 0L))
                .spent(new Amount(request.getUnit(), 0L))
                .debt(new Amount(request.getUnit(), 0L))
                .overdraftLimit(request.getOverdraftLimit() != null ? request.getOverdraftLimit() : new Amount(request.getUnit(), 0L))
                .isOverLimit(false)
                .commitOveragePolicy(request.getCommitOveragePolicy())
                .status(BudgetStatus.ACTIVE)
                .rolloverPolicy(request.getRolloverPolicy())
                .periodStart(request.getPeriodStart())
                .periodEnd(request.getPeriodEnd())
                .createdAt(Instant.now())
                .build();

            // Build flat key-value list for HMSET via Lua
            List<String> args = new ArrayList<>();
            args.add("ledger_id"); args.add(ledger.getLedgerId());
            args.add("tenant_id"); args.add(ledger.getTenantId());
            args.add("scope"); args.add(ledger.getScope());
            args.add("unit"); args.add(ledger.getUnit().name());
            args.add("allocated"); args.add(String.valueOf(ledger.getAllocated().getAmount()));
            args.add("remaining"); args.add(String.valueOf(ledger.getRemaining().getAmount()));
            args.add("reserved"); args.add(String.valueOf(ledger.getReserved().getAmount()));
            args.add("spent"); args.add(String.valueOf(ledger.getSpent().getAmount()));
            args.add("debt"); args.add(String.valueOf(ledger.getDebt().getAmount()));
            args.add("overdraft_limit"); args.add(String.valueOf(ledger.getOverdraftLimit().getAmount()));
            args.add("is_over_limit"); args.add(String.valueOf(ledger.getIsOverLimit()));
            args.add("status"); args.add(ledger.getStatus().name());
            args.add("created_at"); args.add(String.valueOf(ledger.getCreatedAt().toEpochMilli()));
            if (ledger.getCommitOveragePolicy() != null) {
                args.add("commit_overage_policy"); args.add(ledger.getCommitOveragePolicy().name());
            }
            if (ledger.getRolloverPolicy() != null) {
                args.add("rollover_policy"); args.add(ledger.getRolloverPolicy().name());
            }
            if (ledger.getPeriodStart() != null) {
                args.add("period_start"); args.add(String.valueOf(ledger.getPeriodStart().toEpochMilli()));
            }
            if (ledger.getPeriodEnd() != null) {
                args.add("period_end"); args.add(String.valueOf(ledger.getPeriodEnd().toEpochMilli()));
            }

            // Atomic create: EXISTS + HMSET + SADD in one Lua call
            Object result = jedis.eval(CREATE_BUDGET_LUA, List.of(key, indexKey), args);
            if (Long.valueOf(0).equals(result)) {
                throw GovernanceException.duplicateResource("Budget", request.getScope());
            }
            LOG.info("Created budget as HASH: {}", key);

            return ledger;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<BudgetLedger> list(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.smembers("budgets:" + tenantId);
            List<BudgetLedger> ledgers = new ArrayList<>();
            for (String key : keys) {
                try {
                    Map<String, String> hash = jedis.hgetAll(key);
                    if (hash.isEmpty()) {
                        LOG.warn("Budget data missing for key: {}, cleaning index", key);
                        jedis.srem("budgets:" + tenantId, key);
                        continue;
                    }
                    ledgers.add(hashToBudgetLedger(hash, key));
                } catch (Exception e) {
                    LOG.warn("Failed to parse budget: {}", key, e);
                }
            }
            return ledgers;
        }
    }
    
    public BudgetFundingResponse fund(String scope, UnitEnum unit, BudgetFundingRequest request) {
        LOG.info("Funding budget: scope={}, unit={}, op={}", scope, unit, request.getOperation());
        try (Jedis jedis = jedisPool.getResource()) {
            // Idempotency check: return cached response for duplicate requests
            String idempotencyRedisKey = "idempotency:" + request.getIdempotencyKey();
            String cached = jedis.get(idempotencyRedisKey);
            if (cached != null) {
                try {
                    return objectMapper.readValue(cached, BudgetFundingResponse.class);
                } catch (Exception e) {
                    LOG.warn("Failed to parse cached idempotency response", e);
                }
            }

            String key = "budget:" + scope + ":" + unit;
            long changeAmount = request.getAmount().getAmount();
            String now = String.valueOf(Instant.now().toEpochMilli());

            // Atomic fund via Lua — read, compute, write in one step
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(FUND_LUA,
                List.of(key),
                List.of(request.getOperation().name(), String.valueOf(changeAmount), now));

            String status = result.get(0);
            if ("NOT_FOUND".equals(status)) {
                throw GovernanceException.budgetNotFound(scope);
            }
            if ("INSUFFICIENT_FUNDS".equals(status)) {
                throw GovernanceException.insufficientFunds(scope);
            }
            if ("BUDGET_FROZEN".equals(status)) {
                throw GovernanceException.budgetFrozen(scope);
            }
            if ("BUDGET_CLOSED".equals(status)) {
                throw GovernanceException.budgetClosed(scope);
            }

            long prevAllocated = Long.parseLong(result.get(1));
            long newAllocated = Long.parseLong(result.get(2));
            long prevRemaining = Long.parseLong(result.get(3));
            long newRemainingVal = Long.parseLong(result.get(4));
            long prevDebtVal = Long.parseLong(result.get(5));
            long newDebtVal = Long.parseLong(result.get(6));

            BudgetFundingResponse response = BudgetFundingResponse.builder()
                .operation(request.getOperation())
                .previousAllocated(new Amount(unit, prevAllocated))
                .newAllocated(new Amount(unit, newAllocated))
                .previousRemaining(new Amount(unit, prevRemaining))
                .newRemaining(new Amount(unit, newRemainingVal))
                .previousDebt(new Amount(unit, prevDebtVal))
                .newDebt(new Amount(unit, newDebtVal))
                .timestamp(Instant.now())
                .build();

            // Cache response for idempotency (24h TTL)
            try {
                jedis.setex(idempotencyRedisKey, 86400, objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                LOG.warn("Failed to cache idempotency response", e);
            }

            LOG.info("Funded budget atomically: key={}, op={}", key, request.getOperation());
            return response;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Helper method to convert Redis HASH to BudgetLedger object
     */
    private BudgetLedger hashToBudgetLedger(Map<String, String> hash, String key) {
        String scope = hash.get("scope");
        String unitStr = hash.get("unit");
        if (scope == null || unitStr == null) {
            throw new IllegalStateException("Corrupt budget hash for key: " + key + " (missing scope or unit)");
        }
        UnitEnum unit = UnitEnum.valueOf(unitStr);
        
        return BudgetLedger.builder()
            .ledgerId(hash.get("ledger_id"))
            .tenantId(hash.get("tenant_id"))
            .scope(scope)
            .unit(unit)
            .allocated(new Amount(unit, Long.parseLong(hash.getOrDefault("allocated", "0"))))
            .remaining(new Amount(unit, Long.parseLong(hash.getOrDefault("remaining", "0"))))
            .reserved(new Amount(unit, Long.parseLong(hash.getOrDefault("reserved", "0"))))
            .spent(new Amount(unit, Long.parseLong(hash.getOrDefault("spent", "0"))))
            .debt(new Amount(unit, Long.parseLong(hash.getOrDefault("debt", "0"))))
            .overdraftLimit(new Amount(unit, Long.parseLong(hash.getOrDefault("overdraft_limit", "0"))))
            .isOverLimit(Boolean.parseBoolean(hash.getOrDefault("is_over_limit", "false")))
            .commitOveragePolicy(hash.containsKey("commit_overage_policy") ? 
                CommitOveragePolicy.valueOf(hash.get("commit_overage_policy")) : null)
            .status(BudgetStatus.valueOf(hash.getOrDefault("status", "ACTIVE")))
            .rolloverPolicy(hash.containsKey("rollover_policy") ? 
                RolloverPolicy.valueOf(hash.get("rollover_policy")) : null)
            .periodStart(hash.containsKey("period_start") ? 
                Instant.ofEpochMilli(Long.parseLong(hash.get("period_start"))) : null)
            .periodEnd(hash.containsKey("period_end") ? 
                Instant.ofEpochMilli(Long.parseLong(hash.get("period_end"))) : null)
            .createdAt(hash.containsKey("created_at") ? 
                Instant.ofEpochMilli(Long.parseLong(hash.get("created_at"))) : Instant.now())
            .updatedAt(hash.containsKey("updated_at") ? 
                Instant.ofEpochMilli(Long.parseLong(hash.get("updated_at"))) : null)
            .build();
    }
}
