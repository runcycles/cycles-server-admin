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
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix("cyc_live_abc123def456ghi")).thenReturn("cyc_live_abc12");
        when(keyService.hashKey("cyc_live_abc123def456ghi")).thenReturn("$2a$12$hashvalue");
        // Lua now validates tenant atomically and returns status list
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CREATED"));

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
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix(anyString())).thenReturn("cyc_live_abc12");
        when(keyService.hashKey(anyString())).thenReturn("$2a$12$hash");
        // Lua returns TENANT_NOT_FOUND when tenant key doesn't exist in Redis
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("TENANT_NOT_FOUND"));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("missing");
        request.setName("Test Key");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
    }

    @Test
    void create_tenantSuspended_throwsException() {
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix(anyString())).thenReturn("cyc_live_abc12");
        when(keyService.hashKey(anyString())).thenReturn("$2a$12$hash");
        // Lua returns TENANT_INACTIVE with status when tenant is not ACTIVE
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("TENANT_INACTIVE", "SUSPENDED"));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("suspended");
        request.setName("Test Key");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getHttpStatus()).isEqualTo(400);
                });
    }

    @Test
    void create_tenantClosed_throwsException() {
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix(anyString())).thenReturn("cyc_live_abc12");
        when(keyService.hashKey(anyString())).thenReturn("$2a$12$hash");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("TENANT_INACTIVE", "CLOSED"));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("closed-tenant");
        request.setName("Test Key");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getHttpStatus()).isEqualTo(400);
                });
    }

    @Test
    void create_withCustomExpiry_usesProvidedExpiry() throws Exception {
        Instant customExpiry = Instant.now().plusSeconds(3600);
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix(anyString())).thenReturn("cyc_live_abc12");
        when(keyService.hashKey(anyString())).thenReturn("$2a$12$hash");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CREATED"));

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
        when(jedis.get("apikey:lookup:cyc_live_abc12")).thenReturn("key_123");

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
        when(keyService.extractPrefix("unknown_key")).thenReturn("unknown_key");
        when(jedis.get("apikey:lookup:unknown_key")).thenReturn(null);

        ApiKeyValidationResponse response = repository.validate("unknown_key");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void validate_revokedKey_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_revoked")).thenReturn("cyc_live_revok");
        when(jedis.get("apikey:lookup:cyc_live_revok")).thenReturn("key_rev");

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
        when(keyService.extractPrefix("cyc_live_expired")).thenReturn("cyc_live_expir");
        when(jedis.get("apikey:lookup:cyc_live_expir")).thenReturn("key_exp");

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
        when(keyService.extractPrefix("cyc_live_wrong")).thenReturn("cyc_live_wrong");
        when(jedis.get("apikey:lookup:cyc_live_wrong")).thenReturn("key_w");

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
        when(keyService.extractPrefix("cyc_live_susp")).thenReturn("cyc_live_susp1");
        when(jedis.get("apikey:lookup:cyc_live_susp1")).thenReturn("key_s");

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
        when(keyService.extractPrefix("cyc_live_closed")).thenReturn("cyc_live_close");
        when(jedis.get("apikey:lookup:cyc_live_close")).thenReturn("key_c");

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
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyHash("hash")
                .status(ApiKeyStatus.REVOKED).revokedReason("No longer needed")
                .revokedAt(Instant.now()).createdAt(Instant.now()).build();
        String revokedJson = objectMapper.writeValueAsString(revoked);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", revokedJson));

        ApiKey result = repository.revoke("key_1", "No longer needed");

        assertThat(result.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(result.getRevokedReason()).isEqualTo("No longer needed");
        assertThat(result.getRevokedAt()).isNotNull();
    }

    @Test
    void revoke_missingKey_throwsNotFound() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("NOT_FOUND"));

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

    @Test
    void list_cursorPagination_skipsEntriesBeforeCursor() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2", "key_3"));
        when(jedis.smembers("apikeys:t1")).thenReturn(ids);

        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("t1").keyHash("h2").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey k3 = ApiKey.builder().keyId("key_3").tenantId("t1").keyHash("h3").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k2Json = objectMapper.writeValueAsString(k2);
        String k3Json = objectMapper.writeValueAsString(k3);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);
        when(jedis.get("apikey:key_3")).thenReturn(k3Json);

        List<ApiKey> result = repository.list("t1", null, "key_1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_2");
    }

    @Test
    void list_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2", "key_3"));
        when(jedis.smembers("apikeys:t1")).thenReturn(ids);

        ApiKey k1 = ApiKey.builder().keyId("key_1").tenantId("t1").keyHash("h1").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k1Json = objectMapper.writeValueAsString(k1);
        when(jedis.get("apikey:key_1")).thenReturn(k1Json);

        List<ApiKey> result = repository.list("t1", null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_missingKeyData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:t1")).thenReturn(ids);

        when(jedis.get("apikey:key_1")).thenReturn(null);
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("t1").keyHash("h2").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("t1", null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_2");
    }

    @Test
    void list_emptyKeySet_returnsEmptyList() {
        when(jedis.smembers("apikeys:t1")).thenReturn(Collections.emptySet());

        List<ApiKey> result = repository.list("t1", null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_convenienceMethod_callsFullListWithDefaults() {
        when(jedis.smembers("apikeys:t1")).thenReturn(Collections.emptySet());

        List<ApiKey> result = repository.list("t1");

        assertThat(result).isEmpty();
        verify(jedis).smembers("apikeys:t1");
    }

    @Test
    void revoke_alreadyRevoked_returnsRevokedKey() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyHash("hash")
                .status(ApiKeyStatus.REVOKED).revokedAt(Instant.now())
                .createdAt(Instant.now()).build();
        String revokedJson = objectMapper.writeValueAsString(revoked);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("ALREADY_REVOKED", revokedJson));

        ApiKey result = repository.revoke("key_1", "reason");

        assertThat(result.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
    }

    @Test
    void create_withCustomPermissions_usesProvidedPermissions() throws Exception {
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix(anyString())).thenReturn("cyc_live_abc12");
        when(keyService.hashKey(anyString())).thenReturn("$2a$12$hash");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CREATED"));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Test Key");
        request.setPermissions(List.of("reservations:create"));

        ApiKeyCreateResponse response = repository.create(request);

        assertThat(response.getPermissions()).containsExactly("reservations:create");
    }

    @Test
    void create_withoutExpiry_usesDefault90Days() throws Exception {
        when(keyService.generateKeySecret("cyc_live")).thenReturn("cyc_live_abc123def456ghi");
        when(keyService.extractPrefix(anyString())).thenReturn("cyc_live_abc12");
        when(keyService.hashKey(anyString())).thenReturn("$2a$12$hash");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CREATED"));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Test Key");

        ApiKeyCreateResponse response = repository.create(request);

        assertThat(response.getExpiresAt()).isAfter(Instant.now().plusSeconds(86400 * 89));
        assertThat(response.getExpiresAt()).isBefore(Instant.now().plusSeconds(86400 * 91));
    }

    @Test
    void validate_keyDataNullAfterLookup_returnsKeyNotFound() {
        when(keyService.extractPrefix("cyc_live_test")).thenReturn("cyc_live_test1");
        when(jedis.get("apikey:lookup:cyc_live_test1")).thenReturn("key_123");
        when(jedis.get("apikey:key_123")).thenReturn(null);

        ApiKeyValidationResponse response = repository.validate("cyc_live_test");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void validate_blankTenantId_returnsNotOwnedByTenant() throws Exception {
        when(keyService.extractPrefix("cyc_live_blank")).thenReturn("cyc_live_blank");
        when(jedis.get("apikey:lookup:cyc_live_blank")).thenReturn("key_b");

        ApiKey key = ApiKey.builder()
                .keyId("key_b").tenantId("").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_b")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_blank", "$2a$12$hash")).thenReturn(true);

        ApiKeyValidationResponse response = repository.validate("cyc_live_blank");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_NOT_OWNED_BY_TENANT");
    }

    @Test
    void validate_nullPermissions_coercedToEmptyList() throws Exception {
        when(keyService.extractPrefix("cyc_live_noperm")).thenReturn("cyc_live_noper");
        when(jedis.get("apikey:lookup:cyc_live_noper")).thenReturn("key_np");

        ApiKey key = ApiKey.builder()
                .keyId("key_np").tenantId("t1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE).permissions(null)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_np")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_noperm", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:t1")).thenReturn("{\"status\":\"ACTIVE\"}");

        ApiKeyValidationResponse response = repository.validate("cyc_live_noperm");

        assertThat(response.getValid()).isTrue();
        assertThat(response.getPermissions()).isEmpty();
    }

    @Test
    void validate_internalException_returnsInternalError() {
        when(keyService.extractPrefix(anyString())).thenThrow(new RuntimeException("Redis down"));

        ApiKeyValidationResponse response = repository.validate("cyc_live_test");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void create_genericException_wrappedInRuntimeException() {
        when(keyService.generateKeySecret("cyc_live")).thenThrow(new RuntimeException("Key generation failed"));

        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("t1");
        request.setName("Test Key");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void list_deserializationFailure_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:t1")).thenReturn(ids);

        when(jedis.get("apikey:key_1")).thenReturn("{invalid json}");
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("t1").keyHash("h2").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("t1", null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_2");
    }

    @Test
    void revoke_genericException_wrappedInRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.revoke("key_1", "reason"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void validate_tenantDataNull_returnsValid() throws Exception {
        when(keyService.extractPrefix("cyc_live_nodata")).thenReturn("cyc_live_nodat");
        when(jedis.get("apikey:lookup:cyc_live_nodat")).thenReturn("key_nd");

        ApiKey key = ApiKey.builder()
                .keyId("key_nd").tenantId("t1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_nd")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_nodata", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:t1")).thenReturn(null);

        ApiKeyValidationResponse response = repository.validate("cyc_live_nodata");

        assertThat(response.getValid()).isTrue();
        assertThat(response.getTenantId()).isEqualTo("t1");
    }

    @Test
    void revoke_withNullReason_passesEmptyString() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("t1").keyHash("hash")
                .status(ApiKeyStatus.REVOKED)
                .revokedAt(Instant.now()).createdAt(Instant.now()).build();
        String revokedJson = objectMapper.writeValueAsString(revoked);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", revokedJson));

        ApiKey result = repository.revoke("key_1", null);

        assertThat(result.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        verify(jedis).eval(anyString(), anyList(), argThat(args -> args.contains("")));
    }

    @Test
    void validate_activeKeyWithNullExpiresAt_skipsExpirationCheck() throws Exception {
        when(keyService.extractPrefix("cyc_live_noexp")).thenReturn("cyc_live_noexp");
        when(jedis.get("apikey:lookup:cyc_live_noexp")).thenReturn("key_ne");

        ApiKey key = ApiKey.builder()
                .keyId("key_ne").tenantId("t1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE).expiresAt(null)
                .permissions(List.of("balances:read"))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_ne")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_noexp", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:t1")).thenReturn("{\"status\":\"ACTIVE\"}");

        ApiKeyValidationResponse response = repository.validate("cyc_live_noexp");

        assertThat(response.getValid()).isTrue();
        assertThat(response.getTenantId()).isEqualTo("t1");
    }

    @Test
    void validate_revokedKeyWithNullTenantId_returnsEmptyTenantId() throws Exception {
        when(keyService.extractPrefix("cyc_live_revnull")).thenReturn("cyc_live_revnu");
        when(jedis.get("apikey:lookup:cyc_live_revnu")).thenReturn("key_rn");

        ApiKey key = ApiKey.builder()
                .keyId("key_rn").tenantId(null).keyHash("hash")
                .status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_rn")).thenReturn(keyJson);

        ApiKeyValidationResponse response = repository.validate("cyc_live_revnull");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_REVOKED");
        assertThat(response.getTenantId()).isEmpty();
    }

    @Test
    void validate_expiredKeyWithNullTenantId_returnsEmptyTenantId() throws Exception {
        when(keyService.extractPrefix("cyc_live_expnull")).thenReturn("cyc_live_expnu");
        when(jedis.get("apikey:lookup:cyc_live_expnu")).thenReturn("key_en");

        ApiKey key = ApiKey.builder()
                .keyId("key_en").tenantId(null).keyHash("hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_en")).thenReturn(keyJson);

        ApiKeyValidationResponse response = repository.validate("cyc_live_expnull");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_EXPIRED");
        assertThat(response.getTenantId()).isEmpty();
    }

    @Test
    void validate_invalidKeyWithNullTenantId_returnsEmptyTenantId() throws Exception {
        when(keyService.extractPrefix("cyc_live_invnull")).thenReturn("cyc_live_invnu");
        when(jedis.get("apikey:lookup:cyc_live_invnu")).thenReturn("key_in");

        ApiKey key = ApiKey.builder()
                .keyId("key_in").tenantId(null).keyHash("$2a$12$real")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_in")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_invnull", "$2a$12$real")).thenReturn(false);

        ApiKeyValidationResponse response = repository.validate("cyc_live_invnull");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("INVALID_KEY");
        assertThat(response.getTenantId()).isEmpty();
    }

    @Test
    void list_cursorNotFound_returnsNoEntriesAfterCursor() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:t1")).thenReturn(ids);

        // Cursor points to nonexistent key - nothing comes after it
        List<ApiKey> result = repository.list("t1", null, "nonexistent", 50);

        assertThat(result).isEmpty();
    }

    @Test
    void validate_nullTenantId_returnsNotOwnedByTenant() throws Exception {
        when(keyService.extractPrefix("cyc_live_nulltid")).thenReturn("cyc_live_nullt");
        when(jedis.get("apikey:lookup:cyc_live_nullt")).thenReturn("key_nt");

        ApiKey key = ApiKey.builder()
                .keyId("key_nt").tenantId(null).keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_nt")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_nulltid", "$2a$12$hash")).thenReturn(true);

        ApiKeyValidationResponse response = repository.validate("cyc_live_nulltid");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_NOT_OWNED_BY_TENANT");
    }
}
