package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.service.KeyService;
import io.runcycles.admin.model.auth.*;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @Mock private KeyService keyService;
    @InjectMocks private ApiKeyRepository repository;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
    }

    @Test
    void create_validRequest_returnsApiKeyCreateResponse() throws Exception {
        // Tenant exists and is active
        when(jedis.get("tenant:test-tenant")).thenReturn("{\"status\":\"ACTIVE\",\"tenant_id\":\"test-tenant\"}");
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix("cyc_live_abc123def456ghi")).thenReturn("cyc_live_abc12");
        when(keyService.hashKey("cyc_live_abc123def456ghi")).thenReturn("$2a$12$hashvalue");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Test Key");

        ApiKeyCreateResponse response = repository.create(request);

        assertThat(response.getKeySecret()).isEqualTo("cyc_live_abc123def456ghi");
        assertThat(response.getKeyPrefix()).isEqualTo("cyc_live_abc12");
        assertThat(response.getTenantId()).isEqualTo("test-tenant");
        assertThat(response.getPermissions()).isNotEmpty();
    }

    @Test
    void create_tenantNotFound_throwsException() {
        when(jedis.get("tenant:missing")).thenReturn(null);

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("missing");
        request.setName("Test Key");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void create_tenantSuspended_throwsException() {
        when(jedis.get("tenant:suspended")).thenReturn("{\"status\":\"SUSPENDED\",\"tenant_id\":\"suspended\"}");

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("suspended");
        request.setName("Test Key");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void create_withCustomExpiry_usesProvidedExpiry() throws Exception {
        Instant customExpiry = Instant.now().plusSeconds(3600);
        when(jedis.get("tenant:test-tenant")).thenReturn("{\"status\":\"ACTIVE\",\"tenant_id\":\"test-tenant\"}");
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix(anyString())).thenReturn("cyc_live_abc12");
        when(keyService.hashKey(anyString())).thenReturn("$2a$12$hash");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Test Key");
        request.setExpiresAt(customExpiry);

        ApiKeyCreateResponse response = repository.create(request);

        assertThat(response.getExpiresAt()).isEqualTo(customExpiry);
    }

    @Test
    void validate_validKey_returnsValidResponse() throws Exception {
        String keySecret = "cyc_live_abc123def456ghi";
        when(keyService.extractPrefix(keySecret)).thenReturn("cyc_live_abc12");
        when(jedis.get("apikey:lookup:cyc_live_abc123def4")).thenReturn("key_123");

        ApiKey key = ApiKey.builder()
                .keyId("key_123").tenantId("t1").keyPrefix("cyc_live_abc12")
                .keyHash("$2a$12$hash").status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_123")).thenReturn(keyJson);

        when(keyService.verifyKey(keySecret, "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:t1")).thenReturn("{\"status\":\"ACTIVE\"}");

        ApiKeyValidationResponse response = repository.validate(keySecret);

        assertThat(response.getValid()).isTrue();
        assertThat(response.getTenantId()).isEqualTo("t1");
        assertThat(response.getKeyId()).isEqualTo("key_123");
    }

    @Test
    void validate_unknownPrefix_returnsInvalid() {
        when(keyService.extractPrefix("unknown_key")).thenReturn("unknown_key1234");
        when(jedis.get("apikey:lookup:unknown_key1234")).thenReturn(null);

        ApiKeyValidationResponse response = repository.validate("unknown_key");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void validate_revokedKey_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_revoked")).thenReturn("cyc_live_revoked1234");
        when(jedis.get("apikey:lookup:cyc_live_revoked1234")).thenReturn("key_rev");

        ApiKey key = ApiKey.builder()
                .keyId("key_rev").tenantId("t1").keyHash("hash")
                .status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_rev")).thenReturn(keyJson);

        ApiKeyValidationResponse response = repository.validate("cyc_live_revoked");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_REVOKED");
    }

    @Test
    void validate_expiredKey_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_expired")).thenReturn("cyc_live_expired12345");
        when(jedis.get("apikey:lookup:cyc_live_expired12345")).thenReturn("key_exp");

        ApiKey key = ApiKey.builder()
                .keyId("key_exp").tenantId("t1").keyHash("hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_exp")).thenReturn(keyJson);

        ApiKeyValidationResponse response = repository.validate("cyc_live_expired");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_EXPIRED");
    }

    @Test
    void validate_wrongSecret_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_wrong")).thenReturn("cyc_live_wrong123456");
        when(jedis.get("apikey:lookup:cyc_live_wrong123456")).thenReturn("key_w");

        ApiKey key = ApiKey.builder()
                .keyId("key_w").tenantId("t1").keyHash("$2a$12$real")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_w")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_wrong", "$2a$12$real")).thenReturn(false);

        ApiKeyValidationResponse response = repository.validate("cyc_live_wrong");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("INVALID_KEY");
    }

    @Test
    void validate_suspendedTenant_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_susp")).thenReturn("cyc_live_susp12345678");
        when(jedis.get("apikey:lookup:cyc_live_susp12345678")).thenReturn("key_s");

        ApiKey key = ApiKey.builder()
                .keyId("key_s").tenantId("t1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_s")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_susp", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:t1")).thenReturn("{\"status\":\"SUSPENDED\"}");

        ApiKeyValidationResponse response = repository.validate("cyc_live_susp");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("TENANT_SUSPENDED");
    }

    @Test
    void validate_closedTenant_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_closed")).thenReturn("cyc_live_closed123456");
        when(jedis.get("apikey:lookup:cyc_live_closed123456")).thenReturn("key_c");

        ApiKey key = ApiKey.builder()
                .keyId("key_c").tenantId("t1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_c")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_closed", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:t1")).thenReturn("{\"status\":\"CLOSED\"}");

        ApiKeyValidationResponse response = repository.validate("cyc_live_closed");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("TENANT_CLOSED");
    }

    @Test
    void revoke_existingKey_setsRevokedStatus() throws Exception {
        ApiKey key = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyHash("hash")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_1")).thenReturn(keyJson);

        ApiKey result = repository.revoke("key_1", "No longer needed");

        assertThat(result.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(result.getRevokedReason()).isEqualTo("No longer needed");
        assertThat(result.getRevokedAt()).isNotNull();
        verify(jedis).set(eq("apikey:key_1"), anyString());
    }

    @Test
    void revoke_missingKey_throwsNotFound() {
        when(jedis.get("apikey:missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.revoke("missing", "reason"))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    @Test
    void list_returnsKeysForTenant() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:t1")).thenReturn(ids);

        ApiKey k1 = ApiKey.builder().keyId("key_1").tenantId("t1").keyHash("h1").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("t1").keyHash("h2").status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build();
        String k1Json = objectMapper.writeValueAsString(k1);
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_1")).thenReturn(k1Json);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("t1", null, null, 50);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_filtersKeysByStatus() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:t1")).thenReturn(ids);

        ApiKey k1 = ApiKey.builder().keyId("key_1").tenantId("t1").keyHash("h1").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("t1").keyHash("h2").status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build();
        String k1Json = objectMapper.writeValueAsString(k1);
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_1")).thenReturn(k1Json);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("t1", ApiKeyStatus.ACTIVE, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_1");
    }
}
