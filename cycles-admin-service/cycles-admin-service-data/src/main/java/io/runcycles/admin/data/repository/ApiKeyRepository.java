package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.service.KeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.auth.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class ApiKeyRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private KeyService keyService;
    private static final List<String> DEFAULT_PERMISSIONS = List.of(
        "reservations:create", "reservations:commit", "reservations:release",
        "reservations:extend", "reservations:list", "balances:read");
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

    // Lua script for atomic API key revocation: reads, checks state, sets revoked fields in one call.
    // KEYS[1] = apikey:<keyId>
    // ARGV[1] = reason, ARGV[2] = now_iso (ISO-8601 timestamp, must match Jackson serialization format)
    // Returns: {'OK', updated_json} or {'NOT_FOUND'} or {'ALREADY_REVOKED', existing_json}
    private static final String REVOKE_KEY_LUA =
        "local json = redis.call('GET', KEYS[1])\n" +
        "if not json then return {'NOT_FOUND'} end\n" +
        "local key = cjson.decode(json)\n" +
        "if key['status'] == 'REVOKED' then return {'ALREADY_REVOKED', json} end\n" +
        "key['status'] = 'REVOKED'\n" +
        "key['revoked_at'] = ARGV[2]\n" +
        "if ARGV[1] ~= '' then key['revoked_reason'] = ARGV[1] end\n" +
        "local updated = cjson.encode(key)\n" +
        "redis.call('SET', KEYS[1], updated)\n" +
        "return {'OK', updated}\n";
    public ApiKeyCreateResponse create(ApiKeyCreateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
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
        try (Jedis jedis = jedisPool.getResource()) {
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
    }
    public List<ApiKey> list(String tenantId) {
        return list(tenantId, null, null, 1000);
    }
    public ApiKey revoke(String keyId, String reason) {
        try (Jedis jedis = jedisPool.getResource()) {
            String nowIso = Instant.now().toString();

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.eval(REVOKE_KEY_LUA,
                List.of("apikey:" + keyId),
                List.of(reason != null ? reason : "", nowIso));

            String status = result.get(0);
            if ("NOT_FOUND".equals(status)) {
                throw GovernanceException.apiKeyNotFound(keyId);
            }
            // Both OK and ALREADY_REVOKED return the key JSON in result.get(1)
            return objectMapper.readValue(result.get(1), ApiKey.class);
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
            // Check tenant status (admin-specific: validates tenant is not suspended/closed)
            String tenantData = jedis.get("tenant:" + key.getTenantId());
            if (tenantData != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tenantMap = objectMapper.readValue(tenantData, Map.class);
                String tenantStatus = (String) tenantMap.get("status");
                if ("SUSPENDED".equals(tenantStatus)) {
                    return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_SUSPENDED").build();
                }
                if ("CLOSED".equals(tenantStatus)) {
                    return ApiKeyValidationResponse.builder().valid(false).tenantId(key.getTenantId()).reason("TENANT_CLOSED").build();
                }
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
