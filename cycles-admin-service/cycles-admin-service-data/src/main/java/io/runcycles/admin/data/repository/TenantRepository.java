package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantCreateRequest;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.tenant.TenantUpdateRequest;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class TenantRepository {
    private static final Logger LOG = LoggerFactory.getLogger(TenantRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;
    // Lua script for atomic tenant creation: SET NX + SADD in one call
    private static final String CREATE_TENANT_LUA =
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end\n" +
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('SADD', KEYS[2], ARGV[2])\n" +
        "return 1\n";
    public Tenant create(TenantCreateRequest request) {
        LOG.info("Creating tenant: {}", request.getTenantId());
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "tenant:" + request.getTenantId();
            Tenant tenant = Tenant.builder()
                .tenantId(request.getTenantId())
                .name(request.getName())
                .status(TenantStatus.ACTIVE)
                .parentTenantId(request.getParentTenantId())
                .defaultReservationTtlMs(60000L)
                .maxReservationTtlMs(3600000L)
                .maxReservationExtensions(10)
                .metadata(request.getMetadata())
                .createdAt(Instant.now())
                .build();
            // Atomic create: EXISTS + SET + SADD in one Lua call
            String json = objectMapper.writeValueAsString(tenant);
            Object result = jedis.eval(CREATE_TENANT_LUA,
                List.of(key, "tenants"),
                List.of(json, request.getTenantId()));
            if (Long.valueOf(0).equals(result)) {
                throw GovernanceException.duplicateResource("Tenant", request.getTenantId());
            }
            return tenant;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tenant", e);
        }
    }
    public Tenant get(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("tenant:" + tenantId);
            if (data == null) throw GovernanceException.tenantNotFound(tenantId);
            return objectMapper.readValue(data, Tenant.class);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public List<Tenant> list(TenantStatus status, int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers("tenants");
            List<Tenant> tenants = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("tenant:" + id);
                    if (data == null) {
                        LOG.warn("Tenant data missing for id: {}", id);
                        continue;
                    }
                    Tenant t = objectMapper.readValue(data, Tenant.class);
                    if (status == null || t.getStatus() == status) {
                        tenants.add(t);
                        if (tenants.size() >= limit) break;
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to load tenant: {}", id, e);
                }
            }
            return tenants;
        }
    }
    public Tenant update(String tenantId, TenantUpdateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
            Tenant tenant = get(tenantId);
            if (request.getName() != null) tenant.setName(request.getName());
            if (request.getStatus() != null) tenant.setStatus(request.getStatus());
            if (request.getMetadata() != null) tenant.setMetadata(request.getMetadata());
            tenant.setUpdatedAt(Instant.now());
            jedis.set("tenant:" + tenantId, objectMapper.writeValueAsString(tenant));
            return tenant;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
