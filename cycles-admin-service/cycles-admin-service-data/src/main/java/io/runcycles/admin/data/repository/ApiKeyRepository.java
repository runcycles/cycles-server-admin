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
    // Lua script for atomic API key creation: SET key + SADD index + SET lookup in one call
    private static final String CREATE_KEY_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('SADD', KEYS[2], ARGV[2])\n" +
        "redis.call('SET', KEYS[3], ARGV[2])\n" +
        "return 1\n";
    public ApiKeyCreateResponse create(ApiKeyCreateRequest request) {
        try (Jedis jedis = jedisPool.getResource()) {
            String keyId = "key_" + UUID.randomUUID().toString().substring(0, 16);
            String keySecret = keyService.generateKeySecret("gov");
            String keyPrefix = keyService.extractPrefix(keySecret);
            String keyHash = keyService.hashKey(keySecret);
            ApiKey apiKey = ApiKey.builder()
                .keyId(keyId)
                .tenantId(request.getTenantId())
                .keyPrefix(keyPrefix)
                .keyHash(keyHash)
                .name(request.getName())
                .description(request.getDescription())
                .permissions(request.getPermissions() != null ? request.getPermissions() : List.of("reservations:*", "balances:read"))
                .scopeFilter(request.getScopeFilter())
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.now())
                .expiresAt(request.getExpiresAt())
                .metadata(request.getMetadata())
                .build();
            // Atomic create: SET key + SADD index + SET lookup in one Lua call
            String json = objectMapper.writeValueAsString(apiKey);
            jedis.eval(CREATE_KEY_LUA,
                List.of("apikey:" + keyId, "apikeys:" + request.getTenantId(), "apikey:lookup:" + keyPrefix),
                List.of(json, keyId));
            return ApiKeyCreateResponse.builder()
                .keyId(keyId)
                .keySecret(keySecret)
                .keyPrefix(keyPrefix)
                .tenantId(request.getTenantId())
                .permissions(apiKey.getPermissions())
                .createdAt(apiKey.getCreatedAt())
                .expiresAt(apiKey.getExpiresAt())
                .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public List<ApiKey> list(String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers("apikeys:" + tenantId);
            List<ApiKey> keys = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("apikey:" + id);
                    if (data == null) {
                        LOG.warn("API key data missing for id: {}", id);
                        continue;
                    }
                    ApiKey key = objectMapper.readValue(data, ApiKey.class);
                    key.setKeyHash(null); // Don't expose hash
                    keys.add(key);
                } catch (Exception e) {
                    LOG.warn("Failed to parse key: {}", id, e);
                }
            }
            return keys;
        }
    }
    public ApiKey revoke(String keyId, String reason) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("apikey:" + keyId);
            if (data == null) throw GovernanceException.apiKeyNotFound(keyId);
            ApiKey key = objectMapper.readValue(data, ApiKey.class);
            key.setStatus(ApiKeyStatus.REVOKED);
            key.setRevokedAt(Instant.now());
            key.setRevokedReason(reason);
            jedis.set("apikey:" + keyId, objectMapper.writeValueAsString(key));
            key.setKeyHash(null);
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
                return ApiKeyValidationResponse.builder().valid(false).reason("KEY_NOT_FOUND").build();
            }
            String data = jedis.get("apikey:" + keyId);
            if (data == null) {
                return ApiKeyValidationResponse.builder().valid(false).reason("KEY_NOT_FOUND").build();
            }
            ApiKey key = objectMapper.readValue(data, ApiKey.class);
            if (key.getStatus() != ApiKeyStatus.ACTIVE) {
                return ApiKeyValidationResponse.builder().valid(false).reason("KEY_" + key.getStatus()).build();
            }
            if (key.getExpiresAt() != null && Instant.now().isAfter(key.getExpiresAt())) {
                return ApiKeyValidationResponse.builder().valid(false).reason("KEY_EXPIRED").build();
            }
            if (!keyService.verifyKey(keySecret, key.getKeyHash())) {
                return ApiKeyValidationResponse.builder().valid(false).reason("INVALID_KEY").build();
            }
            key.setLastUsedAt(Instant.now());
            jedis.set("apikey:" + keyId, objectMapper.writeValueAsString(key));
            return ApiKeyValidationResponse.builder()
                .valid(true)
                .tenantId(key.getTenantId())
                .keyId(key.getKeyId())
                .permissions(key.getPermissions())
                .scopeFilter(key.getScopeFilter())
                .expiresAt(key.getExpiresAt())
                .build();
        } catch (Exception e) {
            LOG.error("Key validation failed", e);
            return ApiKeyValidationResponse.builder().valid(false).reason("INTERNAL_ERROR").build();
        }
    }
}
