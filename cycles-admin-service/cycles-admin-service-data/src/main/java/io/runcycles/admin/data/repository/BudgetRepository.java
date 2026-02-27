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
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
import java.util.*;

/** Budget Governance v0.1.23 - Fixed to use Redis HASH */
@Repository
public class BudgetRepository {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;
    
    public BudgetLedger create(BudgetCreateRequest request) {
        LOG.info("Creating budget: scope={}, unit={}", request.getScope(), request.getUnit());
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + request.getScope() + ":" + request.getUnit();
            if (jedis.exists(key)) {
                throw GovernanceException.duplicateResource("Budget", request.getScope());
            }
            
            BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId(UUID.randomUUID().toString())
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
            
            // ✅ FIXED: Store as Redis HASH using HMSET (not JSON string)
            Map<String, String> budgetHash = new HashMap<>();
            budgetHash.put("ledger_id", ledger.getLedgerId());
            budgetHash.put("scope", ledger.getScope());
            budgetHash.put("unit", ledger.getUnit().name());
            budgetHash.put("allocated", String.valueOf(ledger.getAllocated().getAmount()));
            budgetHash.put("remaining", String.valueOf(ledger.getRemaining().getAmount()));
            budgetHash.put("reserved", String.valueOf(ledger.getReserved().getAmount()));
            budgetHash.put("spent", String.valueOf(ledger.getSpent().getAmount()));
            budgetHash.put("debt", String.valueOf(ledger.getDebt().getAmount()));
            budgetHash.put("overdraft_limit", String.valueOf(ledger.getOverdraftLimit().getAmount()));
            budgetHash.put("is_over_limit", String.valueOf(ledger.getIsOverLimit()));
            budgetHash.put("status", ledger.getStatus().name());
            budgetHash.put("created_at", String.valueOf(ledger.getCreatedAt().toEpochMilli()));
            
            if (ledger.getCommitOveragePolicy() != null) {
                budgetHash.put("commit_overage_policy", ledger.getCommitOveragePolicy().name());
            }
            if (ledger.getRolloverPolicy() != null) {
                budgetHash.put("rollover_policy", ledger.getRolloverPolicy().name());
            }
            if (ledger.getPeriodStart() != null) {
                budgetHash.put("period_start", String.valueOf(ledger.getPeriodStart().toEpochMilli()));
            }
            if (ledger.getPeriodEnd() != null) {
                budgetHash.put("period_end", String.valueOf(ledger.getPeriodEnd().toEpochMilli()));
            }
            
            jedis.hset(key, budgetHash);
            LOG.info("Created budget as HASH: {}", key);
            
            return ledger;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public List<BudgetLedger> list(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<BudgetLedger> ledgers = new ArrayList<>();
            ScanParams params = new ScanParams().match("budget:*").count(100);
            String cursor = "0";
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                for (String key : result.getResult()) {
                    try {
                        // ✅ FIXED: Read from Redis HASH using HGETALL
                        String keyType = jedis.type(key);
                        if (!"hash".equals(keyType)) {
                            LOG.warn("Expected hash type but found '{}' for key: {}", keyType, key);
                            continue;
                        }
                        
                        Map<String, String> hash = jedis.hgetAll(key);
                        if (hash.isEmpty()) continue;
                        
                        BudgetLedger ledger = hashToBudgetLedger(hash, key);
                        LOG.info("Ledger built:key={}, ledger={}",key,ledger);
                        if (tenantId == null || ledger.getScope().contains(tenantId)) {
                            ledgers.add(ledger);
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to parse budget: {}", key, e);
                    }
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
            return ledgers;
        }
    }
    
    public BudgetFundingResponse fund(String scope, UnitEnum unit, BudgetFundingRequest request) {
        LOG.info("Funding budget: scope={}, unit={}, op={}", scope, unit, request.getOperation());
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "budget:" + scope + ":" + unit;
            
            // ✅ FIXED: Read from Redis HASH
            String keyType = jedis.type(key);
            if ("none".equals(keyType)) {
                throw GovernanceException.budgetNotFound(scope);
            }
            if (!"hash".equals(keyType)) {
                throw new RuntimeException("Budget key is not a hash: " + key + " (type: " + keyType + ")");
            }
            
            Map<String, String> hash = jedis.hgetAll(key);
            if (hash.isEmpty()) {
                throw GovernanceException.budgetNotFound(scope);
            }
            
            BudgetLedger ledger = hashToBudgetLedger(hash, key);
            
            Amount prevAllocated = ledger.getAllocated();
            Amount prevRemaining = ledger.getRemaining();
            Amount prevDebt = ledger.getDebt();
            long changeAmount = request.getAmount().getAmount();
            
            switch (request.getOperation()) {
                case CREDIT:
                    ledger.setAllocated(new Amount(unit, ledger.getAllocated().getAmount() + changeAmount));
                    ledger.setRemaining(new Amount(unit, ledger.getRemaining().getAmount() + changeAmount));
                    break;
                case DEBIT:
                    if (ledger.getRemaining().getAmount() < changeAmount) {
                        throw new RuntimeException("Insufficient funds");
                    }
                    ledger.setAllocated(new Amount(unit, ledger.getAllocated().getAmount() - changeAmount));
                    ledger.setRemaining(new Amount(unit, ledger.getRemaining().getAmount() - changeAmount));
                    break;
                case RESET:
                    ledger.setAllocated(request.getAmount());
                    long newRemaining = changeAmount - ledger.getReserved().getAmount() - ledger.getSpent().getAmount() - ledger.getDebt().getAmount();
                    ledger.setRemaining(new Amount(unit, newRemaining));
                    break;
                case REPAY_DEBT:
                    long debt = ledger.getDebt().getAmount();
                    long repayment = Math.min(debt, changeAmount);
                    ledger.setDebt(new Amount(unit, debt - repayment));
                    if (repayment < changeAmount) {
                        ledger.setRemaining(new Amount(unit, ledger.getRemaining().getAmount() + (changeAmount - repayment)));
                    }
                    // Check if we're no longer over-limit after debt repayment
                    if (ledger.getDebt().getAmount() <= ledger.getOverdraftLimit().getAmount()) {
                        ledger.setIsOverLimit(false);
                    }
                    break;
            }
            
            ledger.setUpdatedAt(Instant.now());
            
            // ✅ FIXED: Update Redis HASH using HMSET
            Map<String, String> updates = new HashMap<>();
            updates.put("allocated", String.valueOf(ledger.getAllocated().getAmount()));
            updates.put("remaining", String.valueOf(ledger.getRemaining().getAmount()));
            updates.put("spent", String.valueOf(ledger.getSpent().getAmount()));
            updates.put("debt", String.valueOf(ledger.getDebt().getAmount()));
            updates.put("is_over_limit", String.valueOf(ledger.getIsOverLimit()));
            updates.put("updated_at", String.valueOf(ledger.getUpdatedAt().toEpochMilli()));
            
            jedis.hset(key, updates);
            LOG.info("Updated budget HASH: {}", key);
            
            return BudgetFundingResponse.builder()
                .operation(request.getOperation().name())
                .previousAllocated(prevAllocated)
                .newAllocated(ledger.getAllocated())
                .previousRemaining(prevRemaining)
                .newRemaining(ledger.getRemaining())
                .previousDebt(prevDebt)
                .newDebt(ledger.getDebt())
                .timestamp(Instant.now())
                .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Helper method to convert Redis HASH to BudgetLedger object
     */
    private BudgetLedger hashToBudgetLedger(Map<String, String> hash, String key) {
        String[] keyParts = key.split(":");
        //String scope = keyParts.length > 1 ? keyParts[1] : "";
        //Interesting why AI decided to extract stuff from key and use them instead of taking from internal data model
        String scope =hash.get("scope");
        String unitStr = keyParts.length > 2 ? keyParts[2] : "";
        
        UnitEnum unit = UnitEnum.valueOf(hash.get("unit"));
        
        return BudgetLedger.builder()
            .ledgerId(hash.get("ledger_id"))
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
