package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.event.*;
import io.runcycles.admin.model.shared.UnitEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventModelTest {

    private final ObjectMapper mapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    // ---- EventType enum ----

    @Test
    void eventType_fromValue_allTypesResolve() {
        for (EventType type : EventType.values()) {
            assertEquals(type, EventType.fromValue(type.getValue()));
        }
    }

    @Test
    void eventType_fromValue_throwsForUnknown() {
        assertThrows(IllegalArgumentException.class, () -> EventType.fromValue("unknown.type"));
    }

    @Test
    void eventType_allTypesHaveExpectedCount() {
        assertEquals(40, EventType.values().length);
    }

    @Test
    void eventType_getCategory_budgetTypes() {
        assertEquals(EventCategory.BUDGET, EventType.BUDGET_CREATED.getCategory());
        assertEquals(EventCategory.BUDGET, EventType.BUDGET_FUNDED.getCategory());
        assertEquals(EventCategory.BUDGET, EventType.BUDGET_FROZEN.getCategory());
        assertEquals(EventCategory.BUDGET, EventType.BUDGET_THRESHOLD_CROSSED.getCategory());
        assertEquals(EventCategory.BUDGET, EventType.BUDGET_BURN_RATE_ANOMALY.getCategory());
    }

    @Test
    void eventType_getCategory_reservationTypes() {
        assertEquals(EventCategory.RESERVATION, EventType.RESERVATION_DENIED.getCategory());
        assertEquals(EventCategory.RESERVATION, EventType.RESERVATION_EXPIRED.getCategory());
        assertEquals(EventCategory.RESERVATION, EventType.RESERVATION_COMMIT_OVERAGE.getCategory());
    }

    @Test
    void eventType_getCategory_tenantTypes() {
        assertEquals(EventCategory.TENANT, EventType.TENANT_CREATED.getCategory());
        assertEquals(EventCategory.TENANT, EventType.TENANT_SUSPENDED.getCategory());
        assertEquals(EventCategory.TENANT, EventType.TENANT_SETTINGS_CHANGED.getCategory());
    }

    @Test
    void eventType_getCategory_apiKeyTypes() {
        assertEquals(EventCategory.API_KEY, EventType.API_KEY_CREATED.getCategory());
        assertEquals(EventCategory.API_KEY, EventType.API_KEY_REVOKED.getCategory());
        assertEquals(EventCategory.API_KEY, EventType.API_KEY_AUTH_FAILED.getCategory());
    }

    @Test
    void eventType_getCategory_policyTypes() {
        assertEquals(EventCategory.POLICY, EventType.POLICY_CREATED.getCategory());
        assertEquals(EventCategory.POLICY, EventType.POLICY_UPDATED.getCategory());
        assertEquals(EventCategory.POLICY, EventType.POLICY_DELETED.getCategory());
    }

    @Test
    void eventType_getCategory_systemTypes() {
        assertEquals(EventCategory.SYSTEM, EventType.SYSTEM_STORE_CONNECTION_LOST.getCategory());
        assertEquals(EventCategory.SYSTEM, EventType.SYSTEM_HIGH_LATENCY.getCategory());
        assertEquals(EventCategory.SYSTEM, EventType.SYSTEM_WEBHOOK_TEST.getCategory());
    }

    @Test
    void eventType_isTenantAccessible_trueForBudgetReservationTenant() {
        assertTrue(EventType.BUDGET_CREATED.isTenantAccessible());
        assertTrue(EventType.BUDGET_EXHAUSTED.isTenantAccessible());
        assertTrue(EventType.RESERVATION_DENIED.isTenantAccessible());
        assertTrue(EventType.RESERVATION_EXPIRED.isTenantAccessible());
        assertTrue(EventType.TENANT_CREATED.isTenantAccessible());
        assertTrue(EventType.TENANT_SUSPENDED.isTenantAccessible());
    }

    @Test
    void eventType_isTenantAccessible_falseForApiKeyPolicySystem() {
        assertFalse(EventType.API_KEY_CREATED.isTenantAccessible());
        assertFalse(EventType.API_KEY_REVOKED.isTenantAccessible());
        assertFalse(EventType.POLICY_CREATED.isTenantAccessible());
        assertFalse(EventType.POLICY_DELETED.isTenantAccessible());
        assertFalse(EventType.SYSTEM_HIGH_LATENCY.isTenantAccessible());
        assertFalse(EventType.SYSTEM_WEBHOOK_TEST.isTenantAccessible());
    }

    @Test
    void eventType_jsonValue_serializesToDotNotation() throws Exception {
        String json = mapper.writeValueAsString(EventType.BUDGET_CREATED);
        assertEquals("\"budget.created\"", json);

        String json2 = mapper.writeValueAsString(EventType.TENANT_SETTINGS_CHANGED);
        assertEquals("\"tenant.settings_changed\"", json2);

        String json3 = mapper.writeValueAsString(EventType.API_KEY_AUTH_FAILURE_RATE_SPIKE);
        assertEquals("\"api_key.auth_failure_rate_spike\"", json3);
    }

    @Test
    void eventType_jsonDeserialization_fromDotNotation() throws Exception {
        EventType type = mapper.readValue("\"budget.created\"", EventType.class);
        assertEquals(EventType.BUDGET_CREATED, type);
    }

    // ---- Event ----

    @Test
    void event_jsonRoundTrip() throws Exception {
        Instant now = Instant.now();
        Event event = Event.builder()
                .eventId("evt_abc123")
                .eventType(EventType.TENANT_CREATED)
                .category(EventCategory.TENANT)
                .timestamp(now)
                .tenantId("t1")
                .source("cycles-admin")
                .scope("org/eng")
                .correlationId("corr_1")
                .requestId("req_1")
                .data(Map.of("key", "value"))
                .metadata(Map.of("meta", "data"))
                .build();

        String json = mapper.writeValueAsString(event);
        Event deserialized = mapper.readValue(json, Event.class);

        assertEquals(event.getEventId(), deserialized.getEventId());
        assertEquals(event.getEventType(), deserialized.getEventType());
        assertEquals(event.getCategory(), deserialized.getCategory());
        assertEquals(event.getTenantId(), deserialized.getTenantId());
        assertEquals(event.getSource(), deserialized.getSource());
        assertEquals(event.getScope(), deserialized.getScope());
        assertEquals(event.getCorrelationId(), deserialized.getCorrelationId());
        assertEquals(event.getRequestId(), deserialized.getRequestId());
    }

    @Test
    void event_jsonContainsExpectedFieldNames() throws Exception {
        Event event = Event.builder()
                .eventId("evt_1")
                .eventType(EventType.BUDGET_FUNDED)
                .category(EventCategory.BUDGET)
                .timestamp(Instant.now())
                .tenantId("t1")
                .source("test")
                .build();

        String json = mapper.writeValueAsString(event);

        assertTrue(json.contains("\"event_id\""));
        assertTrue(json.contains("\"event_type\""));
        assertTrue(json.contains("\"tenant_id\""));
        assertTrue(json.contains("\"budget.funded\""));
    }

    @Test
    void event_nullOptionalFieldsOmitted() throws Exception {
        Event event = Event.builder()
                .eventId("evt_1")
                .eventType(EventType.TENANT_CREATED)
                .category(EventCategory.TENANT)
                .timestamp(Instant.now())
                .tenantId("t1")
                .source("test")
                .build();

        String json = mapper.writeValueAsString(event);

        assertFalse(json.contains("\"scope\""));
        assertFalse(json.contains("\"correlation_id\""));
        assertFalse(json.contains("\"metadata\""));
    }

    // ---- EventDataBudgetLifecycle ----

    @Test
    void eventDataBudgetLifecycle_serialization() throws Exception {
        EventDataBudgetLifecycle data = EventDataBudgetLifecycle.builder()
                .ledgerId("ledger_1")
                .scope("org/eng")
                .unit(UnitEnum.USD_MICROCENTS)
                .operation("fund")
                .previousState(EventDataBudgetLifecycle.BudgetState.builder()
                        .allocated(1000L).remaining(500L).debt(0L).status("ACTIVE").build())
                .newState(EventDataBudgetLifecycle.BudgetState.builder()
                        .allocated(2000L).remaining(1500L).debt(0L).status("ACTIVE").build())
                .reason("Monthly top-up")
                .build();

        String json = mapper.writeValueAsString(data);
        EventDataBudgetLifecycle deserialized = mapper.readValue(json, EventDataBudgetLifecycle.class);

        assertEquals("ledger_1", deserialized.getLedgerId());
        assertEquals("org/eng", deserialized.getScope());
        assertEquals(UnitEnum.USD_MICROCENTS, deserialized.getUnit());
        assertEquals(1000L, deserialized.getPreviousState().getAllocated());
        assertEquals(2000L, deserialized.getNewState().getAllocated());
        assertTrue(json.contains("\"ledger_id\""));
        assertTrue(json.contains("\"previous_state\""));
        assertTrue(json.contains("\"new_state\""));
    }

    // ---- EventCategory ----

    @Test
    void eventCategory_allValues() {
        EventCategory[] values = EventCategory.values();
        assertEquals(6, values.length);
        assertNotNull(EventCategory.valueOf("BUDGET"));
        assertNotNull(EventCategory.valueOf("TENANT"));
        assertNotNull(EventCategory.valueOf("API_KEY"));
        assertNotNull(EventCategory.valueOf("POLICY"));
        assertNotNull(EventCategory.valueOf("RESERVATION"));
        assertNotNull(EventCategory.valueOf("SYSTEM"));
    }
}
