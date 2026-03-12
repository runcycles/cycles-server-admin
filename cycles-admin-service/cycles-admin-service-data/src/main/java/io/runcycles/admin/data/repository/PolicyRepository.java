package io.runcycles.admin.data.repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.policy.Policy;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.policy.PolicyStatus;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
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
