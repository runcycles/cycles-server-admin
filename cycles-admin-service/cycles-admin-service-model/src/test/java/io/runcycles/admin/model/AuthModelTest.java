package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.auth.ApiKey;
import io.runcycles.admin.model.auth.ApiKeyCreateRequest;
import io.runcycles.admin.model.auth.ApiKeyResponse;
import io.runcycles.admin.model.auth.ApiKeyStatus;
import io.runcycles.admin.model.auth.Permission;
import jakarta.validation.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthModelTest {

    private static Validator validator;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void apiKeyCreateRequestValidation() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("tenant-1");
        request.setName("my-key");
        request.setPermissions(List.of(Permission.BALANCES_READ.getValue(), Permission.BUDGETS_WRITE.getValue()));
        request.setExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));

        Set<ConstraintViolation<ApiKeyCreateRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Expected no violations but got: " + violations);
    }

    @Test
    void apiKeyCreateRequestMissingRequiredFields() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();

        Set<ConstraintViolation<ApiKeyCreateRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
        // tenantId and name are @NotBlank
        assertTrue(violations.size() >= 2,
                "Expected at least 2 violations but got " + violations.size());
    }

    @Test
    void apiKeyCreateRequestSerializationRoundTrip() throws Exception {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("tenant-1");
        request.setName("test-key");
        request.setDescription("A test key");
        request.setScopeFilter(List.of("scope-a", "scope-b"));
        request.setMetadata(Map.of("created_by", "test"));

        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"tenant_id\""));
        assertTrue(json.contains("\"scope_filter\""));

        ApiKeyCreateRequest deserialized = mapper.readValue(json, ApiKeyCreateRequest.class);
        assertEquals(request.getTenantId(), deserialized.getTenantId());
        assertEquals(request.getName(), deserialized.getName());
        assertEquals(request.getScopeFilter(), deserialized.getScopeFilter());
    }

    @Test
    void apiKeyStatusEnumValues() {
        ApiKeyStatus[] values = ApiKeyStatus.values();
        assertEquals(3, values.length);
        assertNotNull(ApiKeyStatus.valueOf("ACTIVE"));
        assertNotNull(ApiKeyStatus.valueOf("REVOKED"));
        assertNotNull(ApiKeyStatus.valueOf("EXPIRED"));
    }

    @Test
    void apiKeyCreateRequest_rejectsUnknownFields() {
        String json = """
            {"tenant_id":"tenant-1","name":"k","unknown":true}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, ApiKeyCreateRequest.class));
    }

    @Test
    void apiKeyCreateRequest_nameTooLong_fails() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("tenant-1");
        request.setName("x".repeat(257));
        Set<ConstraintViolation<ApiKeyCreateRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void apiKeyCreateRequest_unknownPermission_acceptedAtDeserialization() throws Exception {
        // v0.1.25.17: permissions are now List<String> at the DTO level —
        // unknown values no longer fail Jackson deserialization. Validation
        // happens in the repository via Permission.findUnknown, which throws
        // GovernanceException(400) with a message naming the offender.
        String json = """
            {"tenant_id":"tenant-1","name":"k","permissions":["budgets:wirte"]}
            """;
        ApiKeyCreateRequest req = mapper.readValue(json, ApiKeyCreateRequest.class);
        assertEquals(List.of("budgets:wirte"), req.getPermissions());
        assertEquals("budgets:wirte", Permission.findUnknown(req.getPermissions()));
    }

    @Test
    void apiKeyCreateRequest_validPermissions_deserialize() throws Exception {
        String json = """
            {"tenant_id":"tenant-1","name":"k","permissions":["budgets:read","admin:audit:read"]}
            """;
        ApiKeyCreateRequest req = mapper.readValue(json, ApiKeyCreateRequest.class);
        assertEquals(List.of("budgets:read", "admin:audit:read"), req.getPermissions());
        assertNull(Permission.findUnknown(req.getPermissions()));
    }

    @Test
    void apiKeyCreateRequest_descriptionTooLong_fails() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("tenant-1");
        request.setName("valid");
        request.setDescription("x".repeat(1025));
        Set<ConstraintViolation<ApiKeyCreateRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    // --- ApiKeyResponse never exposes key_hash ---

    @Test
    void apiKeyResponse_doesNotContainKeyHash() throws Exception {
        ApiKey key = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("$2a$12$secret_hash_value")
                .name("test").status(ApiKeyStatus.ACTIVE)
                .permissions(List.of("balances:read"))
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();

        ApiKeyResponse response = ApiKeyResponse.from(key);
        String json = mapper.writeValueAsString(response);
        assertFalse(json.contains("key_hash"), "ApiKeyResponse must never expose key_hash");
        assertFalse(json.contains("secret_hash"), "ApiKeyResponse must never expose hash contents");
        assertTrue(json.contains("\"key_id\""));
        assertTrue(json.contains("\"key_prefix\""));
    }

    @Test
    void apiKey_internalModel_containsKeyHashForRedis() throws Exception {
        // ApiKey is the internal model used for Redis serialization — key_hash MUST be present
        ApiKey key = ApiKey.builder()
                .keyId("key_1").tenantId("tenant-1").keyPrefix("cyc_live_abc12")
                .keyHash("$2a$12$secret_hash")
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();

        String json = mapper.writeValueAsString(key);
        assertTrue(json.contains("\"key_hash\""), "Internal ApiKey model needs key_hash for Redis storage");

        // Verify round-trip: deserialize back and check hash is preserved
        ApiKey deserialized = mapper.readValue(json, ApiKey.class);
        assertEquals("$2a$12$secret_hash", deserialized.getKeyHash());
    }
}
