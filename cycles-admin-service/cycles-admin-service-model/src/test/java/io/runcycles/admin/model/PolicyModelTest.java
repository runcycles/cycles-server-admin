package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.policy.PolicyUpdateRequest;
import io.runcycles.admin.model.policy.RateLimits;
import io.runcycles.admin.model.policy.ReservationTtlOverride;
import jakarta.validation.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PolicyModelTest {

    private static Validator validator;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void policyCreateRequest_rejectsUnknownFields() {
        String json = """
            {"name":"p1","scope_pattern":"*","phantom":true}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, PolicyCreateRequest.class));
    }

    @Test
    void policyCreateRequest_nameTooLong_fails() {
        PolicyCreateRequest req = new PolicyCreateRequest();
        req.setName("x".repeat(257));
        req.setScopePattern("*");
        Set<ConstraintViolation<PolicyCreateRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void policyCreateRequest_descriptionTooLong_fails() {
        PolicyCreateRequest req = new PolicyCreateRequest();
        req.setName("valid");
        req.setScopePattern("*");
        req.setDescription("x".repeat(1025));
        Set<ConstraintViolation<PolicyCreateRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void policyCreateRequest_negativePriority_fails() {
        PolicyCreateRequest req = new PolicyCreateRequest();
        req.setName("valid");
        req.setScopePattern("*");
        req.setPriority(-1);

        Set<ConstraintViolation<PolicyCreateRequest>> violations = validator.validate(req);

        assertTrue(violations.stream().anyMatch(v -> "priority".equals(v.getPropertyPath().toString())));
    }

    @Test
    void policyCreateRequest_zeroPriority_isValid() {
        PolicyCreateRequest req = new PolicyCreateRequest();
        req.setName("valid");
        req.setScopePattern("*");
        req.setPriority(0);

        assertTrue(validator.validate(req).isEmpty());
    }

    @Test
    void policyUpdateRequest_negativePriority_fails() {
        PolicyUpdateRequest req = new PolicyUpdateRequest();
        req.setPriority(-1);

        Set<ConstraintViolation<PolicyUpdateRequest>> violations = validator.validate(req);

        assertTrue(violations.stream().anyMatch(v -> "priority".equals(v.getPropertyPath().toString())));
    }

    @Test
    void policyUpdateRequest_zeroPriority_isValid() {
        PolicyUpdateRequest req = new PolicyUpdateRequest();
        req.setPriority(0);

        assertTrue(validator.validate(req).isEmpty());
    }

    @Test
    void rateLimits_rejectsUnknownFields() {
        String json = """
            {"max_reservations_per_minute":10,"bad":1}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, RateLimits.class));
    }

    @Test
    void reservationTtlOverride_rejectsUnknownFields() {
        String json = """
            {"default_ttl_ms":5000,"extra":"nope"}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, ReservationTtlOverride.class));
    }
}
