package io.runcycles.admin.data.repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.policy.Policy;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.policy.PolicyStatus;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.policy.PolicyUpdateRequest;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class PolicyRepository {
    private static final Logger LOG = LoggerFactory.getLogger(PolicyRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    // Lua script for atomic policy creation: SET + SADD in one call (consistent with budget/tenant creation)
    private static final String CREATE_POLICY_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('SADD', KEYS[2], ARGV[2])\n" +
        "return 1\n";

    public Policy create(String tenantId, PolicyCreateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
            String policyId = "pol_" + UUID.randomUUID().toString().substring(0, 16);
            Policy policy = Policy.builder()
                .policyId(policyId)
                .tenantId(tenantId)
                .name(request.getName())
                .description(request.getDescription())
                .scopePattern(request.getScopePattern())
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .caps(request.getCaps())
                .commitOveragePolicy(request.getCommitOveragePolicy())
                .reservationTtlOverride(request.getReservationTtlOverride())
                .rateLimits(request.getRateLimits())
                .status(PolicyStatus.ACTIVE)
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveUntil(request.getEffectiveUntil())
                .createdAt(Instant.now())
                .build();
            String json = objectMapper.writeValueAsString(policy);
            // Atomic create: SET policy + SADD index in one Lua call
            jedis.eval(CREATE_POLICY_LUA,
                List.of("policy:" + policyId, "policies:" + tenantId),
                List.of(json, policyId));
            return policy;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    // Lua script for atomic policy update: read-modify-write in one step.
    // KEYS[1] = policy key
    // ARGV[1] = tenant_id (ownership check)
    // ARGV[2] = updated fields JSON (merged into existing policy)
    // ARGV[3] = now_iso
    private static final String UPDATE_POLICY_LUA =
        "local json = redis.call('GET', KEYS[1])\n" +
        "if not json then return {'NOT_FOUND'} end\n" +
        "local policy = cjson.decode(json)\n" +
        "if ARGV[1] ~= '' and policy['tenant_id'] ~= ARGV[1] then return {'FORBIDDEN'} end\n" +
        "local updates = cjson.decode(ARGV[2])\n" +
        "for k, v in pairs(updates) do policy[k] = v end\n" +
        "policy['updated_at'] = ARGV[3]\n" +
        "redis.call('SET', KEYS[1], cjson.encode(policy))\n" +
        "return {'OK', cjson.encode(policy)}\n";

    public Policy update(String tenantId, String policyId, PolicyUpdateRequest request) {
        LOG.info("Updating policy: {}", policyId);
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "policy:" + policyId;

            // Build a map of only non-null fields to merge
            Map<String, Object> updates = new LinkedHashMap<>();
            if (request.getName() != null) updates.put("name", request.getName());
            if (request.getDescription() != null) updates.put("description", request.getDescription());
            if (request.getPriority() != null) updates.put("priority", request.getPriority());
            if (request.getCaps() != null) updates.put("caps", request.getCaps());
            if (request.getCommitOveragePolicy() != null) updates.put("commit_overage_policy", request.getCommitOveragePolicy().name());
            if (request.getReservationTtlOverride() != null) updates.put("reservation_ttl_override", request.getReservationTtlOverride());
            if (request.getRateLimits() != null) updates.put("rate_limits", request.getRateLimits());
            if (request.getEffectiveFrom() != null) updates.put("effective_from", request.getEffectiveFrom().toString());
            if (request.getEffectiveUntil() != null) updates.put("effective_until", request.getEffectiveUntil().toString());
            if (request.getStatus() != null) updates.put("status", request.getStatus().name());

            String updatesJson = objectMapper.writeValueAsString(updates);
            String nowIso = Instant.now().toString();

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(UPDATE_POLICY_LUA,
                List.of(key),
                List.of(tenantId != null ? tenantId : "", updatesJson, nowIso));

            String resultStatus = result.get(0);
            if ("NOT_FOUND".equals(resultStatus)) {
                throw GovernanceException.policyNotFound(policyId);
            }
            if ("FORBIDDEN".equals(resultStatus)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.FORBIDDEN,
                    "Policy does not belong to tenant", 403);
            }

            return objectMapper.readValue(result.get(1), Policy.class);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<Policy> list(String tenantId, String scopePattern, PolicyStatus status, String cursor, int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers("policies:" + tenantId);
            List<String> sortedIds = new ArrayList<>(ids);
            Collections.sort(sortedIds);
            List<Policy> policies = new ArrayList<>();
            boolean pastCursor = (cursor == null || cursor.isBlank());
            for (String id : sortedIds) {
                if (!pastCursor) {
                    if (id.equals(cursor)) pastCursor = true;
                    continue;
                }
                try {
                    String data = jedis.get("policy:" + id);
                    if (data == null) {
                        LOG.warn("Policy data missing for id: {}", id);
                        continue;
                    }
                    Policy p = objectMapper.readValue(data, Policy.class);
                    if (status != null && p.getStatus() != status) continue;
                    if (scopePattern != null && !scopePattern.equals(p.getScopePattern())) continue;
                    policies.add(p);
                    if (policies.size() >= limit) break;
                } catch (Exception e) {
                    LOG.warn("Failed to parse policy: {}", id, e);
                }
            }
            return policies;
        }
    }
}
