package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.budget.BudgetCreateRequest;
import io.runcycles.admin.model.budget.BudgetFundingRequest;
import io.runcycles.admin.model.budget.BudgetLedger;
import io.runcycles.admin.model.budget.BudgetStatus;
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

    @Test
    void budgetCreateRequest_rejectsUnknownFields() {
        String json = """
            {"scope":"s","unit":"TOKENS","allocated":{"unit":"TOKENS","amount":100},"extra":"bad"}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, BudgetCreateRequest.class));
    }

    @Test
    void budgetFundingRequest_rejectsUnknownFields() {
        String json = """
            {"operation":"CREDIT","amount":{"unit":"TOKENS","amount":100},"phantom":true}
            """;
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, BudgetFundingRequest.class));
    }

    @Test
    void budgetLedger_tenantId_roundTripsOnWire() throws Exception {
        // Per spec v0.1.25.19, BudgetLedger exposes tenant_id as an optional
        // response field. Servers implementing the spec MUST populate it on
        // every ledger they return so cross-tenant list responses can be
        // attributed to their tenant without scope-string parsing.
        Amount allocated = new Amount(UnitEnum.USD_MICROCENTS, 1_000_000L);
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-1")
                .tenantId("acme-corp")
                .scope("tenant:acme-corp/workspace:prod")
                .unit(UnitEnum.USD_MICROCENTS)
                .allocated(allocated)
                .remaining(new Amount(UnitEnum.USD_MICROCENTS, 1_000_000L))
                .status(BudgetStatus.ACTIVE)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        String json = mapper.writeValueAsString(ledger);

        assertTrue(json.contains("\"tenant_id\":\"acme-corp\""),
                "BudgetLedger MUST serialize tenant_id on the wire; got: " + json);

        BudgetLedger deserialized = mapper.readValue(json, BudgetLedger.class);
        assertEquals("acme-corp", deserialized.getTenantId(),
                "tenant_id MUST round-trip back from JSON");
    }

    @Test
    void budgetLedger_tenantId_omittedWhenNull() throws Exception {
        // NON_NULL inclusion keeps the wire clean for pre-v0.1.25.19 stored
        // ledgers (or any edge case where tenant_id is unknown). Spec says
        // OPTIONAL, not REQUIRED, so absence is valid.
        Amount allocated = new Amount(UnitEnum.TOKENS, 100L);
        BudgetLedger ledger = BudgetLedger.builder()
                .ledgerId("led-nil")
                .scope("legacy-scope")
                .unit(UnitEnum.TOKENS)
                .allocated(allocated)
                .remaining(new Amount(UnitEnum.TOKENS, 100L))
                .status(BudgetStatus.ACTIVE)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        String json = mapper.writeValueAsString(ledger);

        assertFalse(json.contains("\"tenant_id\""),
                "BudgetLedger with null tenantId MUST omit the field from JSON; got: " + json);
    }
}
