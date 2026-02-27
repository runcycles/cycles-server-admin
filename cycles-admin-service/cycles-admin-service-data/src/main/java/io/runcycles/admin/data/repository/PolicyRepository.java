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
    public Policy create(PolicyCreateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
            String policyId = "pol_" + UUID.randomUUID().toString().substring(0, 16);
            Policy policy = Policy.builder()
                .policyId(policyId)
                .name(request.getName())
                .description(request.getDescription())
                .scopePattern(request.getScopePattern())
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .caps(request.getCaps())
                .commitOveragePolicy(request.getCommitOveragePolicy())
                .status(PolicyStatus.ACTIVE)
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveUntil(request.getEffectiveUntil())
                .createdAt(Instant.now())
                .build();
            jedis.set("policy:" + policyId, objectMapper.writeValueAsString(policy));
            jedis.sadd("policies", policyId);
            return policy;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public List<Policy> list() {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers("policies");
            List<Policy> policies = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("policy:" + id);
                    policies.add(objectMapper.readValue(data, Policy.class));
                } catch (Exception e) {
                    LOG.warn("Failed to parse policy: {}", id);
                }
            }
            return policies;
        }
    }
}
