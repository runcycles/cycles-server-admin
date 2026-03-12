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

    // Lua script for atomic budget funding with idempotency — prevents race conditions on concurrent updates.
    // KEYS[1] = budget key
    // ARGV[1] = operation, ARGV[2] = amount, ARGV[3] = now, ARGV[4] = tenant_id,
    // ARGV[5] = idempotency_key (empty = skip), ARGV[6] = payload_hash (empty = skip)
    private static final String FUND_LUA =
        "local key = KEYS[1]\n" +
        // Atomic idempotency check: same (idem_key) seen before → replay or mismatch
        "local idem_key_arg = ARGV[5]\n" +
        "local payload_hash = ARGV[6]\n" +
        "if idem_key_arg ~= '' then\n" +
        "  local idem_redis = 'idempotency:fund:' .. idem_key_arg\n" +
        "  local cached = redis.call('GET', idem_redis)\n" +
        "  if cached then\n" +
        "    if payload_hash ~= '' then\n" +
        "      local stored_hash = redis.call('GET', idem_redis .. ':hash')\n" +
        "      if stored_hash and stored_hash ~= payload_hash then\n" +
        "        return {'IDEMPOTENCY_MISMATCH'}\n" +
        "      end\n" +
        "    end\n" +
        "    return {'IDEMPOTENT_HIT', cached}\n" +
        "  end\n" +
        "end\n" +
        "if redis.call('EXISTS', key) == 0 then return {'NOT_FOUND'} end\n" +
        "local tid = redis.call('HGET', key, 'tenant_id') or ''\n" +
        "if ARGV[4] ~= '' and tid ~= ARGV[4] then return {'FORBIDDEN'} end\n" +
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
        // Store idempotency result atomically (24h TTL)
        "if idem_key_arg ~= '' then\n" +
        "  local idem_redis = 'idempotency:fund:' .. idem_key_arg\n" +
        "  local result_json = i(prev_allocated) .. '|' .. i(allocated) .. '|' .. i(prev_remaining) .. '|' .. i(remaining) .. '|' .. i(prev_debt) .. '|' .. i(debt) .. '|' .. is_over\n" +
        "  redis.call('SET', idem_redis, result_json)\n" +
        "  redis.call('EXPIRE', idem_redis, 86400)\n" +
        "  if payload_hash ~= '' then\n" +
        "    redis.call('SET', idem_redis .. ':hash', payload_hash)\n" +
        "    redis.call('EXPIRE', idem_redis .. ':hash', 86400)\n" +
        "  end\n" +
        "end\n" +
        "return {'OK', i(prev_allocated), i(allocated), i(prev_remaining),\n" +
        "  i(remaining), i(prev_debt), i(debt), is_over}\n";

    // Lua script for atomic budget creation — prevents TOCTOU race on duplicate check.
    private static final String CREATE_BUDGET_LUA =
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end\n" +
        "redis.call('HMSET', KEYS[1], unpack(ARGV))\n" +
        "redis.call('SADD', KEYS[2], KEYS[1])\n" +
        "return 1\n";

    public BudgetLedger create(String tenantId, BudgetCreateRequest request) {
        LOG.info("Creating budget: scope={}, unit={}", request.getScope(), request.getUnit());
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + request.getScope() + ":" + request.getUnit();
            String indexKey = "budgets:" + tenantId;

            BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId(UUID.randomUUID().toString())
                .tenantId(tenantId)
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

    public List<BudgetLedger> list(String tenantId, String scopePrefix, UnitEnum unitFilter, BudgetStatus statusFilter, String cursor, int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.smembers("budgets:" + tenantId);
            List<String> sortedKeys = new ArrayList<>(keys);
            Collections.sort(sortedKeys);
            List<BudgetLedger> ledgers = new ArrayList<>();
            boolean pastCursor = (cursor == null || cursor.isBlank());
            for (String key : sortedKeys) {
                try {
                    Map<String, String> hash = jedis.hgetAll(key);
                    if (hash.isEmpty()) {
                        LOG.warn("Budget data missing for key: {}, cleaning index", key);
                        jedis.srem("budgets:" + tenantId, key);
                        continue;
                    }
                    BudgetLedger ledger = hashToBudgetLedger(hash, key);
                    if (!pastCursor) {
                        if (ledger.getLedgerId().equals(cursor)) pastCursor = true;
                        continue;
                    }
                    if (scopePrefix != null && !ledger.getScope().startsWith(scopePrefix)) continue;
                    if (unitFilter != null && ledger.getUnit() != unitFilter) continue;
                    if (statusFilter != null && ledger.getStatus() != statusFilter) continue;
                    ledgers.add(ledger);
                    if (ledgers.size() >= limit) break;
                } catch (Exception e) {
                    LOG.warn("Failed to parse budget: {}", key, e);
                }
            }
            return ledgers;
        }
    }

    public List<BudgetLedger> list(String tenantId) {
        return list(tenantId, null, null, null, null, 1000);
    }

    public BudgetFundingResponse fund(String tenantId, String scope, UnitEnum unit, BudgetFundingRequest request) {
        LOG.info("Funding budget: scope={}, unit={}, op={}, tenant={}", scope, unit, request.getOperation(), tenantId);
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + scope + ":" + unit;
            long changeAmount = request.getAmount().getAmount();
            String now = String.valueOf(Instant.now().toEpochMilli());

            // Compute payload hash for idempotency mismatch detection (spec MUST)
            String idempotencyKey = (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank())
                ? request.getIdempotencyKey() : "";
            String payloadHash = "";
            if (!idempotencyKey.isEmpty()) {
                payloadHash = computePayloadHash(request);
            }

            // Atomic fund via Lua — idempotency check + read + compute + write + cache in one step
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(FUND_LUA,
                List.of(key),
                List.of(request.getOperation().name(), String.valueOf(changeAmount), now,
                         tenantId != null ? tenantId : "", idempotencyKey, payloadHash));

            String status = result.get(0);
            if ("IDEMPOTENCY_MISMATCH".equals(status)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.IDEMPOTENCY_MISMATCH,
                    "Idempotency key reused with different payload", 409);
            }
            if ("IDEMPOTENT_HIT".equals(status)) {
                // Reconstruct response from cached pipe-delimited values
                String cached = result.get(1);
                return parseCachedFundResponse(cached, unit, request.getOperation());
            }
            if ("FORBIDDEN".equals(status)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.FORBIDDEN,
                    "Budget does not belong to authenticated tenant", 403);
            }
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

            LOG.info("Funded budget atomically: key={}, op={}", key, request.getOperation());
            return response;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BudgetFundingResponse parseCachedFundResponse(String cached, UnitEnum unit, FundingOperation operation) {
        String[] parts = cached.split("\\|");
        return BudgetFundingResponse.builder()
            .operation(operation)
            .previousAllocated(new Amount(unit, Long.parseLong(parts[0])))
            .newAllocated(new Amount(unit, Long.parseLong(parts[1])))
            .previousRemaining(new Amount(unit, Long.parseLong(parts[2])))
            .newRemaining(new Amount(unit, Long.parseLong(parts[3])))
            .previousDebt(new Amount(unit, Long.parseLong(parts[4])))
            .newDebt(new Amount(unit, Long.parseLong(parts[5])))
            .timestamp(Instant.now())
            .build();
    }

    private String computePayloadHash(Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            LOG.warn("Failed to compute payload hash, skipping mismatch detection", e);
            return "";
        }
    }

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
