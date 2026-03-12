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

    // Lua script for atomic tenant update: reads, validates status transition, and writes in one step.
    // Prevents lost-update race conditions from concurrent read-modify-write cycles.
    // KEYS[1] = tenant key
    // ARGV[1] = new_name (empty string = no change)
    // ARGV[2] = new_status (empty string = no change)
    // ARGV[3] = new_metadata_json (empty string = no change)
    // ARGV[4] = now_ms (epoch millis for timestamps)
    // Returns: {'OK', updated_json} on success, or {'ERROR', error_message} on failure
    private static final String UPDATE_TENANT_LUA =
        "local json = redis.call('GET', KEYS[1])\n" +
        "if not json then return {'NOT_FOUND'} end\n" +
        "local tenant = cjson.decode(json)\n" +
        "local new_name = ARGV[1]\n" +
        "local new_status = ARGV[2]\n" +
        "local new_metadata = ARGV[3]\n" +
        "local now_ms = ARGV[4]\n" +
        // Apply name change
        "if new_name ~= '' then tenant['name'] = new_name end\n" +
        // Validate and apply status transition
        "if new_status ~= '' then\n" +
        "  local old_status = tenant['status'] or 'ACTIVE'\n" +
        "  if old_status == 'CLOSED' then return {'INVALID_TRANSITION', 'Cannot transition from CLOSED'} end\n" +
        "  if old_status == 'ACTIVE' and new_status ~= 'SUSPENDED' and new_status ~= 'CLOSED' and new_status ~= 'ACTIVE' then\n" +
        "    return {'INVALID_TRANSITION', 'Invalid status transition: ' .. old_status .. ' -> ' .. new_status}\n" +
        "  end\n" +
        "  if old_status == 'SUSPENDED' and new_status ~= 'ACTIVE' and new_status ~= 'CLOSED' and new_status ~= 'SUSPENDED' then\n" +
        "    return {'INVALID_TRANSITION', 'Invalid status transition: ' .. old_status .. ' -> ' .. new_status}\n" +
        "  end\n" +
        "  tenant['status'] = new_status\n" +
        "  if new_status == 'SUSPENDED' then tenant['suspendedAt'] = now_ms end\n" +
        "  if new_status == 'CLOSED' then tenant['closedAt'] = now_ms end\n" +
        "end\n" +
        // Apply metadata change
        "if new_metadata ~= '' then tenant['metadata'] = cjson.decode(new_metadata) end\n" +
        "tenant['updatedAt'] = now_ms\n" +
        "local updated = cjson.encode(tenant)\n" +
        "redis.call('SET', KEYS[1], updated)\n" +
        "return {'OK', updated}\n";

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
            String key = "tenant:" + tenantId;
            String nowMs = String.valueOf(Instant.now().toEpochMilli());

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(UPDATE_TENANT_LUA,
                List.of(key),
                List.of(
                    request.getName() != null ? request.getName() : "",
                    request.getStatus() != null ? request.getStatus().name() : "",
                    request.getMetadata() != null ? objectMapper.writeValueAsString(request.getMetadata()) : "",
                    nowMs
                ));

            String status = result.get(0);
            if ("NOT_FOUND".equals(status)) {
                throw GovernanceException.tenantNotFound(tenantId);
            }
            if ("INVALID_TRANSITION".equals(status)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    result.get(1), 400);
            }

            // result.get(1) is the updated tenant JSON
            return objectMapper.readValue(result.get(1), Tenant.class);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
