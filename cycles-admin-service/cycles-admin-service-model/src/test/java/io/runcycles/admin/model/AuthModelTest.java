package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.auth.ApiKeyCreateRequest;
import io.runcycles.admin.model.auth.ApiKeyStatus;
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
        request.setPermissions(List.of("read", "write"));
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
}
