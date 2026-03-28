package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.budget.BudgetCreateRequest;
import io.runcycles.admin.model.shared.*;
import jakarta.validation.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BudgetModelTest {

    private static Validator validator;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void serializationRoundTrip() throws Exception {
        Amount allocated = new Amount(UnitEnum.USD_MICROCENTS, 1_000_000L);
        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("project-alpha");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(allocated);
        request.setCommitOveragePolicy(CommitOveragePolicy.REJECT);
        request.setPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        request.setMetadata(Map.of("env", "prod"));

        String json = mapper.writeValueAsString(request);

        // Verify snake_case property names from @JsonProperty
        assertTrue(json.contains("\"scope\""));
        assertTrue(json.contains("\"commit_overage_policy\""));
        assertTrue(json.contains("\"period_start\""));

        BudgetCreateRequest deserialized = mapper.readValue(json, BudgetCreateRequest.class);
        assertEquals(request.getScope(), deserialized.getScope());
        assertEquals(request.getUnit(), deserialized.getUnit());
        assertEquals(request.getAllocated().getAmount(), deserialized.getAllocated().getAmount());
        assertEquals(request.getCommitOveragePolicy(), deserialized.getCommitOveragePolicy());
        assertEquals(request.getMetadata(), deserialized.getMetadata());
    }

    @Test
    void validRequestHasNoViolations() {
        Amount allocated = new Amount(UnitEnum.TOKENS, 500L);
        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("my-scope");
        request.setUnit(UnitEnum.TOKENS);
        request.setAllocated(allocated);

        Set<ConstraintViolation<BudgetCreateRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), "Expected no violations but got: " + violations);
    }

    @Test
    void missingRequiredFieldsProducesViolations() {
        BudgetCreateRequest request = new BudgetCreateRequest();

        Set<ConstraintViolation<BudgetCreateRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
        // scope, unit, allocated are all @NotNull/@NotBlank
        assertTrue(violations.size() >= 3,
                "Expected at least 3 violations but got " + violations.size());
    }

    @Test
    void allocatedAmountNegativeValueFails() {
        Amount allocated = new Amount(UnitEnum.USD_MICROCENTS, -1L);
        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("scope");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(allocated);

        Set<ConstraintViolation<BudgetCreateRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty(), "Negative amount should fail @Min(0)");
    }
}
