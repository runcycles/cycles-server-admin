package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.service.KeyService;
import io.runcycles.admin.model.auth.*;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
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
                .keyId("key_123").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("$2a$12$hash").status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_123")).thenReturn(keyJson);

        when(keyService.verifyKey(keySecret, "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:tenant-1")).thenReturn("{\"status\":\"ACTIVE\"}");

        ApiKeyValidationResponse response = repository.validate(keySecret);

        assertThat(response.getValid()).isTrue();
        assertThat(response.getTenantId()).isEqualTo("tenant-1");
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
                .keyId("key_rev").tenantId("tenant-1").keyHash("hash")
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
                .keyId("key_exp").tenantId("tenant-1").keyHash("hash")
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
                .keyId("key_w").tenantId("tenant-1").keyHash("$2a$12$real")
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
                .keyId("key_s").tenantId("tenant-1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_s")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_susp", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:tenant-1")).thenReturn("{\"status\":\"SUSPENDED\"}");

        ApiKeyValidationResponse response = repository.validate("cyc_live_susp");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("TENANT_SUSPENDED");
    }

    @Test
    void validate_closedTenant_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_closed")).thenReturn("cyc_live_close");
        when(jedis.get("apikey:lookup:cyc_live_close")).thenReturn("key_c");

        ApiKey key = ApiKey.builder()
                .keyId("key_c").tenantId("tenant-1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_c")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_closed", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:tenant-1")).thenReturn("{\"status\":\"CLOSED\"}");

        ApiKeyValidationResponse response = repository.validate("cyc_live_closed");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("TENANT_CLOSED");
    }

    @Test
    void revoke_existingKey_setsRevokedStatus() throws Exception {
        ApiKey active = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyHash("hash")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String activeJson = objectMapper.writeValueAsString(active);
        when(jedis.get("apikey:key_1")).thenReturn(activeJson);

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
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);

        ApiKey k1 = ApiKey.builder().keyId("key_1").tenantId("tenant-1").keyHash("h1").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("tenant-1").keyHash("h2").status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build();
        String k1Json = objectMapper.writeValueAsString(k1);
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_1")).thenReturn(k1Json);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("tenant-1", null, null, 50);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_filtersKeysByStatus() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);

        ApiKey k1 = ApiKey.builder().keyId("key_1").tenantId("tenant-1").keyHash("h1").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("tenant-1").keyHash("h2").status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build();
        String k1Json = objectMapper.writeValueAsString(k1);
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_1")).thenReturn(k1Json);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("tenant-1", ApiKeyStatus.ACTIVE, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_1");
    }

    @Test
    void list_cursorPagination_skipsEntriesBeforeCursor() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2", "key_3"));
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);

        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("tenant-1").keyHash("h2").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey k3 = ApiKey.builder().keyId("key_3").tenantId("tenant-1").keyHash("h3").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k2Json = objectMapper.writeValueAsString(k2);
        String k3Json = objectMapper.writeValueAsString(k3);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);
        when(jedis.get("apikey:key_3")).thenReturn(k3Json);

        List<ApiKey> result = repository.list("tenant-1", null, "key_1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_2");
    }

    @Test
    void list_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2", "key_3"));
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);

        ApiKey k1 = ApiKey.builder().keyId("key_1").tenantId("tenant-1").keyHash("h1").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k1Json = objectMapper.writeValueAsString(k1);
        when(jedis.get("apikey:key_1")).thenReturn(k1Json);

        List<ApiKey> result = repository.list("tenant-1", null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_missingKeyData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);

        when(jedis.get("apikey:key_1")).thenReturn(null);
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("tenant-1").keyHash("h2").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("tenant-1", null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_2");
    }

    @Test
    void list_emptyKeySet_returnsEmptyList() {
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(Collections.emptySet());

        List<ApiKey> result = repository.list("tenant-1", null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_convenienceMethod_callsFullListWithDefaults() {
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(Collections.emptySet());

        List<ApiKey> result = repository.list("tenant-1");

        assertThat(result).isEmpty();
        verify(jedis).smembers("apikeys:tenant-1");
    }

    @Test
    void revoke_alreadyRevoked_throws409() throws Exception {
        // Spec (cycles-governance-admin-v0.1.25.yaml → revokeApiKey) requires
        // 409 ALREADY_REVOKED when the key is already revoked. The old Lua path
        // returned 200 with the stored record, which was a pre-existing spec
        // violation; v0.1.25.17 corrects it.
        ApiKey revoked = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyHash("hash")
                .status(ApiKeyStatus.REVOKED)
                .revokedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .revokedReason("original reason")
                .createdAt(Instant.now()).build();
        String revokedJson = objectMapper.writeValueAsString(revoked);
        when(jedis.get("apikey:key_1")).thenReturn(revokedJson);

        assertThatThrownBy(() -> repository.revoke("key_1", "different reason"))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.KEY_REVOKED);
                    assertThat(ge.getMessage()).contains("already revoked");
                });
        // No write — the stored record is untouched.
        verify(jedis, never()).set(anyString(), anyString());
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
                .keyId("key_np").tenantId("tenant-1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE).permissions(null)
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_np")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_noperm", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:tenant-1")).thenReturn("{\"status\":\"ACTIVE\"}");

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
        request.setTenantId("tenant-1");
        request.setName("Test Key");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void list_deserializationFailure_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("key_1", "key_2"));
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);

        when(jedis.get("apikey:key_1")).thenReturn("{invalid json}");
        ApiKey k2 = ApiKey.builder().keyId("key_2").tenantId("tenant-1").keyHash("h2").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String k2Json = objectMapper.writeValueAsString(k2);
        when(jedis.get("apikey:key_2")).thenReturn(k2Json);

        List<ApiKey> result = repository.list("tenant-1", null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_2");
    }

    @Test
    void revoke_genericException_wrappedInRuntimeException() {
        when(jedis.get("apikey:key_1")).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.revoke("key_1", "reason"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void validate_tenantDataNull_returnsInvalid() throws Exception {
        when(keyService.extractPrefix("cyc_live_nodata")).thenReturn("cyc_live_nodat");
        when(jedis.get("apikey:lookup:cyc_live_nodat")).thenReturn("key_nd");

        ApiKey key = ApiKey.builder()
                .keyId("key_nd").tenantId("tenant-1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_nd")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_nodata", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:tenant-1")).thenReturn(null);

        ApiKeyValidationResponse response = repository.validate("cyc_live_nodata");

        assertThat(response.getValid()).isFalse();
        assertThat(response.getTenantId()).isEqualTo("tenant-1");
        assertThat(response.getReason()).isEqualTo("TENANT_NOT_FOUND");
    }

    @Test
    void revoke_withNullReason_leavesRevokedReasonUnset() throws Exception {
        ApiKey active = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyHash("hash")
                .status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String activeJson = objectMapper.writeValueAsString(active);
        when(jedis.get("apikey:key_1")).thenReturn(activeJson);

        ApiKey result = repository.revoke("key_1", null);

        assertThat(result.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(result.getRevokedReason()).isNull();
        assertThat(result.getRevokedAt()).isNotNull();
    }

    @Test
    void list_legacyCorruptedEmptyArrays_stillReadable() throws Exception {
        // Pre-v0.1.25.17 records revoked via the old Lua cjson round-trip had
        // scope_filter and permissions rewritten as {} instead of []. The
        // lenient deserializer on ApiKey must accept those records so the
        // admin list endpoint doesn't silently drop them (cycles-dashboard#43).
        Set<String> ids = new LinkedHashSet<>(List.of("key_corrupt"));
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);
        String corruptedJson = "{"
                + "\"key_id\":\"key_corrupt\","
                + "\"tenant_id\":\"tenant-1\","
                + "\"key_hash\":\"h\","
                + "\"permissions\":{},"
                + "\"scope_filter\":{},"
                + "\"status\":\"REVOKED\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\""
                + "}";
        when(jedis.get("apikey:key_corrupt")).thenReturn(corruptedJson);

        List<ApiKey> result = repository.list("tenant-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_corrupt");
        assertThat(result.get(0).getPermissions()).isEmpty();
        assertThat(result.get(0).getScopeFilter()).isEmpty();
    }

    @Test
    void validate_activeKeyWithNullExpiresAt_skipsExpirationCheck() throws Exception {
        when(keyService.extractPrefix("cyc_live_noexp")).thenReturn("cyc_live_noexp");
        when(jedis.get("apikey:lookup:cyc_live_noexp")).thenReturn("key_ne");

        ApiKey key = ApiKey.builder()
                .keyId("key_ne").tenantId("tenant-1").keyHash("$2a$12$hash")
                .status(ApiKeyStatus.ACTIVE).expiresAt(null)
                .permissions(List.of("balances:read"))
                .createdAt(Instant.now()).build();
        String keyJson = objectMapper.writeValueAsString(key);
        when(jedis.get("apikey:key_ne")).thenReturn(keyJson);
        when(keyService.verifyKey("cyc_live_noexp", "$2a$12$hash")).thenReturn(true);
        when(jedis.get("tenant:tenant-1")).thenReturn("{\"status\":\"ACTIVE\"}");

        ApiKeyValidationResponse response = repository.validate("cyc_live_noexp");

        assertThat(response.getValid()).isTrue();
        assertThat(response.getTenantId()).isEqualTo("tenant-1");
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
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(ids);

        // Cursor points to nonexistent key - nothing comes after it
        List<ApiKey> result = repository.list("tenant-1", null, "nonexistent", 50);

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

    // ========== update ==========

    @Test
    void update_success_returnsUpdatedKey() throws Exception {
        ApiKey existing = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("$2a$12$hash").status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        String existingJson = objectMapper.writeValueAsString(existing);
        when(jedis.get("apikey:key_1")).thenReturn(existingJson);

        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest();
        request.setName("Updated");
        request.setPermissions(List.of("budgets:read", "budgets:write"));

        ApiKey result = repository.update("key_1", request);

        assertThat(result.getName()).isEqualTo("Updated");
        assertThat(result.getPermissions()).containsExactly("budgets:read", "budgets:write");
        verify(jedis).set(eq("apikey:key_1"), anyString());
    }

    @Test
    void update_unknownPermission_throws400WithSpecificValue() throws Exception {
        // v0.1.25.17: raw-string permissions surface an actionable 400
        // naming the exact bad value (replaces Jackson's opaque
        // "Malformed request body" that the strict enum used to produce).
        ApiKey existing = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("$2a$12$hash").status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        String existingJson = objectMapper.writeValueAsString(existing);
        when(jedis.get("apikey:key_1")).thenReturn(existingJson);

        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest();
        // Typo on "write" — dashboard round-tripping legacy data can hit this
        request.setPermissions(List.of("budgets:read", "budgets:wirte"));

        assertThatThrownBy(() -> repository.update("key_1", request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(400);
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getMessage()).contains("budgets:wirte");
                });
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void create_unknownPermission_throws400WithSpecificValue() {
        // Permission validation runs before any key generation, so no
        // keyService stubbing is needed here.
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Test Key");
        request.setPermissions(List.of("admin:apikey:read")); // singular typo — enum has "apikeys"

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(400);
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getMessage()).contains("admin:apikey:read");
                });
    }

    @Test
    void update_notFound_throws404() {
        when(jedis.get("apikey:missing")).thenReturn(null);

        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest();
        request.setName("test");

        assertThatThrownBy(() -> repository.update("missing", request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
    }

    @Test
    void update_revokedKey_throws409() throws Exception {
        ApiKey revoked = ApiKey.builder()
                .keyId("key_rev").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("hash").status(ApiKeyStatus.REVOKED)
                .createdAt(Instant.now()).build();
        String revokedJson = objectMapper.writeValueAsString(revoked);
        when(jedis.get("apikey:key_rev")).thenReturn(revokedJson);

        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest();
        request.setName("test");

        assertThatThrownBy(() -> repository.update("key_rev", request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void update_expiredKey_throws409() throws Exception {
        ApiKey expired = ApiKey.builder()
                .keyId("key_exp").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("hash").status(ApiKeyStatus.EXPIRED)
                .createdAt(Instant.now()).build();
        String expiredJson = objectMapper.writeValueAsString(expired);
        when(jedis.get("apikey:key_exp")).thenReturn(expiredJson);

        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest();
        request.setName("test");

        assertThatThrownBy(() -> repository.update("key_exp", request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void update_onlyName_doesNotModifyOtherFields() throws Exception {
        ApiKey existing = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("hash").name("Old Name").status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read")).scopeFilter(List.of("workspace:eng"))
                .createdAt(Instant.now()).build();
        String existingJson = objectMapper.writeValueAsString(existing);
        when(jedis.get("apikey:key_1")).thenReturn(existingJson);

        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest();
        request.setName("New Name");

        ApiKey result = repository.update("key_1", request);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getPermissions()).containsExactly("balances:read");
        assertThat(result.getScopeFilter()).containsExactly("workspace:eng");
    }

    // --- v0.1.25.22 cross-tenant listAllTenants ---

    private ApiKey buildKey(String tenantId, String keyId) throws Exception {
        return ApiKey.builder()
                .keyId(keyId).tenantId(tenantId).keyPrefix("cyc_live_" + keyId)
                .keyHash("hash").status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400))
                .build();
    }

    @Test
    void listAllTenants_noCursor_walksEveryTenantInSortedOrder() throws Exception {
        String jsonA1 = objectMapper.writeValueAsString(buildKey("tenant-a", "key_a1"));
        String jsonA2 = objectMapper.writeValueAsString(buildKey("tenant-a", "key_a2"));
        String jsonB1 = objectMapper.writeValueAsString(buildKey("tenant-b", "key_b1"));
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-b", "tenant-a")));
        when(jedis.smembers("apikeys:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("key_a1", "key_a2")));
        when(jedis.smembers("apikeys:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("key_b1")));
        when(jedis.get("apikey:key_a1")).thenReturn(jsonA1);
        when(jedis.get("apikey:key_a2")).thenReturn(jsonA2);
        when(jedis.get("apikey:key_b1")).thenReturn(jsonB1);

        List<ApiKey> result = repository.listAllTenants(null, null, 50);

        // Sorted tenants → tenant-a, tenant-b; within each tenant, sorted keys.
        assertThat(result).extracting(ApiKey::getTenantId)
                .containsExactly("tenant-a", "tenant-a", "tenant-b");
        assertThat(result).extracting(ApiKey::getKeyId)
                .containsExactly("key_a1", "key_a2", "key_b1");
    }

    @Test
    void listAllTenants_withCursor_resumesInsideMatchingTenant() throws Exception {
        String jsonA1 = objectMapper.writeValueAsString(buildKey("tenant-a", "key_a1"));
        String jsonA2 = objectMapper.writeValueAsString(buildKey("tenant-a", "key_a2"));
        String jsonB1 = objectMapper.writeValueAsString(buildKey("tenant-b", "key_b1"));
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        when(jedis.smembers("apikeys:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("key_a1", "key_a2")));
        when(jedis.smembers("apikeys:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("key_b1")));
        lenient().when(jedis.get("apikey:key_a1")).thenReturn(jsonA1);
        lenient().when(jedis.get("apikey:key_a2")).thenReturn(jsonA2);
        lenient().when(jedis.get("apikey:key_b1")).thenReturn(jsonB1);

        // Cursor = "tenant-a|key_a1" → resume strictly after key_a1 within tenant-a.
        List<ApiKey> result = repository.listAllTenants(null, "tenant-a|key_a1", 50);

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_a2", "key_b1");
    }

    @Test
    void listAllTenants_bareCursor_resumesAtStartOfCursorTenant() throws Exception {
        String jsonB1 = objectMapper.writeValueAsString(buildKey("tenant-b", "key_b1"));
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        when(jedis.smembers("apikeys:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("key_b1")));
        lenient().when(jedis.get("apikey:key_b1")).thenReturn(jsonB1);

        // Cursor with no "|" means "start at the first key of the cursor tenant".
        List<ApiKey> result = repository.listAllTenants(null, "tenant-b", 50);

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_b1");
    }

    @Test
    void listAllTenants_statusFilter_excludesNonMatching() throws Exception {
        ApiKey active = buildKey("tenant-a", "key_active");
        ApiKey revoked = ApiKey.builder().keyId("key_revoked").tenantId("tenant-a")
                .keyPrefix("cyc_live_r").keyHash("h").status(ApiKeyStatus.REVOKED)
                .createdAt(Instant.now()).build();
        String jsonActive = objectMapper.writeValueAsString(active);
        String jsonRevoked = objectMapper.writeValueAsString(revoked);
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a")));
        when(jedis.smembers("apikeys:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("key_active", "key_revoked")));
        when(jedis.get("apikey:key_active")).thenReturn(jsonActive);
        when(jedis.get("apikey:key_revoked")).thenReturn(jsonRevoked);

        List<ApiKey> result = repository.listAllTenants(ApiKeyStatus.REVOKED, null, 50);

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_revoked");
    }

    @Test
    void listAllTenants_respectsLimit_stopsAtBoundary() throws Exception {
        String jsonA1 = objectMapper.writeValueAsString(buildKey("tenant-a", "key_a1"));
        String jsonA2 = objectMapper.writeValueAsString(buildKey("tenant-a", "key_a2"));
        String jsonB1 = objectMapper.writeValueAsString(buildKey("tenant-b", "key_b1"));
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        when(jedis.smembers("apikeys:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("key_a1", "key_a2")));
        lenient().when(jedis.smembers("apikeys:tenant-b"))
                .thenReturn(new LinkedHashSet<>(List.of("key_b1")));
        lenient().when(jedis.get("apikey:key_a1")).thenReturn(jsonA1);
        lenient().when(jedis.get("apikey:key_a2")).thenReturn(jsonA2);
        lenient().when(jedis.get("apikey:key_b1")).thenReturn(jsonB1);

        List<ApiKey> result = repository.listAllTenants(null, null, 2);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_a1", "key_a2");
    }

    @Test
    void listAllTenants_missingKeyData_skipsGracefully() throws Exception {
        String jsonA1 = objectMapper.writeValueAsString(buildKey("tenant-a", "key_a1"));
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a")));
        when(jedis.smembers("apikeys:tenant-a"))
                .thenReturn(new LinkedHashSet<>(List.of("key_a1", "key_missing")));
        when(jedis.get("apikey:key_a1")).thenReturn(jsonA1);
        when(jedis.get("apikey:key_missing")).thenReturn(null);

        List<ApiKey> result = repository.listAllTenants(null, null, 50);

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_a1");
    }

    @Test
    void listAllTenants_emptyTenantsSet_returnsEmptyList() {
        when(jedis.smembers("tenants")).thenReturn(Collections.emptySet());

        List<ApiKey> result = repository.listAllTenants(null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void listAllTenants_cursorTenantDeleted_skipsForwardToNextTenant() throws Exception {
        // Cursor points at tenant-b, but tenant-b has been deleted between pages.
        // Must not stall at empty: should resume at tenant-c (sorts strictly after).
        String jsonC1 = objectMapper.writeValueAsString(buildKey("tenant-c", "key_c1"));
        String jsonC2 = objectMapper.writeValueAsString(buildKey("tenant-c", "key_c2"));
        when(jedis.smembers("tenants"))
                .thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-c")));
        when(jedis.smembers("apikeys:tenant-c"))
                .thenReturn(new LinkedHashSet<>(List.of("key_c1", "key_c2")));
        lenient().when(jedis.get("apikey:key_c1")).thenReturn(jsonC1);
        lenient().when(jedis.get("apikey:key_c2")).thenReturn(jsonC2);

        List<ApiKey> result = repository.listAllTenants(null, "tenant-b|key_b1", 50);

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_c1", "key_c2");
    }

    // --- v0.1.25.24 sort support ---

    private ApiKey buildKeySorted(String tenantId, String keyId, String name,
                                  ApiKeyStatus status, Instant createdAt, Instant expiresAt) {
        return ApiKey.builder()
                .keyId(keyId).tenantId(tenantId).name(name).keyPrefix("cyc_live_" + keyId)
                .keyHash("hash").status(status)
                .createdAt(createdAt).expiresAt(expiresAt)
                .build();
    }

    private void stubSingleTenantKeys(String tenantId, ApiKey... keys) throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> jsonByKey = new LinkedHashMap<>();
        for (ApiKey k : keys) {
            ids.add(k.getKeyId());
            jsonByKey.put("apikey:" + k.getKeyId(), objectMapper.writeValueAsString(k));
        }
        when(jedis.smembers("apikeys:" + tenantId)).thenReturn(ids);
        for (var e : jsonByKey.entrySet()) {
            when(jedis.get(e.getKey())).thenReturn(e.getValue());
        }
    }

    @Test
    void list_sortByNameAscending_ordersByName() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey zebra = buildKeySorted("t1", "key_2", "Zebra", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey alpha = buildKeySorted("t1", "key_1", "Alpha", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        stubSingleTenantKeys("t1", zebra, alpha);

        List<ApiKey> result = repository.list("t1", null, null, 50,
                SortSpec.of("name", SortDirection.ASC));

        assertThat(result).extracting(ApiKey::getName).containsExactly("Alpha", "Zebra");
    }

    @Test
    void list_sortByCreatedAtDescending_newestFirst() throws Exception {
        Instant older = Instant.parse("2025-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey old = buildKeySorted("t1", "key_1", "Old", ApiKeyStatus.ACTIVE, older, older.plusSeconds(3600));
        ApiKey fresh = buildKeySorted("t1", "key_2", "Fresh", ApiKeyStatus.ACTIVE, newer, newer.plusSeconds(3600));
        stubSingleTenantKeys("t1", old, fresh);

        List<ApiKey> result = repository.list("t1", null, null, 50,
                SortSpec.of("created_at", SortDirection.DESC));

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_2", "key_1");
    }

    @Test
    void list_sortByStatus_nullSafeWithFallback() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey revoked = buildKeySorted("t1", "key_1", "A", ApiKeyStatus.REVOKED, now, now.plusSeconds(3600));
        ApiKey active = buildKeySorted("t1", "key_2", "B", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        stubSingleTenantKeys("t1", revoked, active);

        List<ApiKey> result = repository.list("t1", null, null, 50,
                SortSpec.of("status", SortDirection.ASC));

        // ACTIVE < REVOKED lexicographically
        assertThat(result).extracting(ApiKey::getStatus)
                .containsExactly(ApiKeyStatus.ACTIVE, ApiKeyStatus.REVOKED);
    }

    @Test
    void list_sortByExpiresAt_ascending() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey later = buildKeySorted("t1", "key_b", "B", ApiKeyStatus.ACTIVE, now, now.plusSeconds(7200));
        ApiKey sooner = buildKeySorted("t1", "key_a", "A", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        stubSingleTenantKeys("t1", later, sooner);

        List<ApiKey> result = repository.list("t1", null, null, 50,
                SortSpec.of("expires_at", SortDirection.ASC));

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_a", "key_b");
    }

    @Test
    void list_sortedCursorResumesAfterSortKey() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey a = buildKeySorted("t1", "key_a", "Alpha", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey b = buildKeySorted("t1", "key_b", "Beta", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey c = buildKeySorted("t1", "key_c", "Charlie", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        stubSingleTenantKeys("t1", a, b, c);

        List<ApiKey> result = repository.list("t1", null, "key_a", 50,
                SortSpec.of("name", SortDirection.ASC));

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_b", "key_c");
    }

    @Test
    void list_unknownSortField_fallsBackToKeyId() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey b = buildKeySorted("t1", "key_b", "B", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey a = buildKeySorted("t1", "key_a", "A", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        stubSingleTenantKeys("t1", b, a);

        List<ApiKey> result = repository.list("t1", null, null, 50,
                new SortSpec("unknown_field", SortDirection.ASC));

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_a", "key_b");
    }

    @Test
    void list_nullSortSpec_preservesLegacyPath() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey b = buildKeySorted("t1", "key_b", "B", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey a = buildKeySorted("t1", "key_a", "A", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        stubSingleTenantKeys("t1", b, a);

        List<ApiKey> result = repository.list("t1", null, null, 50, null);

        // Legacy path sorts by raw keyId
        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_a", "key_b");
    }

    @Test
    void listAllTenants_sortedByName_globalOrder() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey a = buildKeySorted("tenant-a", "key_1", "Zebra", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey b = buildKeySorted("tenant-b", "key_2", "Alpha", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        String jsonA = objectMapper.writeValueAsString(a);
        String jsonB = objectMapper.writeValueAsString(b);
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        when(jedis.smembers("apikeys:tenant-a")).thenReturn(new LinkedHashSet<>(List.of("key_1")));
        when(jedis.smembers("apikeys:tenant-b")).thenReturn(new LinkedHashSet<>(List.of("key_2")));
        when(jedis.get("apikey:key_1")).thenReturn(jsonA);
        when(jedis.get("apikey:key_2")).thenReturn(jsonB);

        List<ApiKey> result = repository.listAllTenants(null, null, 50,
                SortSpec.of("name", SortDirection.ASC));

        // Alpha (tenant-b) < Zebra (tenant-a)
        assertThat(result).extracting(ApiKey::getTenantId).containsExactly("tenant-b", "tenant-a");
    }

    @Test
    void listAllTenants_sortedCursor_resumesAfterComposite() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey a = buildKeySorted("tenant-a", "key_1", "Alpha", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey b = buildKeySorted("tenant-b", "key_2", "Beta", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        ApiKey c = buildKeySorted("tenant-c", "key_3", "Charlie", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        String jsonA = objectMapper.writeValueAsString(a);
        String jsonB = objectMapper.writeValueAsString(b);
        String jsonC = objectMapper.writeValueAsString(c);
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-b", "tenant-c")));
        when(jedis.smembers("apikeys:tenant-a")).thenReturn(new LinkedHashSet<>(List.of("key_1")));
        when(jedis.smembers("apikeys:tenant-b")).thenReturn(new LinkedHashSet<>(List.of("key_2")));
        when(jedis.smembers("apikeys:tenant-c")).thenReturn(new LinkedHashSet<>(List.of("key_3")));
        when(jedis.get("apikey:key_1")).thenReturn(jsonA);
        when(jedis.get("apikey:key_2")).thenReturn(jsonB);
        when(jedis.get("apikey:key_3")).thenReturn(jsonC);

        List<ApiKey> result = repository.listAllTenants(null, "tenant-a|key_1", 50,
                SortSpec.of("name", SortDirection.ASC));

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_2", "key_3");
    }

    @Test
    void listAllTenants_nullSortSpec_preservesLegacySkipForward() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ApiKey c1 = buildKeySorted("tenant-c", "key_c1", "C", ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
        String jsonC1 = objectMapper.writeValueAsString(c1);
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a", "tenant-c")));
        when(jedis.smembers("apikeys:tenant-c")).thenReturn(new LinkedHashSet<>(List.of("key_c1")));
        lenient().when(jedis.get("apikey:key_c1")).thenReturn(jsonC1);

        // Null SortSpec → legacy path; cursor tenant-b is deleted → skip forward to tenant-c
        List<ApiKey> result = repository.listAllTenants(null, "tenant-b|key_b1", 50, null);

        assertThat(result).extracting(ApiKey::getKeyId).containsExactly("key_c1");
    }

    @Test
    void listAllTenants_sorted_stopsAtHydrationCap() throws Exception {
        // Hydrate SORTED_HYDRATE_CAP+10 keys under a single tenant; the sorted
        // cross-tenant path must stop hydrating at the cap and still return a
        // valid sorted page from the capped window. Page size 5 ensures the
        // page fills from the capped slice regardless of global population.
        int cap = ApiKeyRepository.SORTED_HYDRATE_CAP;
        int total = cap + 10;
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        LinkedHashSet<String> keyIds = new LinkedHashSet<>();
        Map<String, String> jsonById = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            String id = String.format("key_%05d", i);
            keyIds.add(id);
            ApiKey k = buildKeySorted("tenant-a", id, "Name-" + i, ApiKeyStatus.ACTIVE, now, now.plusSeconds(3600));
            // Serialize before stubbing so the spy isn't invoked inside a when() clause
            jsonById.put(id, objectMapper.writeValueAsString(k));
        }
        for (var e : jsonById.entrySet()) {
            lenient().when(jedis.get("apikey:" + e.getKey())).thenReturn(e.getValue());
        }
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("tenant-a")));
        when(jedis.smembers("apikeys:tenant-a")).thenReturn(keyIds);

        List<ApiKey> result = repository.listAllTenants(null, null, 5,
                SortSpec.of("key_id", SortDirection.ASC));

        // We never observe more than `cap` hydrations, so `jedis.get` is called at most `cap` times.
        // Page requested is 5 rows, delivered from the capped slice.
        assertThat(result).hasSize(5);
        verify(jedis, atMost(cap)).get(anyString());
    }

    // --- cascadeRevoke (spec v0.1.25.29 Rule 1) ---

    @Test
    void cascadeRevoke_noOwnedKeys_returnsEmpty() {
        when(jedis.smembers("apikeys:tenant-1")).thenReturn(Collections.emptySet());

        List<ApiKeyRepository.CascadeRevokeOutcome> outcomes = repository.cascadeRevoke("tenant-1", "tenant_closed");

        assertThat(outcomes).isEmpty();
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void cascadeRevoke_mixedStatuses_revokesOnlyActive() throws Exception {
        ApiKey active1 = ApiKey.builder().keyId("key_1").tenantId("tenant-1").name("ci").keyPrefix("cyc_live_1")
            .keyHash("h").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey revoked = ApiKey.builder().keyId("key_2").tenantId("tenant-1").name("old").keyPrefix("cyc_live_2")
            .keyHash("h").status(ApiKeyStatus.REVOKED).createdAt(Instant.now()).build();
        ApiKey expired = ApiKey.builder().keyId("key_3").tenantId("tenant-1").name("stale").keyPrefix("cyc_live_3")
            .keyHash("h").status(ApiKeyStatus.EXPIRED).createdAt(Instant.now()).build();
        // Pre-compute JSON outside when(...) — Jackson invocations against the
        // @Spy objectMapper inside a stubbing expression trip Mockito's
        // UnfinishedStubbingException detector.
        String json1 = objectMapper.writeValueAsString(active1);
        String json2 = objectMapper.writeValueAsString(revoked);
        String json3 = objectMapper.writeValueAsString(expired);
        when(jedis.smembers("apikeys:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("key_1", "key_2", "key_3")));
        when(jedis.get("apikey:key_1")).thenReturn(json1);
        when(jedis.get("apikey:key_2")).thenReturn(json2);
        when(jedis.get("apikey:key_3")).thenReturn(json3);

        List<ApiKeyRepository.CascadeRevokeOutcome> outcomes = repository.cascadeRevoke("tenant-1", "tenant_closed");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).keyId()).isEqualTo("key_1");
        assertThat(outcomes.get(0).priorStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(outcomes.get(0).name()).isEqualTo("ci");
        assertThat(outcomes.get(0).toString()).contains("key_1");

        verify(jedis).set(eq("apikey:key_1"), anyString());
        verify(jedis, never()).set(eq("apikey:key_2"), anyString());
        verify(jedis, never()).set(eq("apikey:key_3"), anyString());
    }

    @Test
    void cascadeRevoke_nullReason_skipsReasonUpdate() throws Exception {
        ApiKey active = ApiKey.builder().keyId("key_1").tenantId("tenant-1").name("ci").keyPrefix("cyc_live_1")
            .keyHash("h").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String json = objectMapper.writeValueAsString(active);
        when(jedis.smembers("apikeys:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("key_1")));
        when(jedis.get("apikey:key_1")).thenReturn(json);

        List<ApiKeyRepository.CascadeRevokeOutcome> outcomes = repository.cascadeRevoke("tenant-1", null);

        assertThat(outcomes).hasSize(1);
    }

    @Test
    void cascadeRevoke_emptyReason_skipsReasonUpdate() throws Exception {
        ApiKey active = ApiKey.builder().keyId("key_1").tenantId("tenant-1").name("ci").keyPrefix("cyc_live_1")
            .keyHash("h").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String json = objectMapper.writeValueAsString(active);
        when(jedis.smembers("apikeys:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("key_1")));
        when(jedis.get("apikey:key_1")).thenReturn(json);

        List<ApiKeyRepository.CascadeRevokeOutcome> outcomes = repository.cascadeRevoke("tenant-1", "");

        assertThat(outcomes).hasSize(1);
    }

    @Test
    void cascadeRevoke_serializationException_skipsRowAndContinues() throws Exception {
        ApiKey active1 = ApiKey.builder().keyId("key_1").tenantId("tenant-1").name("bad").keyPrefix("cyc_live_1")
            .keyHash("h").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        ApiKey active2 = ApiKey.builder().keyId("key_2").tenantId("tenant-1").name("good").keyPrefix("cyc_live_2")
            .keyHash("h").status(ApiKeyStatus.ACTIVE).createdAt(Instant.now()).build();
        String json1 = objectMapper.writeValueAsString(active1);
        String json2 = objectMapper.writeValueAsString(active2);
        when(jedis.smembers("apikeys:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("key_1", "key_2")));
        when(jedis.get("apikey:key_1")).thenReturn(json1);
        when(jedis.get("apikey:key_2")).thenReturn(json2);
        when(jedis.set(eq("apikey:key_1"), anyString()))
            .thenThrow(new RuntimeException("redis down"));
        when(jedis.set(eq("apikey:key_2"), anyString())).thenReturn("OK");

        List<ApiKeyRepository.CascadeRevokeOutcome> outcomes = repository.cascadeRevoke("tenant-1", "tenant_closed");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).keyId()).isEqualTo("key_2");
    }
}
