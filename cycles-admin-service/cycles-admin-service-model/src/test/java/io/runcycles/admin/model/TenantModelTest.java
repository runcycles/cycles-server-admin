package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.tenant.TenantCreateRequest;
import io.runcycles.admin.model.tenant.TenantUpdateRequest;
import jakarta.validation.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantModelTest {

    private static Validator validator;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void tenantCreateRequest_rejectsUnknownFields() {
        String json = """
            {"tenant_id":"acme","name":"Acme","ghost":"field"}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, TenantCreateRequest.class));
    }

    @Test
    void tenantCreateRequest_ttlTooLow_fails() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setTenantId("acme");
        req.setName("Acme");
        req.setDefaultReservationTtlMs(500L);
        Set<ConstraintViolation<TenantCreateRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void tenantCreateRequest_ttlTooHigh_fails() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setTenantId("acme");
        req.setName("Acme");
        req.setMaxReservationTtlMs(86400001L);
        Set<ConstraintViolation<TenantCreateRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void tenantCreateRequest_negativeExtensions_fails() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setTenantId("acme");
        req.setName("Acme");
        req.setMaxReservationExtensions(-1);
        Set<ConstraintViolation<TenantCreateRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void tenantCreateRequest_validTtlBoundary_passes() {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setTenantId("acme");
        req.setName("Acme");
        req.setDefaultReservationTtlMs(1000L);
        req.setMaxReservationTtlMs(86400000L);
        req.setMaxReservationExtensions(0);
        Set<ConstraintViolation<TenantCreateRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Boundary values should pass: " + violations);
    }

    @Test
    void tenantUpdateRequest_rejectsUnknownFields() {
        String json = """
            {"name":"Updated","extra":1}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, TenantUpdateRequest.class));
    }

    @Test
    void tenantUpdateRequest_ttlTooLow_fails() {
        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setDefaultReservationTtlMs(999L);
        Set<ConstraintViolation<TenantUpdateRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void tenantUpdateRequest_negativeExtensions_fails() {
        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setMaxReservationExtensions(-1);
        Set<ConstraintViolation<TenantUpdateRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }
}
