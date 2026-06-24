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

    // Lua script for atomic policy creation: validates tenant exists and is ACTIVE,
    // then SET + SADD in one call.
    // KEYS[1] = policy key, KEYS[2] = tenant policy index, KEYS[3] = tenant key
    // ARGV[1] = policy JSON, ARGV[2] = policyId
    private static final String CREATE_POLICY_LUA =
        "local tenant_json = redis.call('GET', KEYS[3])\n" +
        "if not tenant_json then return -1 end\n" +
        "local tenant = cjson.decode(tenant_json)\n" +
        "local ts = tenant['status'] or 'ACTIVE'\n" +
        "if ts ~= 'ACTIVE' then return -2 end\n" +
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
            // Atomic create: tenant check + SET policy + SADD index in one Lua call
            String tenantKey = "tenant:" + tenantId;
            Object result = jedis.eval(CREATE_POLICY_LUA,
                List.of("policy:" + policyId, "policies:" + tenantId, tenantKey),
                List.of(json, policyId));
            long resultCode = (Long) result;
            if (resultCode == -1) {
                throw GovernanceException.tenantNotFound(tenantId);
            }
            if (resultCode == -2) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Tenant is not ACTIVE: " + tenantId, 400);
            }
            return policy;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    // NOTE: policy update was previously an atomic Lua script that round-tripped
    // the full Policy JSON through cjson.decode/encode. That tripped the same
    // Redis cjson empty-array bug fixed for ApiKey.revoke() — Caps.toolAllowlist
    // and Caps.toolDenylist (both List<String>) were rewritten as {} whenever
    // they were empty at write time, after which Jackson could not deserialize
    // the policy back, and PolicyRepository.list() would silently drop it via
    // the catch-all WARN. See the matching hazard note in ApiKeyRepository.
    //
    // The update() path now follows the same Jackson-in-Java pattern as
    // ApiKeyRepository.update() / revoke(). Last-writer-wins race semantics are
    // preserved — the previous Lua "atomicity" was already a nominal guarantee
    // because every other admin write path is Jackson-in-Java with no CAS.

    public Policy update(String tenantId, String policyId, PolicyUpdateRequest request) {
        LOG.info("Updating policy: policy_id={} tenant_id={}", policyId, tenantId);
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "policy:" + policyId;
            String json = jedis.get(key);
            if (json == null) {
                throw GovernanceException.policyNotFound(policyId);
            }
            Policy policy = objectMapper.readValue(json, Policy.class);
            if (tenantId != null && !tenantId.isEmpty()
                    && !tenantId.equals(policy.getTenantId())) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.FORBIDDEN,
                    "Policy does not belong to tenant", 403);
            }
            // Apply partial updates — only non-null fields.
            if (request.getName() != null) policy.setName(request.getName());
            if (request.getDescription() != null) policy.setDescription(request.getDescription());
            if (request.getPriority() != null) policy.setPriority(request.getPriority());
            if (request.getCaps() != null) policy.setCaps(request.getCaps());
            if (request.getCommitOveragePolicy() != null) policy.setCommitOveragePolicy(request.getCommitOveragePolicy());
            if (request.getReservationTtlOverride() != null) policy.setReservationTtlOverride(request.getReservationTtlOverride());
            if (request.getRateLimits() != null) policy.setRateLimits(request.getRateLimits());
            if (request.getEffectiveFrom() != null) policy.setEffectiveFrom(request.getEffectiveFrom());
            if (request.getEffectiveUntil() != null) policy.setEffectiveUntil(request.getEffectiveUntil());
            if (request.getStatus() != null) policy.setStatus(request.getStatus());
            policy.setUpdatedAt(Instant.now());
            // Write back with Jackson — clean serialization, no cjson issues.
            jedis.set(key, objectMapper.writeValueAsString(policy));
            return policy;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getScopePattern(String policyId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("policy:" + policyId);
            if (data == null) {
                throw GovernanceException.policyNotFound(policyId);
            }
            Policy policy = objectMapper.readValue(data, Policy.class);
            return policy.getScopePattern();
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
                        LOG.warn("Policy index points to missing row: policy_id={} tenant_id={} index_key={} status_filter={} scope_pattern_filter={}",
                            id, tenantId, "policies:" + tenantId, status, scopePattern);
                        continue;
                    }
                    Policy p = objectMapper.readValue(data, Policy.class);
                    if (status != null && p.getStatus() != status) continue;
                    if (scopePattern != null && !scopePattern.equals(p.getScopePattern())) continue;
                    policies.add(p);
                    if (policies.size() >= limit) break;
                } catch (Exception e) {
                    LOG.warn("Failed to parse policy row: policy_id={} tenant_id={} index_key={} status_filter={} scope_pattern_filter={}",
                        id, tenantId, "policies:" + tenantId, status, scopePattern, e);
                }
            }
            return policies;
        }
    }
}
