package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortSpec;
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
    // Lua script for atomic tenant creation with conflict detection.
    // Returns {'CREATED'} if new, {'EXISTS', json} if idempotent replay (same name),
    // or {'CONFLICT', json} if tenant_id exists with a different name (spec: 409).
    // KEYS[1] = tenant key, KEYS[2] = tenants index set
    // ARGV[1] = new tenant JSON, ARGV[2] = tenant_id, ARGV[3] = request name for conflict check
    private static final String CREATE_TENANT_LUA =
        "local existing = redis.call('GET', KEYS[1])\n" +
        "if existing then\n" +
        "  local tenant = cjson.decode(existing)\n" +
        "  if tenant['name'] ~= ARGV[3] then return {'CONFLICT', existing} end\n" +
        "  return {'EXISTS', existing}\n" +
        "end\n" +
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('SADD', KEYS[2], ARGV[2])\n" +
        "return {'CREATED'}\n";

    // NOTE: tenant update was previously an atomic Lua script that round-tripped
    // the full Tenant JSON through cjson.decode/encode. Tenant's only collection
    // field is a Map<String, String> (metadata), which deserializes cleanly from
    // an empty {} — so the corruption was symptom-free on this path in practice.
    // Migrated off cjson anyway for consistency with policy/apikey writes and to
    // eliminate the latent risk if any future Tenant field becomes a List<*>.
    // See the matching note on ApiKeyRepository.revoke() for the underlying bug.

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
                .defaultCommitOveragePolicy(request.getDefaultCommitOveragePolicy() != null ? request.getDefaultCommitOveragePolicy() : CommitOveragePolicy.ALLOW_IF_AVAILABLE)
                .defaultReservationTtlMs(request.getDefaultReservationTtlMs() != null ? request.getDefaultReservationTtlMs() : 60000L)
                .maxReservationTtlMs(request.getMaxReservationTtlMs() != null ? request.getMaxReservationTtlMs() : 3600000L)
                .maxReservationExtensions(request.getMaxReservationExtensions() != null ? request.getMaxReservationExtensions() : 10)
                .reservationExpiryPolicy(request.getReservationExpiryPolicy())
                .metadata(request.getMetadata())
                .createdAt(Instant.now())
                .build();
            String json = objectMapper.writeValueAsString(tenant);
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(CREATE_TENANT_LUA,
                List.of(key, "tenants"),
                List.of(json, request.getTenantId(), request.getName()));
            String status = result.get(0);
            if ("CONFLICT".equals(status)) {
                // Same tenant_id but different name → spec requires 409
                throw GovernanceException.duplicateResource("Tenant", request.getTenantId());
            }
            if ("EXISTS".equals(status)) {
                // Idempotent replay (same tenant_id and name) → 200
                Tenant existing = objectMapper.readValue(result.get(1), Tenant.class);
                return new TenantCreateResult(existing, false);
            }
            // CREATED
            return new TenantCreateResult(tenant, true);
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
    public List<Tenant> list(TenantStatus status, String parentTenantId, String cursor, int limit) {
        return list(status, parentTenantId, null, cursor, limit, null);
    }

    public List<Tenant> list(TenantStatus status, String parentTenantId, String cursor, int limit, SortSpec sortSpec) {
        return list(status, parentTenantId, null, cursor, limit, sortSpec);
    }

    /**
     * Search-aware list (spec v0.1.25.21). When {@code search} is non-null,
     * the tenant set is narrowed to rows whose {@code tenant_id} or
     * {@code name} contains the search value as a case-insensitive
     * substring. The filter is applied BEFORE cursor pagination so cursor
     * traversal is stable under a given (filters, sort, search) tuple.
     *
     * When {@code sortSpec} is null, falls back to the pre-sort tenant_id-
     * lexicographic order for wire-compat with older callers that haven't
     * opted in. Search applies in both the sort-aware and legacy paths.
     */
    public List<Tenant> list(TenantStatus status, String parentTenantId, String search,
                              String cursor, int limit, SortSpec sortSpec) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (sortSpec == null) {
                return listLegacy(jedis, status, parentTenantId, search, cursor, limit);
            }
            Set<String> ids = jedis.smembers("tenants");
            List<Tenant> hydrated = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("tenant:" + id);
                    if (data == null) {
                        LOG.warn("Tenant data missing for id: {}", id);
                        continue;
                    }
                    Tenant t = objectMapper.readValue(data, Tenant.class);
                    if (!matchesFilters(t, status, parentTenantId, search)) continue;
                    hydrated.add(t);
                } catch (Exception e) {
                    LOG.warn("Failed to load tenant: {}", id, e);
                }
            }
            hydrated.sort(tenantComparator(sortSpec));
            List<Tenant> page = new ArrayList<>();
            boolean pastCursor = (cursor == null || cursor.isBlank());
            for (Tenant t : hydrated) {
                if (!pastCursor) {
                    if (t.getTenantId().equals(cursor)) pastCursor = true;
                    continue;
                }
                page.add(t);
                if (page.size() >= limit) break;
            }
            return page;
        }
    }

    private List<Tenant> listLegacy(Jedis jedis, TenantStatus status, String parentTenantId,
                                     String search, String cursor, int limit) {
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
                if (!matchesFilters(t, status, parentTenantId, search)) continue;
                tenants.add(t);
                if (tenants.size() >= limit) break;
            } catch (Exception e) {
                LOG.warn("Failed to load tenant: {}", id, e);
            }
        }
        return tenants;
    }

    private static boolean matchesFilters(Tenant t, TenantStatus status, String parentTenantId, String search) {
        if (status != null && t.getStatus() != status) return false;
        if (parentTenantId != null && !parentTenantId.equals(t.getParentTenantId())) return false;
        if (search != null) {
            // Per spec v0.1.25.21: search matches tenant_id OR name (OR semantics
            // within the search filter, AND with other filter params).
            if (!SearchSpec.matches(t.getTenantId(), search)
                    && !SearchSpec.matches(t.getName(), search)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build a Comparator matching the requested SortSpec. The secondary
     * key on tenant_id guarantees a strict total order under equal
     * primary keys (e.g. two tenants sharing a name), so cursor
     * pagination is deterministic across calls.
     *
     * sortSpec == null preserves the pre-v0.1.25.20 default (tenant_id
     * ascending) for wire-compat with callers who haven't opted in.
     */
    private static Comparator<Tenant> tenantComparator(SortSpec sortSpec) {
        Comparator<String> nullLastStr = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<Tenant> tid = Comparator.comparing(Tenant::getTenantId, nullLastStr);
        if (sortSpec == null) {
            return tid;
        }
        String field = sortSpec.field() != null ? sortSpec.field() : "tenant_id";
        Comparator<Tenant> primary;
        switch (field) {
            case "name":
                primary = Comparator.comparing(Tenant::getName, nullLastStr);
                break;
            case "status":
                primary = Comparator.comparing(
                    t -> t.getStatus() != null ? t.getStatus().name() : null, nullLastStr);
                break;
            case "created_at":
                primary = Comparator.comparing(Tenant::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "tenant_id":
            default:
                primary = tid;
                break;
        }
        Comparator<Tenant> directed = sortSpec.isAscending() ? primary : primary.reversed();
        return directed.thenComparing(tid);
    }
    public Tenant update(String tenantId, TenantUpdateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "tenant:" + tenantId;
            String json = jedis.get(key);
            if (json == null) {
                throw GovernanceException.tenantNotFound(tenantId);
            }
            Tenant tenant = objectMapper.readValue(json, Tenant.class);
            Instant now = Instant.now();

            // Apply name change
            if (request.getName() != null) {
                tenant.setName(request.getName());
            }

            // Validate and apply status transition
            if (request.getStatus() != null) {
                TenantStatus oldStatus = tenant.getStatus() != null ? tenant.getStatus() : TenantStatus.ACTIVE;
                TenantStatus newStatus = request.getStatus();
                if (oldStatus == TenantStatus.CLOSED) {
                    throw new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                        "Cannot transition from CLOSED", 400);
                }
                // From ACTIVE: -> ACTIVE, SUSPENDED, CLOSED
                // From SUSPENDED: -> ACTIVE, SUSPENDED, CLOSED
                // (Both sets are the same after CLOSED is excluded as a source state.)
                if (!(newStatus == TenantStatus.ACTIVE
                        || newStatus == TenantStatus.SUSPENDED
                        || newStatus == TenantStatus.CLOSED)) {
                    throw new GovernanceException(
                        io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                        "Invalid status transition: " + oldStatus + " -> " + newStatus, 400);
                }
                tenant.setStatus(newStatus);
                if (newStatus == TenantStatus.SUSPENDED) {
                    tenant.setSuspendedAt(now);
                } else if (newStatus == TenantStatus.CLOSED) {
                    tenant.setClosedAt(now);
                }
            }

            // Metadata replace (match prior Lua behavior: a non-null metadata on the
            // request replaces the stored metadata wholesale)
            if (request.getMetadata() != null) {
                tenant.setMetadata(request.getMetadata());
            }
            if (request.getDefaultCommitOveragePolicy() != null) {
                tenant.setDefaultCommitOveragePolicy(request.getDefaultCommitOveragePolicy());
            }
            if (request.getDefaultReservationTtlMs() != null) {
                tenant.setDefaultReservationTtlMs(request.getDefaultReservationTtlMs());
            }
            if (request.getMaxReservationTtlMs() != null) {
                tenant.setMaxReservationTtlMs(request.getMaxReservationTtlMs());
            }
            if (request.getMaxReservationExtensions() != null) {
                tenant.setMaxReservationExtensions(request.getMaxReservationExtensions());
            }
            tenant.setUpdatedAt(now);

            jedis.set(key, objectMapper.writeValueAsString(tenant));
            return tenant;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
