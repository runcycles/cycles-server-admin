package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.shared.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void amountSerializationRoundTrip() throws Exception {
        Amount amount = new Amount(UnitEnum.USD_MICROCENTS, 500_000L);
        String json = mapper.writeValueAsString(amount);

        assertTrue(json.contains("\"unit\""));
        assertTrue(json.contains("\"amount\""));
        assertTrue(json.contains("USD_MICROCENTS"));

        Amount deserialized = mapper.readValue(json, Amount.class);
        assertEquals(amount.getUnit(), deserialized.getUnit());
        assertEquals(amount.getAmount(), deserialized.getAmount());
    }

    @Test
    void amountDefaultConstructor() {
        Amount amount = new Amount();
        assertNull(amount.getUnit());
        assertNull(amount.getAmount());
    }

    @Test
    void unitEnumValues() {
        UnitEnum[] values = UnitEnum.values();
        assertEquals(4, values.length);
        assertNotNull(UnitEnum.valueOf("USD_MICROCENTS"));
        assertNotNull(UnitEnum.valueOf("TOKENS"));
        assertNotNull(UnitEnum.valueOf("CREDITS"));
        assertNotNull(UnitEnum.valueOf("RISK_POINTS"));
    }

    @Test
    void errorCodeValues() {
        ErrorCode[] values = ErrorCode.values();
        assertTrue(values.length > 10, "Expected many error codes");
        assertNotNull(ErrorCode.valueOf("INVALID_REQUEST"));
        assertNotNull(ErrorCode.valueOf("BUDGET_EXCEEDED"));
        assertNotNull(ErrorCode.valueOf("TENANT_SUSPENDED"));
        assertNotNull(ErrorCode.valueOf("KEY_REVOKED"));
        assertNotNull(ErrorCode.valueOf("DUPLICATE_RESOURCE"));
        assertNotNull(ErrorCode.valueOf("BUDGET_CLOSED"));
    }

    @Test
    void commitOveragePolicyValues() {
        CommitOveragePolicy[] values = CommitOveragePolicy.values();
        assertEquals(3, values.length);
        assertNotNull(CommitOveragePolicy.valueOf("REJECT"));
        assertNotNull(CommitOveragePolicy.valueOf("ALLOW_IF_AVAILABLE"));
        assertNotNull(CommitOveragePolicy.valueOf("ALLOW_WITH_OVERDRAFT"));
    }
}
