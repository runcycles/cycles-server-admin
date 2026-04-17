package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.service.KeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.auth.*;
import io.runcycles.admin.model.shared.SortSpec;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
import java.util.Comparator;
@Repository
public class ApiKeyRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private KeyService keyService;
    private static final List<String> DEFAULT_PERMISSIONS = List.of(
        "reservations:create", "reservations:commit", "reservations:release",
        "reservations:extend", "reservations:list", "balances:read");

    // Cross-tenant sorted list must hydrate every tenant's keys before the
    // in-memory sort, which is unbounded in the naive case (10k tenants *
    // 50 keys = 500k hydrations). Cap at 2000 total keys across all tenants
    // so the admin pane cannot exhaust heap on a cross-tenant sort + broad
    // filter. Callers that need to see beyond the cap should narrow the
    // filter (status, tenant_id) to fit under it. Log at WARN when hit so
    // operators can see that the sort window was truncated.
    static final int SORTED_HYDRATE_CAP = 2000;
    // Lua script for atomic API key creation with tenant validation.
    // Validates tenant exists and is ACTIVE atomically, then creates key + index + lookup.
    // KEYS[1] = apikey:<keyId>, KEYS[2] = apikeys:<tenantId>, KEYS[3] = apikey:lookup:<prefix>,
    // KEYS[4] = tenant:<tenantId>
    // ARGV[1] = key JSON, ARGV[2] = keyId
    // Returns: {'CREATED'}, {'TENANT_NOT_FOUND'}, or {'TENANT_INACTIVE', status}
    private static final String CREATE_KEY_LUA =
        "local tenant_json = redis.call('GET', KEYS[4])\n" +
        "if not tenant_json then return {'TENANT_NOT_FOUND'} end\n" +
        "local tenant = cjson.decode(tenant_json)\n" +
        "local tenant_status = tenant['status'] or 'ACTIVE'\n" +
        "if tenant_status ~= 'ACTIVE' then return {'TENANT_INACTIVE', tenant_status} end\n" +
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('SADD', KEYS[2], ARGV[2])\n" +
        "redis.call('SET', KEYS[3], ARGV[2])\n" +
        "return {'CREATED'}\n";

    // NOTE: API-key revocation was previously implemented as a Lua script that
    // round-tripped the full record JSON through cjson.decode/encode. That tripped
    // Redis's well-known empty-array bug — `scope_filter: []` (and `permissions: []`
    // on a freshly created key with no permissions override) were rewritten as
    // `{}` by cjson.encode, after which Jackson could no longer deserialize the
    // record (ApiKey.scope_filter / permissions are List<String>). The list()
    // catch-all would then silently drop the key from admin responses.
    // The revoke path now follows the same Jackson-in-Java pattern as update()
    // below — see its docblock. Kept here as a hazard marker for anyone thinking
    // about reintroducing cjson round-trips on records with array fields.
    public ApiKeyCreateResponse create(ApiKeyCreateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
            // Validate permission values explicitly — produces a clear 400
            // naming the bad value instead of Jackson's generic
            // "Malformed request body" that the enum-bound path used to emit.
            String unknown = Permission.findUnknown(request.getPermissions());
            if (unknown != null) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Unrecognized permission: " + unknown, 400);
            }
            String keyId = "key_" + UUID.randomUUID().toString().substring(0, 16);
            String keySecret = keyService.generateKeySecret("cyc_live");
            String keyPrefix = keyService.extractPrefix(keySecret);
            String keyHash = keyService.hashKey(keySecret);
            Instant expiresAt = request.getExpiresAt() != null
                ? request.getExpiresAt()
                : Instant.now().plus(java.time.Duration.ofDays(90));
            ApiKey apiKey = ApiKey.builder()
                .keyId(keyId)
                .tenantId(request.getTenantId())
                .keyPrefix(keyPrefix)
                .keyHash(keyHash)
                .name(request.getName())
                .description(request.getDescription())
                .permissions(request.getPermissions() != null ? request.getPermissions() : DEFAULT_PERMISSIONS)
                .scopeFilter(request.getScopeFilter())
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .metadata(request.getMetadata())
                .build();
            // Atomic create with tenant validation: check tenant + SET key + SADD index + SET lookup
            String json = objectMapper.writeValueAsString(apiKey);
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(CREATE_KEY_LUA,
                List.of("apikey:" + keyId, "apikeys:" + request.getTenantId(),
                         "apikey:lookup:" + keyPrefix, "tenant:" + request.getTenantId()),
                List.of(json, keyId));
            String status = result.get(0);
            if ("TENANT_NOT_FOUND".equals(status)) {
                throw GovernanceException.tenantNotFound(request.getTenantId());
            }
            if ("TENANT_INACTIVE".equals(status)) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Tenant is " + result.get(1) + ": " + request.getTenantId(), 400);
            }
            return ApiKeyCreateResponse.builder()
                .keyId(keyId)
                .keySecret(keySecret)
                .keyPrefix(keyPrefix)
                .tenantId(request.getTenantId())
                .permissions(apiKey.getPermissions())
                .createdAt(apiKey.getCreatedAt())
                .expiresAt(apiKey.getExpiresAt())
                .build();
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public List<ApiKey> list(String tenantId, ApiKeyStatus statusFilter, String cursor, int limit) {
        return list(tenantId, statusFilter, cursor, limit, null);
    }

    /**
     * Single-tenant list with optional sort (spec v0.1.25.20). When sortSpec is
     * null, preserves the pre-v0.1.25.20 cursor-on-raw-keyId semantics so old
     * clients see no behavior change. When present, hydrates all keys for the
     * tenant, applies the status filter, sorts via {@link #apiKeyComparator},
     * then walks the cursor (strictly-after match) and takes limit. The cursor
     * remains a bare keyId — stable pagination depends on the caller passing
     * the same sortSpec on follow-up pages.
     */
    public List<ApiKey> list(String tenantId, ApiKeyStatus statusFilter, String cursor, int limit, SortSpec sortSpec) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (sortSpec == null) {
                return listLegacy(jedis, tenantId, statusFilter, cursor, limit);
            }
            return listSorted(jedis, tenantId, statusFilter, cursor, limit, sortSpec);
        }
    }

    private List<ApiKey> listLegacy(Jedis jedis, String tenantId, ApiKeyStatus statusFilter, String cursor, int limit) {
        Set<String> ids = jedis.smembers("apikeys:" + tenantId);
        List<String> sortedIds = new ArrayList<>(ids);
        Collections.sort(sortedIds);
        List<ApiKey> keys = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        for (String id : sortedIds) {
            if (!pastCursor) {
                if (id.equals(cursor)) pastCursor = true;
                continue;
            }
            try {
                String data = jedis.get("apikey:" + id);
                if (data == null) {
                    LOG.warn("API key data missing for id: {}", id);
                    continue;
                }
                ApiKey key = objectMapper.readValue(data, ApiKey.class);
                if (statusFilter != null && key.getStatus() != statusFilter) continue;
                keys.add(key);
                if (keys.size() >= limit) break;
            } catch (Exception e) {
                LOG.warn("Failed to parse key: {}", id, e);
            }
        }
        return keys;
    }

    private List<ApiKey> listSorted(Jedis jedis, String tenantId, ApiKeyStatus statusFilter, String cursor, int limit, SortSpec sortSpec) {
        Set<String> ids = jedis.smembers("apikeys:" + tenantId);
        List<ApiKey> all = new ArrayList<>();
        for (String id : ids) {
            try {
                String data = jedis.get("apikey:" + id);
                if (data == null) continue;
                ApiKey key = objectMapper.readValue(data, ApiKey.class);
                if (statusFilter != null && key.getStatus() != statusFilter) continue;
                all.add(key);
            } catch (Exception e) {
                LOG.warn("Failed to parse key: {}", id, e);
            }
        }
        all.sort(apiKeyComparator(sortSpec));
        List<ApiKey> result = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        for (ApiKey key : all) {
            if (!pastCursor) {
                if (cursor.equals(key.getKeyId())) pastCursor = true;
                continue;
            }
            result.add(key);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /**
     * Null-safe comparator for the sort fields whitelisted in
     * ApiKeyController. Secondary sort on keyId guarantees a total
     * order so cursor resume is deterministic. Unknown fields fall
     * back to keyId — controller validation should have rejected them
     * already, but this keeps the repo contract total in case a caller
     * bypasses the controller layer.
     */
    static Comparator<ApiKey> apiKeyComparator(SortSpec sortSpec) {
        String field = sortSpec.field();
        Comparator<ApiKey> primary;
        switch (field) {
            case "name":
                primary = Comparator.comparing(ApiKey::getName, Comparator.nullsLast(String::compareTo));
                break;
            case "tenant_id":
                primary = Comparator.comparing(ApiKey::getTenantId, Comparator.nullsLast(String::compareTo));
                break;
            case "status":
                primary = Comparator.comparing(
                    k -> k.getStatus() == null ? null : k.getStatus().name(),
                    Comparator.nullsLast(String::compareTo));
                break;
            case "created_at":
                primary = Comparator.comparing(ApiKey::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "expires_at":
                primary = Comparator.comparing(ApiKey::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "key_id":
            default:
                primary = Comparator.comparing(ApiKey::getKeyId, Comparator.nullsLast(String::compareTo));
                break;
        }
        Comparator<ApiKey> withTieBreak = primary.thenComparing(
            ApiKey::getKeyId, Comparator.nullsLast(String::compareTo));
        return sortSpec.isAscending() ? withTieBreak : withTieBreak.reversed();
    }

    public List<ApiKey> list(String tenantId) {
        return list(tenantId, null, null, 1000);
    }

    /**
     * Cross-tenant listing introduced in governance spec v0.1.25.18 for
     * AdminKeyAuth callers that did not supply a `tenant_id` query param.
     * Iterates the global `tenants` set in sorted order; for each tenant,
     * walks that tenant's keys in sorted order. Filters apply before
     * cursor traversal so pagination is stable.
     *
     * Cursor format: "{tenantId}|{keyId}". Resume semantics:
     *   - cursor tenant still present → resume within it using cursorKeyId
     *     (strictly after that key), then continue into later tenants.
     *   - cursor tenant deleted between pages → advance to the first
     *     tenant whose id is lexically greater than cursorTenantId and
     *     serve it from the beginning. Without this "skip forward"
     *     behaviour the iterator would stall (never match by equality)
     *     and the client would incorrectly infer end-of-data.
     */
    public List<ApiKey> listAllTenants(ApiKeyStatus statusFilter, String cursor, int limit) {
        return listAllTenants(statusFilter, cursor, limit, null);
    }

    /**
     * Cross-tenant list with optional sort (spec v0.1.25.20). When sortSpec is
     * null, preserves the v0.1.25.18 per-tenant walk (stable cursor = per-tenant
     * keyId, tenants iterated in id order, skip-forward when cursor tenant
     * deleted). When present, hydrates every tenant's keys, applies the filter,
     * sorts globally via {@link #apiKeyComparator}, then walks the composite
     * "{tenantId}|{keyId}" cursor to the strictly-next entry and takes limit.
     * The cross-tenant cursor format is unchanged — callers keep passing the
     * same sortSpec on follow-up pages for stability.
     */
    public List<ApiKey> listAllTenants(ApiKeyStatus statusFilter, String cursor, int limit, SortSpec sortSpec) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (sortSpec == null) {
                return listAllTenantsLegacy(jedis, statusFilter, cursor, limit);
            }
            return listAllTenantsSorted(jedis, statusFilter, cursor, limit, sortSpec);
        }
    }

    private List<ApiKey> listAllTenantsLegacy(Jedis jedis, ApiKeyStatus statusFilter, String cursor, int limit) {
        String cursorTenantId = null;
        String cursorKeyId = null;
        if (cursor != null && !cursor.isBlank()) {
            int sep = cursor.indexOf('|');
            if (sep > 0) {
                cursorTenantId = cursor.substring(0, sep);
                cursorKeyId = cursor.substring(sep + 1);
            } else {
                cursorTenantId = cursor;
            }
        }
        Set<String> tenantIds = jedis.smembers("tenants");
        List<String> sortedTenantIds = new ArrayList<>(tenantIds);
        Collections.sort(sortedTenantIds);
        List<ApiKey> collected = new ArrayList<>();
        boolean pastTenantCursor = (cursorTenantId == null);
        for (String tenantId : sortedTenantIds) {
            String innerCursor;
            if (!pastTenantCursor) {
                int cmp = tenantId.compareTo(cursorTenantId);
                if (cmp < 0) continue;
                pastTenantCursor = true;
                // cmp == 0: same tenant as cursor → resume inside using cursorKeyId.
                // cmp  > 0: cursor tenant was deleted → serve this tenant from start.
                innerCursor = (cmp == 0) ? cursorKeyId : null;
            } else {
                innerCursor = null;
            }
            int remaining = limit - collected.size();
            if (remaining <= 0) break;
            collected.addAll(collectForTenant(jedis, tenantId, statusFilter, innerCursor, remaining));
            if (collected.size() >= limit) break;
        }
        return collected;
    }

    private List<ApiKey> listAllTenantsSorted(Jedis jedis, ApiKeyStatus statusFilter, String cursor, int limit, SortSpec sortSpec) {
        Set<String> tenantIds = jedis.smembers("tenants");
        List<ApiKey> all = new ArrayList<>();
        boolean capped = false;
        outer:
        for (String tenantId : tenantIds) {
            Set<String> keyIds = jedis.smembers("apikeys:" + tenantId);
            for (String keyId : keyIds) {
                if (all.size() >= SORTED_HYDRATE_CAP) { capped = true; break outer; }
                try {
                    String data = jedis.get("apikey:" + keyId);
                    if (data == null) continue;
                    ApiKey key = objectMapper.readValue(data, ApiKey.class);
                    if (statusFilter != null && key.getStatus() != statusFilter) continue;
                    all.add(key);
                } catch (Exception e) {
                    LOG.warn("Failed to parse key: {}", keyId, e);
                }
            }
        }
        if (capped) {
            LOG.warn("listAllTenants sort hydration capped at {} keys; narrow filters to see beyond the cap", SORTED_HYDRATE_CAP);
        }
        all.sort(apiKeyComparator(sortSpec));
        List<ApiKey> result = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        String cursorTenantId = null;
        String cursorKeyId = null;
        if (!pastCursor) {
            int sep = cursor.indexOf('|');
            if (sep > 0) {
                cursorTenantId = cursor.substring(0, sep);
                cursorKeyId = cursor.substring(sep + 1);
            } else {
                cursorKeyId = cursor;
            }
        }
        for (ApiKey key : all) {
            if (!pastCursor) {
                boolean match = Objects.equals(key.getKeyId(), cursorKeyId)
                    && (cursorTenantId == null || Objects.equals(key.getTenantId(), cursorTenantId));
                if (match) pastCursor = true;
                continue;
            }
            result.add(key);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private List<ApiKey> collectForTenant(Jedis jedis, String tenantId, ApiKeyStatus statusFilter, String cursor, int limit) {
        Set<String> ids = jedis.smembers("apikeys:" + tenantId);
        List<String> sortedIds = new ArrayList<>(ids);
        Collections.sort(sortedIds);
        List<ApiKey> keys = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        for (String id : sortedIds) {
            if (!pastCursor) {
                if (id.equals(cursor)) pastCursor = true;
                continue;
            }
            try {
                String data = jedis.get("apikey:" + id);
                if (data == null) {
                    LOG.warn("API key data missing for id: {}", id);
                    continue;
                }
                ApiKey key = objectMapper.readValue(data, ApiKey.class);
                if (statusFilter != null && key.getStatus() != statusFilter) continue;
                keys.add(key);
                if (keys.size() >= limit) break;
            } catch (Exception e) {
                LOG.warn("Failed to parse key: {}", id, e);
            }
        }
        return keys;
    }
    /**
     * Update mutable fields on an API key. Uses Jackson for serialization
     * (not Lua cjson roundtrip) to avoid the Redis cjson empty-array bug
     * where [] becomes {} and breaks deserialization.
     */
    public ApiKey update(String keyId, ApiKeyUpdateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get("apikey:" + keyId);
            if (json == null) {
                throw GovernanceException.apiKeyNotFound(keyId);
            }
            ApiKey key = objectMapper.readValue(json, ApiKey.class);
            if (key.getStatus() == ApiKeyStatus.REVOKED) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Cannot modify a REVOKED key", 409);
            }
            if (key.getStatus() == ApiKeyStatus.EXPIRED) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Cannot modify an EXPIRED key", 409);
            }
            // Validate permission values explicitly — produces a clear 400
            // naming the bad value. See equivalent check in create().
            String unknown = Permission.findUnknown(request.getPermissions());
            if (unknown != null) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "Unrecognized permission: " + unknown, 400);
            }
            // Apply partial updates — only non-null fields
            if (request.getName() != null) key.setName(request.getName());
            if (request.getDescription() != null) key.setDescription(request.getDescription());
            if (request.getPermissions() != null) key.setPermissions(request.getPermissions());
            if (request.getScopeFilter() != null) key.setScopeFilter(request.getScopeFilter());
            if (request.getMetadata() != null) key.setMetadata(request.getMetadata());
            // Write back with Jackson (clean serialization, no cjson issues)
            jedis.set("apikey:" + keyId, objectMapper.writeValueAsString(key));
            return key;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Revoke an API key. Uses Jackson in-Java (not a Lua cjson round-trip) to
     * avoid the Redis cjson empty-array bug — see the comment where the old
     * REVOKE_KEY_LUA used to live, and the matching note on update().
     *
     * Per spec (cycles-governance-admin-v0.1.25.yaml → revokeApiKey), attempting
     * to revoke a key that is already REVOKED must return 409 ALREADY_REVOKED.
     * The previous Lua path returned the stored record with HTTP 200, which was
     * a pre-existing spec violation; this migration corrects it.
     */
    public ApiKey revoke(String keyId, String reason) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get("apikey:" + keyId);
            if (json == null) {
                throw GovernanceException.apiKeyNotFound(keyId);
            }
            ApiKey key = objectMapper.readValue(json, ApiKey.class);
            if (key.getStatus() == ApiKeyStatus.REVOKED) {
                throw GovernanceException.apiKeyAlreadyRevoked(keyId);
            }
            key.setStatus(ApiKeyStatus.REVOKED);
            key.setRevokedAt(Instant.now());
            if (reason != null && !reason.isEmpty()) {
                key.setRevokedReason(reason);
            }
            jedis.set("apikey:" + keyId, objectMapper.writeValueAsString(key));
            return key;
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public ApiKeyValidationResponse validate(String keySecret) {
        try (Jedis jedis = jedisPool.getResource()) {
            String prefix = keyService.extractPrefix(keySecret);
            String keyId = jedis.get("apikey:lookup:" + prefix);
            if (keyId == null) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build();
            }
            String data = jedis.get("apikey:" + keyId);
            if (data == null) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_FOUND").build();
            }
            ApiKey key = objectMapper.readValue(data, ApiKey.class);
            if (key.getStatus() != ApiKeyStatus.ACTIVE) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("KEY_" + key.getStatus()).build();
            }
            if (key.getExpiresAt() != null && Instant.now().isAfter(key.getExpiresAt())) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("KEY_EXPIRED").build();
            }
            if (!keyService.verifyKey(keySecret, key.getKeyHash())) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId() != null ? key.getTenantId() : "").reason("INVALID_KEY").build();
            }
            if (key.getTenantId() == null || key.getTenantId().isBlank()) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("KEY_NOT_OWNED_BY_TENANT").build();
            }
            // Check tenant exists and is ACTIVE (spec: tenant must exist for key to be valid)
            String tenantData = jedis.get("tenant:" + key.getTenantId());
            if (tenantData == null) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_NOT_FOUND").build();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tenantMap = objectMapper.readValue(tenantData, Map.class);
            String tenantStatus = (String) tenantMap.get("status");
            if ("SUSPENDED".equals(tenantStatus)) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_SUSPENDED").build();
            }
            if ("CLOSED".equals(tenantStatus)) {
                return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_CLOSED").build();
            }
            // Coerce null permissions to empty list (consistent with cycles-server authority)
            List<String> permissions = key.getPermissions() != null ? key.getPermissions() : Collections.emptyList();
            //Not sure do we need such update on access
            //key.setLastUsedAt(Instant.now());
            //jedis.set("apikey:" + keyId, objectMapper.writeValueAsString(key));
            return ApiKeyValidationResponse.builder()
                .valid(true)
                .tenantId(key.getTenantId())
                .keyId(key.getKeyId())
                .permissions(permissions)
                .scopeFilter(key.getScopeFilter())
                .expiresAt(key.getExpiresAt())
                .build();
        } catch (Exception e) {
            return ApiKeyValidationResponse.builder().valid(false).tenantId("").reason("INTERNAL_ERROR").build();
        }
    }
}
