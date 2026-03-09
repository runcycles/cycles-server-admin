package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
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
    // Lua script for atomic tenant creation: returns existing JSON if exists, otherwise creates
    private static final String CREATE_TENANT_LUA =
        "local existing = redis.call('GET', KEYS[1])\n" +
        "if existing then return existing end\n" +
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('SADD', KEYS[2], ARGV[2])\n" +
        "return nil\n";

    public record TenantCreateResult(Tenant tenant, boolean created) {}

    public TenantCreateResult create(TenantCreateRequest request) {
        LOG.info("Creating tenant: {}", request.getTenantId());
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "tenant:" + request.getTenantId();
            Tenant tenant = Tenant.builder()
                .tenantId(request.getTenantId())
                .name(request.getName())
                .status(TenantStatus.ACTIVE)
                .parentTenantId(request.getParentTenantId())
                .defaultCommitOveragePolicy(CommitOveragePolicy.REJECT)
                .defaultReservationTtlMs(60000L)
                .maxReservationTtlMs(3600000L)
                .maxReservationExtensions(10)
                .metadata(request.getMetadata())
                .createdAt(Instant.now())
                .build();
            String json = objectMapper.writeValueAsString(tenant);
            Object result = jedis.eval(CREATE_TENANT_LUA,
                List.of(key, "tenants"),
                List.of(json, request.getTenantId()));
            if (result != null) {
                // Tenant already exists — return it (idempotent 200)
                Tenant existing = objectMapper.readValue((String) result, Tenant.class);
                return new TenantCreateResult(existing, false);
            }
            return new TenantCreateResult(tenant, true);
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
    public List<Tenant> list(TenantStatus status, String parentTenantId, String cursor, int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers("tenants");
            List<String> sortedIds = new ArrayList<>(ids);
            Collections.sort(sortedIds);
            List<Tenant> tenants = new ArrayList<>();
            boolean pastCursor = (cursor == null || cursor.isBlank());
            for (String id : sortedIds) {
                if (!pastCursor) {
                    if (id.equals(cursor)) pastCursor = true;
                    continue;
                }
                try {
                    String data = jedis.get("tenant:" + id);
                    if (data == null) {
                        LOG.warn("Tenant data missing for id: {}", id);
                        continue;
                    }
                    Tenant t = objectMapper.readValue(data, Tenant.class);
                    if (status != null && t.getStatus() != status) continue;
                    if (parentTenantId != null && !parentTenantId.equals(t.getParentTenantId())) continue;
                    tenants.add(t);
                    if (tenants.size() >= limit) break;
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
            if (request.getStatus() != null) {
                TenantStatus oldStatus = tenant.getStatus();
                TenantStatus newStatus = request.getStatus();
                // Validate status transitions per spec
                if (oldStatus == TenantStatus.CLOSED) {
                    throw new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                        "Cannot transition from CLOSED", 400);
                }
                if (oldStatus == TenantStatus.ACTIVE && newStatus != TenantStatus.SUSPENDED && newStatus != TenantStatus.CLOSED) {
                    if (newStatus != TenantStatus.ACTIVE) {
                        throw new GovernanceException(
                            io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                            "Invalid status transition: " + oldStatus + " -> " + newStatus, 400);
                    }
                }
                if (oldStatus == TenantStatus.SUSPENDED && newStatus != TenantStatus.ACTIVE && newStatus != TenantStatus.CLOSED) {
                    if (newStatus != TenantStatus.SUSPENDED) {
                        throw new GovernanceException(
                            io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                            "Invalid status transition: " + oldStatus + " -> " + newStatus, 400);
                    }
                }
                tenant.setStatus(newStatus);
                Instant now = Instant.now();
                if (newStatus == TenantStatus.SUSPENDED) {
                    tenant.setSuspendedAt(now);
                } else if (newStatus == TenantStatus.CLOSED) {
                    tenant.setClosedAt(now);
                }
            }
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
