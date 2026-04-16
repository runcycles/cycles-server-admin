package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.auth.ApiKey;
import io.runcycles.admin.model.auth.ApiKeyCreateRequest;
import io.runcycles.admin.model.auth.ApiKeyResponse;
import io.runcycles.admin.model.auth.ApiKeyStatus;
import io.runcycles.admin.model.auth.AuthIntrospectResponse;
import io.runcycles.admin.model.auth.Capabilities;
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

    // --- v0.1.25.19 introspect dual-auth (spec v0.1.25.15) ---

    @Test
    void authIntrospectResponse_adminShape_omitsTenantAndScopeFilter() throws Exception {
        // Under auth_type=admin, tenant_id and scope_filter MUST be absent
        // (spec yaml:3187-3198). Per-field @JsonInclude on the DTO takes care
        // of this as long as the fields are left null.
        AuthIntrospectResponse admin = AuthIntrospectResponse.builder()
                .authenticated(true)
                .authType("admin")
                .permissions(List.of("*"))
                .capabilities(Capabilities.builder()
                        .viewOverview(true).viewBudgets(true).viewEvents(true)
                        .viewWebhooks(true).viewAudit(true).viewTenants(true)
                        .viewApiKeys(true).viewPolicies(true)
                        .build())
                .build();
        String json = mapper.writeValueAsString(admin);
        assertTrue(json.contains("\"auth_type\":\"admin\""));
        assertFalse(json.contains("\"tenant_id\""), "admin shape must not include tenant_id");
        assertFalse(json.contains("\"scope_filter\""), "admin shape must not include scope_filter");
    }

    @Test
    void authIntrospectResponse_tenantShape_includesTenantIdAndScopeFilterWhenSet() throws Exception {
        AuthIntrospectResponse tenant = AuthIntrospectResponse.builder()
                .authenticated(true)
                .authType("tenant")
                .permissions(List.of("budgets:read"))
                .tenantId("t-1")
                .scopeFilter(List.of("tenant:t-1/workspace:a"))
                .capabilities(Capabilities.builder()
                        .viewOverview(false).viewBudgets(true).viewEvents(false)
                        .viewWebhooks(false).viewAudit(false).viewTenants(false)
                        .viewApiKeys(false).viewPolicies(false)
                        .build())
                .build();
        String json = mapper.writeValueAsString(tenant);
        assertTrue(json.contains("\"auth_type\":\"tenant\""));
        assertTrue(json.contains("\"tenant_id\":\"t-1\""));
        assertTrue(json.contains("\"scope_filter\":[\"tenant:t-1/workspace:a\"]"));
    }

    @Test
    void authIntrospectResponse_tenantShape_emptyScopeFilterOmitted() throws Exception {
        // scope_filter is @JsonInclude(NON_EMPTY) — an empty list serializes
        // as absent per spec yaml:3194-3198 ("absent or empty means no scope
        // narrowing"). Prevents wire ambiguity between "no restrictions" and
        // "explicitly empty restrictions".
        AuthIntrospectResponse tenant = AuthIntrospectResponse.builder()
                .authenticated(true)
                .authType("tenant")
                .permissions(List.of("events:read"))
                .tenantId("t-2")
                .scopeFilter(List.of())
                .capabilities(Capabilities.builder().build())
                .build();
        String json = mapper.writeValueAsString(tenant);
        assertFalse(json.contains("\"scope_filter\""),
                "empty scope_filter must serialize as absent");
    }

    @Test
    void capabilities_optionalManageFields_absentWhenUnset() throws Exception {
        // Legacy shape (pre-v0.1.25.19): only the 8 view_* booleans. Optional
        // manage_* fields are @JsonInclude(NON_NULL) so they stay absent when
        // left null — wire-compatible with pre-v0.1.25.19 consumers.
        Capabilities legacy = Capabilities.builder()
                .viewOverview(true).viewBudgets(true).viewEvents(true)
                .viewWebhooks(true).viewAudit(true).viewTenants(true)
                .viewApiKeys(true).viewPolicies(true)
                .build();
        String json = mapper.writeValueAsString(legacy);
        assertTrue(json.contains("\"view_overview\":true"));
        assertFalse(json.contains("manage_budgets"));
        assertFalse(json.contains("manage_policies"));
        assertFalse(json.contains("manage_webhooks"));
        assertFalse(json.contains("manage_tenants"));
        assertFalse(json.contains("manage_api_keys"));
        assertFalse(json.contains("manage_reservations"));
        assertFalse(json.contains("view_reservations"));
    }

    @Test
    void capabilities_allFieldsSet_serializesEverything() throws Exception {
        Capabilities full = Capabilities.builder()
                .viewOverview(true).viewBudgets(true).viewEvents(true)
                .viewWebhooks(true).viewAudit(true).viewTenants(true)
                .viewApiKeys(true).viewPolicies(true)
                .viewReservations(true)
                .manageBudgets(true).managePolicies(true).manageWebhooks(true)
                .manageTenants(true).manageApiKeys(true).manageReservations(true)
                .build();
        String json = mapper.writeValueAsString(full);
        // All 15 fields present.
        assertTrue(json.contains("\"view_overview\":true"));
        assertTrue(json.contains("\"view_reservations\":true"));
        assertTrue(json.contains("\"manage_budgets\":true"));
        assertTrue(json.contains("\"manage_policies\":true"));
        assertTrue(json.contains("\"manage_webhooks\":true"));
        assertTrue(json.contains("\"manage_tenants\":true"));
        assertTrue(json.contains("\"manage_api_keys\":true"));
        assertTrue(json.contains("\"manage_reservations\":true"));
    }
}
