package io.runcycles.admin.data.repository;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.logging.LogSanitizer;
import io.runcycles.admin.data.repository.support.CascadeMutationResult;
import io.runcycles.admin.data.repository.support.SortedQueryGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.shared.UnitEnum;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;

import java.time.Instant;
import java.util.*;

/** Budget Governance - Fixed to use Redis HASH */
@Repository
public class BudgetRepository {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetRepository.class);

    /**
     * Normalize scope values to lowercase to match the runtime server's
     * ScopeDerivationService which lowercases all scope segments.
     * Preserves the "level:value" structure but lowercases the values.
     */
    private static String normalizeScope(String scope) {
        if (scope == null) return null;
        return scope.toLowerCase();
    }
    @Autowired private JedisPool jedisPool;
    @Autowired @Qualifier("redisObjectMapper") private ObjectMapper objectMapper;

    // Lua script for atomic budget funding with idempotency — prevents race conditions on concurrent updates.
    // KEYS[1] = budget key
    // ARGV[1] = operation, ARGV[2] = amount, ARGV[3] = now, ARGV[4] = tenant_id,
    // ARGV[5] = idempotency_key (empty = skip), ARGV[6] = payload_hash (empty = skip),
    // ARGV[7] = spent_override (empty = use default 0 for RESET_SPENT; ignored for
    //          other operations). Added in v0.1.25.17 with the RESET_SPENT operation.
    //
    // Idempotency cache key format is versioned: 'idempotency:fund:v2:<key>'. The v2
    // bump coincides with the expansion of the cached result from 7 to 9
    // pipe-delimited fields (adds prev_spent|new_spent). Pre-v2 cache entries expire
    // naturally within 24h, so old and new coexist without parse-failure risk during
    // deploy. Readers of the v1 key space are unaffected because we never read from
    // the old prefix here.
    private static final String FUND_LUA =
        "local key = KEYS[1]\n" +
        // Atomic idempotency check: same (idem_key) seen before → replay or mismatch
        "local idem_key_arg = ARGV[5]\n" +
        "local payload_hash = ARGV[6]\n" +
        "if idem_key_arg ~= '' then\n" +
        "  local idem_redis = 'idempotency:fund:v2:' .. idem_key_arg\n" +
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
        "local spent_override_arg = ARGV[7]\n" +
        "local allocated = tonumber(redis.call('HGET', key, 'allocated') or '0')\n" +
        "local remaining = tonumber(redis.call('HGET', key, 'remaining') or '0')\n" +
        "local reserved = tonumber(redis.call('HGET', key, 'reserved') or '0')\n" +
        "local spent = tonumber(redis.call('HGET', key, 'spent') or '0')\n" +
        "local debt = tonumber(redis.call('HGET', key, 'debt') or '0')\n" +
        "local overdraft = tonumber(redis.call('HGET', key, 'overdraft_limit') or '0')\n" +
        "local prev_allocated = allocated\n" +
        "local prev_remaining = remaining\n" +
        "local prev_debt = debt\n" +
        "local prev_spent = spent\n" +
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
        "elseif op == 'RESET_SPENT' then\n" +
        // New billing-period operation added in v0.1.25.17. Sets allocated to the given
        // amount AND sets spent to the optional ARGV[7] override (defaulting to 0).
        // Preserves reserved (active reservations straddle the period boundary) and
        // debt (periods don't forgive debt — REPAY_DEBT is the explicit channel).
        // Remaining recomputed from the ledger invariant; allowed to go negative when
        // (allocated - spent - reserved - debt) < 0, matching overdraft semantics.
        "  allocated = amount\n" +
        "  if spent_override_arg ~= '' then\n" +
        "    spent = tonumber(spent_override_arg)\n" +
        "    if spent == nil or spent < 0 then\n" +
        "      return {'INVALID_REQUEST', 'spent must be >= 0'}\n" +
        "    end\n" +
        "  else\n" +
        "    spent = 0\n" +
        "  end\n" +
        "  remaining = allocated - spent - reserved - debt\n" +
        "elseif op == 'REPAY_DEBT' then\n" +
        "  local repayment = math.min(debt, amount)\n" +
        "  debt = debt - repayment\n" +
        "  remaining = remaining + repayment\n" +
        "  if repayment < amount then\n" +
        "    allocated = allocated + (amount - repayment)\n" +
        "    remaining = remaining + (amount - repayment)\n" +
        "  end\n" +
        "end\n" +
        "local is_over = 'false'\n" +
        "if debt > overdraft then is_over = 'true' end\n" +
        "local function i(n) return string.format('%.0f', n) end\n" +
        "redis.call('HMSET', key, 'allocated', i(allocated), 'remaining', i(remaining),\n" +
        "  'debt', i(debt), 'reserved', i(reserved), 'spent', i(spent),\n" +
        "  'is_over_limit', is_over, 'updated_at', now)\n" +
        // Store idempotency result atomically (24h TTL). Cache value is 9
        // pipe-delimited fields (v2 format): prev_allocated|allocated|prev_remaining|
        // remaining|prev_debt|debt|is_over|prev_spent|spent. v2 cache key prefix
        // (above) prevents v1 consumers from parsing this expanded format.
        "if idem_key_arg ~= '' then\n" +
        "  local idem_redis = 'idempotency:fund:v2:' .. idem_key_arg\n" +
        "  local result_json = i(prev_allocated) .. '|' .. i(allocated) .. '|' .. i(prev_remaining) .. '|' .. i(remaining) .. '|' .. i(prev_debt) .. '|' .. i(debt) .. '|' .. is_over .. '|' .. i(prev_spent) .. '|' .. i(spent)\n" +
        "  redis.call('SET', idem_redis, result_json)\n" +
        "  redis.call('EXPIRE', idem_redis, 86400)\n" +
        "  if payload_hash ~= '' then\n" +
        "    redis.call('SET', idem_redis .. ':hash', payload_hash)\n" +
        "    redis.call('EXPIRE', idem_redis .. ':hash', 86400)\n" +
        "  end\n" +
        "end\n" +
        "return {'OK', i(prev_allocated), i(allocated), i(prev_remaining),\n" +
        "  i(remaining), i(prev_debt), i(debt), is_over, i(prev_spent), i(spent)}\n";

    // Lua script for atomic budget creation — validates tenant exists and is ACTIVE,
    // prevents TOCTOU race on duplicate check.
    // KEYS[1] = budget key, KEYS[2] = tenant budget index, KEYS[3] = tenant key
    // ARGV = flat key-value pairs for HMSET
    private static final String CREATE_BUDGET_LUA =
        "local tenant_json = redis.call('GET', KEYS[3])\n" +
        "if not tenant_json then return -1 end\n" +
        "local tenant = cjson.decode(tenant_json)\n" +
        "local ts = tenant['status'] or 'ACTIVE'\n" +
        "if ts ~= 'ACTIVE' then return -2 end\n" +
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end\n" +
        "redis.call('HMSET', KEYS[1], unpack(ARGV))\n" +
        "redis.call('SADD', KEYS[2], KEYS[1])\n" +
        "return 1\n";

    public BudgetLedger create(String tenantId, BudgetCreateRequest request) {
        String normalizedScope = normalizeScope(request.getScope());
        LOG.info("Creating budget: tenant_id={} scope={} unit={}", LogSanitizer.safe(tenantId), LogSanitizer.safe(normalizedScope), request.getUnit());
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + normalizedScope + ":" + request.getUnit();
            String indexKey = "budgets:" + tenantId;

            BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .scope(normalizedScope)
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
            if (request.getMetadata() != null) {
                try {
                    args.add("metadata_json"); args.add(objectMapper.writeValueAsString(request.getMetadata()));
                    ledger.setMetadata(request.getMetadata());
                } catch (Exception e) { /* skip metadata on serialization error */ }
            }

            // Atomic create: tenant check + EXISTS + HMSET + SADD in one Lua call
            String tenantKey = "tenant:" + tenantId;
            Object result = jedis.eval(CREATE_BUDGET_LUA, List.of(key, indexKey, tenantKey), args);
            long resultCode = (Long) result;
            if (resultCode == -1) {
                throw GovernanceException.tenantNotFound(tenantId);
            }
            if (resultCode == -2) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Tenant is not ACTIVE: " + tenantId, 400);
            }
            if (resultCode == 0) {
                throw GovernanceException.duplicateResource("Budget", request.getScope());
            }
            LOG.info("Created budget: tenant_id={} ledger_id={} scope={} unit={} redis_type=hash",
                LogSanitizer.safe(tenantId), LogSanitizer.safe(ledger.getLedgerId()), LogSanitizer.safe(ledger.getScope()), ledger.getUnit());

            return ledger;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Lua script for atomic budget update — validates tenant ownership, applies partial updates,
    // and recalculates is_over_limit after overdraft_limit change.
    // KEYS[1] = budget key
    // ARGV[1] = tenant_id (for ownership check, empty = skip)
    // ARGV[2] = new_overdraft_limit (empty = no change)
    // ARGV[3] = new_commit_overage_policy (empty = no change)
    // KEYS[1] = budget key
    // ARGV[1] = now_millis
    // ARGV[2] = target_status ("FROZEN" or "ACTIVE")
    private static final String TRANSITION_STATUS_LUA =
        "local key = KEYS[1]\n" +
        "if redis.call('EXISTS', key) == 0 then return {'NOT_FOUND'} end\n" +
        "local status = redis.call('HGET', key, 'status') or 'ACTIVE'\n" +
        "local target = ARGV[2]\n" +
        "if status == 'CLOSED' then return {'BUDGET_CLOSED'} end\n" +
        "if status == target then return {'ALREADY_' .. target} end\n" +
        "if target == 'FROZEN' and status ~= 'ACTIVE' then return {'INVALID_TRANSITION'} end\n" +
        "if target == 'ACTIVE' and status ~= 'FROZEN' then return {'INVALID_TRANSITION'} end\n" +
        "redis.call('HMSET', key, 'status', target, 'updated_at', ARGV[1])\n" +
        "return {'OK'}\n";

    // ARGV[4] = new_metadata_json (empty = no change)
    // ARGV[5] = now_iso
    private static final String UPDATE_BUDGET_LUA =
        "local key = KEYS[1]\n" +
        "if redis.call('EXISTS', key) == 0 then return {'NOT_FOUND'} end\n" +
        "local tid = redis.call('HGET', key, 'tenant_id') or ''\n" +
        "if ARGV[1] ~= '' and tid ~= ARGV[1] then return {'FORBIDDEN'} end\n" +
        "local status = redis.call('HGET', key, 'status') or 'ACTIVE'\n" +
        "if status == 'CLOSED' then return {'BUDGET_CLOSED'} end\n" +
        "if ARGV[2] ~= '' then redis.call('HSET', key, 'overdraft_limit', ARGV[2]) end\n" +
        "if ARGV[3] ~= '' then redis.call('HSET', key, 'commit_overage_policy', ARGV[3]) end\n" +
        "if ARGV[4] ~= '' then redis.call('HSET', key, 'metadata_json', ARGV[4]) end\n" +
        "local debt = tonumber(redis.call('HGET', key, 'debt') or '0')\n" +
        "local overdraft = tonumber(redis.call('HGET', key, 'overdraft_limit') or '0')\n" +
        "local is_over = 'false'\n" +
        "if debt > overdraft then is_over = 'true' end\n" +
        "redis.call('HMSET', key, 'is_over_limit', is_over, 'updated_at', ARGV[5])\n" +
        "return {'OK'}\n";

    public BudgetLedger update(String tenantId, String scope, UnitEnum unit, BudgetUpdateRequest request) {
        scope = normalizeScope(scope);
        LOG.info("Updating budget: tenant_id={} scope={} unit={}", LogSanitizer.safe(tenantId), LogSanitizer.safe(scope), unit);
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + scope + ":" + unit;
            String now = String.valueOf(Instant.now().toEpochMilli());

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(UPDATE_BUDGET_LUA,
                List.of(key),
                List.of(
                    tenantId != null ? tenantId : "",
                    request.getOverdraftLimit() != null ? String.valueOf(request.getOverdraftLimit().getAmount()) : "",
                    request.getCommitOveragePolicy() != null ? request.getCommitOveragePolicy().name() : "",
                    request.getMetadata() != null ? objectMapper.writeValueAsString(request.getMetadata()) : "",
                    now
                ));

            String status = result.get(0);
            if ("NOT_FOUND".equals(status)) {
                throw GovernanceException.budgetNotFound(scope + ":" + unit);
            }
            if ("FORBIDDEN".equals(status)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.FORBIDDEN,
                    "Budget does not belong to tenant", 403);
            }
            if ("BUDGET_CLOSED".equals(status)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.BUDGET_CLOSED,
                    "Cannot update a CLOSED budget", 409);
            }

            // Re-read and return the updated budget
            Map<String, String> hash = jedis.hgetAll(key);
            return hashToBudgetLedger(hash, key);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public BudgetLedger freeze(String scope, UnitEnum unit) {
        return transitionStatus(scope, unit, "FROZEN");
    }

    public BudgetLedger unfreeze(String scope, UnitEnum unit) {
        return transitionStatus(scope, unit, "ACTIVE");
    }

    // Spec v0.1.25.29 CASCADE SEMANTICS: close every ACTIVE|FROZEN budget
    // owned by `tenantId` and return the before-state of each row that was
    // actually mutated. Drains `reserved` back to `remaining` (equivalent to
    // releasing every open reservation against the budget) before flipping
    // status → CLOSED. Stamps `closed_at`. Already-CLOSED budgets are
    // skipped (idempotent — a re-issued tenant-close cascade is a no-op).
    //
    // Runtime-plane reservations that live outside this admin store will see
    // the CLOSED status on their next precondition check and reject per the
    // existing runtime contract.
    //
    // Numeric precision: Redis embeds Lua 5.1, which lacks an integer
    // subtype — amounts are doubles throughout. Values beyond 2^53
    // (~9.0×10^15) lose precision in arithmetic; governance-plane units
    // (cents / tokens / request-counts) do not approach this bound, so the
    // string.format('%.0f', n) serialization is exact over the entire
    // supported value range. Promote to big-integer string arithmetic only
    // if a future unit widens past 2^53.
    private static final String CASCADE_CLOSE_BUDGET_LUA =
        "local key = KEYS[1]\n" +
        "if redis.call('EXISTS', key) == 0 then return {'NOT_FOUND'} end\n" +
        "local status = redis.call('HGET', key, 'status') or 'ACTIVE'\n" +
        "if status == 'CLOSED' then return {'ALREADY_CLOSED'} end\n" +
        "local reserved = tonumber(redis.call('HGET', key, 'reserved') or '0')\n" +
        "local remaining = tonumber(redis.call('HGET', key, 'remaining') or '0')\n" +
        "local new_remaining = remaining + reserved\n" +
        "local function i(n) return string.format('%.0f', n) end\n" +
        "redis.call('HMSET', key,\n" +
        "  'status', 'CLOSED',\n" +
        "  'reserved', '0',\n" +
        "  'remaining', i(new_remaining),\n" +
        "  'closed_at', ARGV[1],\n" +
        "  'updated_at', ARGV[1])\n" +
        "local ledger_id = redis.call('HGET', key, 'ledger_id') or key\n" +
        "local scope = redis.call('HGET', key, 'scope') or ''\n" +
        "local unit = redis.call('HGET', key, 'unit') or ''\n" +
        "local item_id = 'budget:' .. ledger_id\n" +
        "local item_key = 'tenant-close:outbox:item:' .. ARGV[2] .. ':' .. item_id\n" +
        "local item = {itemId=item_id, tenantId=ARGV[2], resourceType='budget',\n" +
        "  resourceId=ledger_id, scope=scope, unit=unit, priorStatus=status,\n" +
        "  releasedReservedAmount=reserved}\n" +
        "redis.call('SET', item_key, cjson.encode(item))\n" +
        "redis.call('SADD', KEYS[2], item_id)\n" +
        "return {'OK', status, i(reserved)}\n";

    /** Outcome of one budget row in a tenant-close cascade. */
    public record CascadeCloseBudgetOutcome(
            String ledgerId,
            String scope,
            UnitEnum unit,
            BudgetStatus priorStatus,
            long releasedReservedAmount) {}

    /**
     * Spec v0.1.25.29 CASCADE SEMANTICS (Rule 1): close every budget owned by
     * {@code tenantId}. Already-CLOSED budgets are skipped so re-issuing the
     * cascade is a no-op. Returns one outcome per budget that was actually
     * transitioned — the caller emits a matching audit + event per outcome.
     *
     * <p>This is the Redis-level primitive; sequencing across budget →
     * webhook → api-key cascades + the final tenant.status flip lives in
     * {@code TenantCloseCascadeService}.
     */
    public CascadeMutationResult<CascadeCloseBudgetOutcome> cascadeClose(String tenantId) {
        var outcomes = CascadeMutationResult.<CascadeCloseBudgetOutcome>builder();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.smembers("budgets:" + tenantId);
            if (keys == null || keys.isEmpty()) return outcomes.build();
            String now = String.valueOf(Instant.now().toEpochMilli());
            for (String key : keys) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> result = (List<String>) jedis.eval(
                        CASCADE_CLOSE_BUDGET_LUA,
                        List.of(key, TenantCloseWorkRepository.outboxKey(tenantId)),
                        List.of(now, tenantId));
                    String status = result.get(0);
                    if (!"OK".equals(status)) continue;
                    Map<String, String> hash = jedis.hgetAll(key);
                    BudgetLedger closed = hashToBudgetLedger(hash, key);
                    long released = 0L;
                    try { released = Long.parseLong(result.get(2)); }
                    catch (NumberFormatException ignored) {}
                    outcomes.succeeded(new CascadeCloseBudgetOutcome(
                        closed.getLedgerId(),
                        closed.getScope(),
                        closed.getUnit(),
                        BudgetStatus.valueOf(result.get(1)),
                        released));
                } catch (Exception e) {
                    outcomes.failed(key, e);
                    LOG.warn("Cascade-close skipped budget: budget_key={} tenant_id={} error={}",
                        LogSanitizer.safe(key), LogSanitizer.safe(tenantId), LogSanitizer.safe(e.getMessage()), e);
                }
            }
        }
        return outcomes.build();
    }

    private BudgetLedger transitionStatus(String scope, UnitEnum unit, String targetStatus) {
        scope = normalizeScope(scope);
        LOG.info("Transitioning budget status: scope={} unit={} target_status={}", LogSanitizer.safe(scope), unit, targetStatus);
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + scope + ":" + unit;
            String now = String.valueOf(Instant.now().toEpochMilli());

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(TRANSITION_STATUS_LUA,
                List.of(key),
                List.of(now, targetStatus));

            String status = result.get(0);
            if ("NOT_FOUND".equals(status)) {
                throw GovernanceException.budgetNotFound(scope + ":" + unit);
            }
            if ("BUDGET_CLOSED".equals(status)) {
                throw GovernanceException.budgetClosed(scope);
            }
            if (status.startsWith("ALREADY_") || "INVALID_TRANSITION".equals(status)) {
                String msg = "FROZEN".equals(targetStatus)
                    ? "Budget is already frozen" : "Budget is not frozen";
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST, msg, 409);
            }

            Map<String, String> hash = jedis.hgetAll(key);
            return hashToBudgetLedger(hash, key);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<BudgetLedger> list(String tenantId, String scopePrefix, UnitEnum unitFilter, BudgetStatus statusFilter, String cursor, int limit) {
        BudgetListFilters filters = new BudgetListFilters(
                scopePrefix, unitFilter, statusFilter, null, null, null, null);
        return list(tenantId, filters, cursor, limit);
    }

    public List<BudgetLedger> list(String tenantId, BudgetListFilters filters, String cursor, int limit) {
        return list(tenantId, filters, cursor, limit, null);
    }

    /**
     * Single-tenant list with optional sort (spec v0.1.25.20). Null SortSpec
     * preserves the pre-v0.1.25.20 cursor-on-raw-key path. When provided,
     * hydrates all budgets for the tenant, applies filters pre-sort (so
     * pagination stays stable under every filter set), sorts via
     * {@link #budgetComparator}, then walks the cursor strictly-after
     * match and takes limit. Cursor remains a bare ledger_id — stable
     * pagination requires the caller to pass the same sortSpec on follow-up
     * pages.
     */
    public List<BudgetLedger> list(String tenantId, BudgetListFilters filters, String cursor, int limit, SortSpec sortSpec) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (sortSpec == null) {
                return collectForTenant(jedis, tenantId, filters, cursor, limit);
            }
            return collectForTenantSorted(jedis, tenantId, filters, cursor, limit, sortSpec);
        }
    }

    private List<BudgetLedger> collectForTenantSorted(Jedis jedis, String tenantId, BudgetListFilters filters, String cursor, int limit, SortSpec sortSpec) {
        Set<String> keys = jedis.smembers("budgets:" + tenantId);
        SortedQueryGuard.requireBounded(keys.size(), "budget");
        List<BudgetLedger> all = new ArrayList<>();
        BudgetListFilters effective = filters != null ? filters : BudgetListFilters.empty();
        for (String key : keys) {
            try {
                Map<String, String> hash = jedis.hgetAll(key);
                if (hash.isEmpty()) {
                    LOG.warn("Budget index points to missing row; cleaning index: budget_key={} tenant_id={} index_key={} sort_field={}",
                        LogSanitizer.safe(key), LogSanitizer.safe(tenantId), LogSanitizer.safe("budgets:" + tenantId), sortSpec.field());
                    jedis.srem("budgets:" + tenantId, key);
                    continue;
                }
                BudgetLedger ledger = hashToBudgetLedger(hash, key);
                if (!effective.matches(ledger)) continue;
                all.add(ledger);
            } catch (Exception e) {
                LOG.warn("Failed to parse budget row: budget_key={} tenant_id={} index_key={} sort_field={}",
                    LogSanitizer.safe(key), LogSanitizer.safe(tenantId), LogSanitizer.safe("budgets:" + tenantId), sortSpec.field(), e);
            }
        }
        all.sort(budgetComparator(sortSpec));
        List<BudgetLedger> result = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        for (BudgetLedger ledger : all) {
            if (!pastCursor) {
                if (cursor.equals(ledger.getLedgerId())) pastCursor = true;
                continue;
            }
            result.add(ledger);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /**
     * Null-safe comparator for the whitelisted sort fields. Secondary sort
     * on ledger_id guarantees a total order so cursor resume is deterministic.
     * Utilization and debt are computed per-ledger at sort time using the
     * same arithmetic as BudgetListFilters so filter + sort are consistent
     * for any given ledger. Unknown fields fall back to ledger_id.
     */
    static Comparator<BudgetLedger> budgetComparator(SortSpec sortSpec) {
        String field = sortSpec.field() != null ? sortSpec.field() : "utilization";
        Comparator<BudgetLedger> primary;
        switch (field) {
            case "tenant_id":
                primary = Comparator.comparing(BudgetLedger::getTenantId, Comparator.nullsLast(String::compareTo));
                break;
            case "scope":
                primary = Comparator.comparing(BudgetLedger::getScope, Comparator.nullsLast(String::compareTo));
                break;
            case "unit":
                primary = Comparator.comparing(
                    b -> b.getUnit() == null ? null : b.getUnit().name(),
                    Comparator.nullsLast(String::compareTo));
                break;
            case "status":
                primary = Comparator.comparing(
                    b -> b.getStatus() == null ? null : b.getStatus().name(),
                    Comparator.nullsLast(String::compareTo));
                break;
            case "commit_overage_policy":
                primary = Comparator.comparing(
                    b -> b.getCommitOveragePolicy() == null ? null : b.getCommitOveragePolicy().name(),
                    Comparator.nullsLast(String::compareTo));
                break;
            case "debt":
                primary = Comparator.comparingLong(BudgetRepository::debtAmount);
                break;
            case "utilization":
            default:
                if (!"utilization".equals(field) && !"ledger_id".equals(field)) {
                    // Unknown field — fall through to ledger_id tie-breaker only.
                    primary = Comparator.comparing(BudgetLedger::getLedgerId, Comparator.nullsLast(String::compareTo));
                    break;
                }
                primary = Comparator.comparingDouble(BudgetRepository::utilization);
                break;
        }
        Comparator<BudgetLedger> withTieBreak = primary.thenComparing(
            BudgetLedger::getLedgerId, Comparator.nullsLast(String::compareTo));
        return sortSpec.isAscending() ? withTieBreak : withTieBreak.reversed();
    }

    private static long debtAmount(BudgetLedger ledger) {
        return ledger.getDebt() != null ? ledger.getDebt().getAmount() : 0L;
    }

    private static double utilization(BudgetLedger ledger) {
        if (ledger.getAllocated() == null) return 0.0;
        long allocated = ledger.getAllocated().getAmount();
        if (allocated == 0L) return 0.0;
        long spent = ledger.getSpent() != null ? ledger.getSpent().getAmount() : 0L;
        return (double) spent / (double) allocated;
    }

    /**
     * Cross-tenant listing introduced in governance spec v0.1.25.18 for
     * AdminKeyAuth callers. Iterates the global `tenants` set in sorted
     * order; for each tenant, walks that tenant's budgets in sorted
     * order. Filters are applied before pagination so cursor traversal
     * is stable under any filter set.
     *
     * Cursor format: "{tenantId}|{ledgerId}". Resumes at the next ledger
     * strictly after the cursor within the cursor's tenant, then
     * continues into subsequent tenants.
     *
     * If the cursor tenant has been deleted between pages, resumes at
     * the first tenant whose id sorts strictly after the cursor tenant,
     * serving that tenant from the start. This avoids stalling
     * pagination (returning empty and implying end-of-data) when the
     * cursor tenant is gone but later tenants still have data.
     */
    public List<BudgetLedger> listAllTenants(BudgetListFilters filters, String cursor, int limit) {
        return listAllTenants(filters, cursor, limit, null);
    }

    /**
     * Cross-tenant listing with optional sort (spec v0.1.25.20). Null SortSpec
     * preserves the v0.1.25.22 per-tenant walk (tenants iterated in id
     * order, skip-forward when cursor tenant deleted). When present,
     * flattens every tenant's budgets, applies filters, sorts globally
     * via {@link #budgetComparator}, then walks the composite
     * "{tenantId}|{ledgerId}" cursor to the strictly-next entry.
     */
    public List<BudgetLedger> listAllTenants(BudgetListFilters filters, String cursor, int limit, SortSpec sortSpec) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (sortSpec == null) {
                return listAllTenantsLegacy(jedis, filters, cursor, limit);
            }
            return listAllTenantsSorted(jedis, filters, cursor, limit, sortSpec);
        }
    }

    private List<BudgetLedger> listAllTenantsLegacy(Jedis jedis, BudgetListFilters filters, String cursor, int limit) {
        String cursorTenantId = null;
        String cursorLedgerId = null;
        if (cursor != null && !cursor.isBlank()) {
            int sep = cursor.indexOf('|');
            if (sep > 0) {
                cursorTenantId = cursor.substring(0, sep);
                cursorLedgerId = cursor.substring(sep + 1);
            } else {
                cursorTenantId = cursor;
            }
        }
        Set<String> tenantIds = jedis.smembers("tenants");
        List<String> sortedTenantIds = new ArrayList<>(tenantIds);
        Collections.sort(sortedTenantIds);
        List<BudgetLedger> collected = new ArrayList<>();
        boolean pastTenantCursor = (cursorTenantId == null);
        for (String tenantId : sortedTenantIds) {
            String innerCursor;
            if (!pastTenantCursor) {
                int cmp = tenantId.compareTo(cursorTenantId);
                if (cmp < 0) continue;
                pastTenantCursor = true;
                // cmp == 0: same tenant as cursor → resume inside using cursorLedgerId.
                // cmp  > 0: cursor tenant was deleted → serve this tenant from start.
                innerCursor = (cmp == 0) ? cursorLedgerId : null;
            } else {
                innerCursor = null;
            }
            int remaining = limit - collected.size();
            if (remaining <= 0) break;
            collected.addAll(collectForTenant(jedis, tenantId, filters, innerCursor, remaining));
            if (collected.size() >= limit) break;
        }
        return collected;
    }

    private List<BudgetLedger> listAllTenantsSorted(Jedis jedis, BudgetListFilters filters, String cursor, int limit, SortSpec sortSpec) {
        Set<String> tenantIds = jedis.smembers("tenants");
        long candidates = 0L;
        for (String tenantId : tenantIds) candidates += jedis.scard("budgets:" + tenantId);
        SortedQueryGuard.requireBounded(candidates, "cross-tenant budget");
        List<BudgetLedger> all = new ArrayList<>();
        BudgetListFilters effective = filters != null ? filters : BudgetListFilters.empty();
        for (String tenantId : tenantIds) {
            Set<String> budgetKeys = jedis.smembers("budgets:" + tenantId);
            for (String key : budgetKeys) {
                try {
                    Map<String, String> hash = jedis.hgetAll(key);
                    if (hash.isEmpty()) continue;
                    BudgetLedger ledger = hashToBudgetLedger(hash, key);
                    if (!effective.matches(ledger)) continue;
                    all.add(ledger);
                } catch (Exception e) {
                    LOG.warn("Failed to parse budget row during cross-tenant sorted list: budget_key={} tenant_id={} sort_field={}",
                        LogSanitizer.safe(key), LogSanitizer.safe(tenantId), sortSpec.field(), e);
                }
            }
        }
        all.sort(budgetComparator(sortSpec));
        List<BudgetLedger> result = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        String cursorTenantId = null;
        String cursorLedgerId = null;
        if (!pastCursor) {
            int sep = cursor.indexOf('|');
            if (sep > 0) {
                cursorTenantId = cursor.substring(0, sep);
                cursorLedgerId = cursor.substring(sep + 1);
            } else {
                cursorLedgerId = cursor;
            }
        }
        for (BudgetLedger ledger : all) {
            if (!pastCursor) {
                boolean match = Objects.equals(ledger.getLedgerId(), cursorLedgerId)
                    && (cursorTenantId == null || Objects.equals(ledger.getTenantId(), cursorTenantId));
                if (match) pastCursor = true;
                continue;
            }
            result.add(ledger);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private List<BudgetLedger> collectForTenant(Jedis jedis, String tenantId, BudgetListFilters filters, String cursor, int limit) {
        Set<String> keys = jedis.smembers("budgets:" + tenantId);
        List<String> sortedKeys = new ArrayList<>(keys);
        Collections.sort(sortedKeys);
        List<BudgetLedger> ledgers = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        BudgetListFilters effective = filters != null ? filters : BudgetListFilters.empty();
        for (String key : sortedKeys) {
            try {
                Map<String, String> hash = jedis.hgetAll(key);
                if (hash.isEmpty()) {
                    LOG.warn("Budget index points to missing row; cleaning index: budget_key={} tenant_id={} index_key={} cursor_present={}",
                        LogSanitizer.safe(key), LogSanitizer.safe(tenantId), LogSanitizer.safe("budgets:" + tenantId), cursor != null && !cursor.isBlank());
                    jedis.srem("budgets:" + tenantId, key);
                    continue;
                }
                BudgetLedger ledger = hashToBudgetLedger(hash, key);
                if (!pastCursor) {
                    if (ledger.getLedgerId().equals(cursor)) pastCursor = true;
                    continue;
                }
                if (!effective.matches(ledger)) continue;
                ledgers.add(ledger);
                if (ledgers.size() >= limit) break;
            } catch (Exception e) {
                LOG.warn("Failed to parse budget row: budget_key={} tenant_id={} index_key={} cursor_present={}",
                    LogSanitizer.safe(key), LogSanitizer.safe(tenantId), LogSanitizer.safe("budgets:" + tenantId), cursor != null && !cursor.isBlank(), e);
            }
        }
        return ledgers;
    }

    public List<BudgetLedger> list(String tenantId) {
        return list(tenantId, null, null, null, null, 1000);
    }

    /**
     * Tenant-scoped bulk-match for {@code POST /v1/admin/budgets/bulk-action}
     * (spec v0.1.25.26). Hydrates up to {@code cap + 1} matching ledgers
     * from {@code budgets:{tenantId}} and applies {@link BudgetListFilters#matches}
     * for zero-drift semantics with listBudgets. The {@code +1} is the
     * "filter is too wide" sentinel — callers check {@code size() > cap}
     * and raise LIMIT_EXCEEDED without hydrating the remainder.
     *
     * <p>No cross-tenant path: the bulk endpoint requires {@code tenant_id}
     * so the walk is constrained to a single tenant's budget index.
     */
    public List<BudgetLedger> matchForBulk(String tenantId, BudgetListFilters filters, int cap) {
        BudgetListFilters effective = filters != null ? filters : BudgetListFilters.empty();
        List<BudgetLedger> matched = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.smembers("budgets:" + tenantId);
            for (String key : keys) {
                try {
                    Map<String, String> hash = jedis.hgetAll(key);
                    if (hash.isEmpty()) {
                        LOG.warn("Budget index points to missing row during bulk match; cleaning index: budget_key={} tenant_id={} index_key={} cap={}",
                            LogSanitizer.safe(key), LogSanitizer.safe(tenantId), LogSanitizer.safe("budgets:" + tenantId), cap);
                        jedis.srem("budgets:" + tenantId, key);
                        continue;
                    }
                    BudgetLedger ledger = hashToBudgetLedger(hash, key);
                    if (!effective.matches(ledger)) continue;
                    matched.add(ledger);
                    if (matched.size() > cap) break;
                } catch (Exception e) {
                    LOG.warn("Failed to parse budget row during bulk match: budget_key={} tenant_id={} index_key={} cap={}",
                        LogSanitizer.safe(key), LogSanitizer.safe(tenantId), LogSanitizer.safe("budgets:" + tenantId), cap, e);
                }
            }
        }
        return matched;
    }

    /** Exact match count used only for an over-limit bulk rejection response. */
    public int countForBulk(String tenantId, BudgetListFilters filters) {
        BudgetListFilters effective = filters != null ? filters : BudgetListFilters.empty();
        int count = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            for (String key : jedis.smembers("budgets:" + tenantId)) {
                try {
                    Map<String, String> hash = jedis.hgetAll(key);
                    if (hash.isEmpty()) continue;
                    if (effective.matches(hashToBudgetLedger(hash, key))) count++;
                } catch (Exception e) {
                    LOG.warn("Failed to parse budget row during exact bulk count: budget_key={} tenant_id={}",
                        LogSanitizer.safe(key), LogSanitizer.safe(tenantId), e);
                }
            }
        }
        return count;
    }

    public BudgetLedger getByExactScope(String scope, UnitEnum unit) {
        String normalizedScope = normalizeScope(scope);
        String key = "budget:" + normalizedScope + ":" + unit;
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> hash = jedis.hgetAll(key);
            if (hash.isEmpty()) throw GovernanceException.budgetNotFound(scope);
            return hashToBudgetLedger(hash, key);
        }
    }

    public BudgetFundingResponse fund(String tenantId, String scope, UnitEnum unit, BudgetFundingRequest request) {
        scope = normalizeScope(scope);
        LOG.info("Funding budget: tenant_id={} scope={} unit={} operation={} idempotency_key_present={}",
            LogSanitizer.safe(tenantId), LogSanitizer.safe(scope), unit, request.getOperation(),
            request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank());
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

            // Spent override (ARGV[7]) — only meaningful for RESET_SPENT. Empty string
            // means "use default" (spent = 0 for RESET_SPENT; not used at all for
            // other operations). Passing the raw string (not the request field's
            // internal unit) keeps the Lua side unit-agnostic; the controller has
            // already enforced that request.spent.unit matches request.amount.unit.
            String spentOverride = "";
            if (request.getOperation() == FundingOperation.RESET_SPENT
                && request.getSpent() != null) {
                spentOverride = String.valueOf(request.getSpent().getAmount());
            }

            // Atomic fund via Lua — idempotency check + read + compute + write + cache in one step
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(FUND_LUA,
                List.of(key),
                List.of(request.getOperation().name(), String.valueOf(changeAmount), now,
                         tenantId != null ? tenantId : "", idempotencyKey, payloadHash,
                         spentOverride));

            String status = result.get(0);
            if ("IDEMPOTENCY_MISMATCH".equals(status)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.IDEMPOTENCY_MISMATCH,
                    "Idempotency key reused with different payload", 409);
            }
            if ("IDEMPOTENT_HIT".equals(status)) {
                // Reconstruct response from cached pipe-delimited values (v2 format)
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
            if ("INVALID_REQUEST".equals(status)) {
                // Raised today only by RESET_SPENT with negative spent override.
                String detail = result.size() > 1 ? result.get(1) : "Invalid request";
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    detail, 400);
            }

            long prevAllocated = Long.parseLong(result.get(1));
            long newAllocated = Long.parseLong(result.get(2));
            long prevRemaining = Long.parseLong(result.get(3));
            long newRemainingVal = Long.parseLong(result.get(4));
            long prevDebtVal = Long.parseLong(result.get(5));
            long newDebtVal = Long.parseLong(result.get(6));
            // indices 7 = is_over (string); 8 = prev_spent; 9 = new_spent (v0.1.25.17+)
            long prevSpentVal = result.size() > 8 ? Long.parseLong(result.get(8)) : 0L;
            long newSpentVal  = result.size() > 9 ? Long.parseLong(result.get(9)) : 0L;

            BudgetFundingResponse response = BudgetFundingResponse.builder()
                .operation(request.getOperation())
                .previousAllocated(new Amount(unit, prevAllocated))
                .newAllocated(new Amount(unit, newAllocated))
                .previousRemaining(new Amount(unit, prevRemaining))
                .newRemaining(new Amount(unit, newRemainingVal))
                .previousDebt(new Amount(unit, prevDebtVal))
                .newDebt(new Amount(unit, newDebtVal))
                .previousSpent(new Amount(unit, prevSpentVal))
                .newSpent(new Amount(unit, newSpentVal))
                .timestamp(Instant.now())
                .build();

            LOG.info("Funded budget atomically: tenant_id={} scope={} unit={} operation={} idempotency_key_present={}",
                LogSanitizer.safe(tenantId), LogSanitizer.safe(scope), unit, request.getOperation(), !idempotencyKey.isEmpty());
            return response;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BudgetFundingResponse parseCachedFundResponse(String cached, UnitEnum unit, FundingOperation operation) {
        // v2 cache format (spec v0.1.25.17+): 9 pipe-delimited fields
        //   prev_allocated|allocated|prev_remaining|remaining|prev_debt|debt|is_over|prev_spent|spent
        // We read by position and tolerate older cache format if somehow a 7-field
        // entry survived — the v2 prefix on the cache key should make this
        // impossible in practice, but the defensive null-fill is cheap.
        String[] parts = cached.split("\\|");
        BudgetFundingResponse.BudgetFundingResponseBuilder b = BudgetFundingResponse.builder()
            .operation(operation)
            .previousAllocated(new Amount(unit, Long.parseLong(parts[0])))
            .newAllocated(new Amount(unit, Long.parseLong(parts[1])))
            .previousRemaining(new Amount(unit, Long.parseLong(parts[2])))
            .newRemaining(new Amount(unit, Long.parseLong(parts[3])))
            .previousDebt(new Amount(unit, Long.parseLong(parts[4])))
            .newDebt(new Amount(unit, Long.parseLong(parts[5])))
            .timestamp(Instant.now());
        if (parts.length >= 9) {
            b.previousSpent(new Amount(unit, Long.parseLong(parts[7])));
            b.newSpent(new Amount(unit, Long.parseLong(parts[8])));
        }
        return b.build();
    }

    private String computePayloadHash(Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            LOG.warn("Failed to compute budget funding payload hash; idempotency mismatch detection skipped: request_type={}",
                request.getClass().getSimpleName(), e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return null; }
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
            .metadata(hash.containsKey("metadata_json") ? parseMetadata(hash.get("metadata_json")) : null)
            .build();
    }
}
