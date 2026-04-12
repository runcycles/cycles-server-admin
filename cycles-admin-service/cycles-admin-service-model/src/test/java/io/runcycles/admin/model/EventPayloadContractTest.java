package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.auth.ApiKeyStatus;
import io.runcycles.admin.model.event.*;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.UnitEnum;
import io.runcycles.admin.model.tenant.TenantStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-H contract check — event payload shape.
 *
 * <p>Enforces that every {@link EventType} maps to a documented
 * {@link EventPayloadTypeMapping} entry, and that each event-data class
 * serializes to / deserializes from JSON cleanly with
 * {@code @JsonIgnoreProperties(ignoreUnknown=false)} — so any producer
 * emitting a payload with undocumented fields fails validation.
 *
 * <p>Also fixes the specific drift class that the PR-G audit uncovered in
 * {@code BudgetController}: the {@code operation} field emitted lowercase
 * values when the spec requires uppercase. These per-class serialization
 * tests pin the on-the-wire enum values.
 */
class EventPayloadContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void everyEventType_hasPayloadMapping() {
        for (EventType type : EventType.values()) {
            assertTrue(EventPayloadTypeMapping.payloadClass(type).isPresent(),
                    "Missing EventPayloadTypeMapping entry for " + type
                            + " (" + type.getValue() + ") — add one in EventPayloadTypeMapping.");
        }
    }

    /**
     * For every event type, serialize a minimal payload of its mapped class
     * and round-trip through Jackson. Fails if the class has drifted out of
     * wire-compatibility with itself (e.g. a field was renamed without
     * updating {@code @JsonProperty}).
     *
     * <p>Uses dynamic tests so the failing event type is named in the
     * report, not just "one of the 40".
     */
    @TestFactory
    List<DynamicTest> everyMappedPayloadClass_roundTripsCleanly() {
        return EventPayloadTypeMapping.all().keySet().stream()
                .map(type -> DynamicTest.dynamicTest(
                        "roundTrip " + type.getValue(),
                        () -> {
                            Class<?> cls = EventPayloadTypeMapping.payloadClass(type).orElseThrow();
                            Object instance = cls.getDeclaredConstructor().newInstance();
                            String json = mapper.writeValueAsString(instance);
                            Object decoded = mapper.readValue(json, cls);
                            assertEquals(instance, decoded,
                                    "Round-trip drift on empty " + cls.getSimpleName() + " payload");
                        }))
                .toList();
    }

    // ---- per-class enum-on-the-wire assertions ----

    @Test
    void budgetLifecycle_operationEnum_serializesUppercase() throws Exception {
        EventDataBudgetLifecycle data = EventDataBudgetLifecycle.builder()
                .ledgerId("lg_1").scope("tenant:tenant-1").unit(UnitEnum.USD_MICROCENTS)
                .operation(BudgetOperation.CREATE).build();
        Map<?, ?> parsed = mapper.readValue(mapper.writeValueAsString(data), Map.class);
        assertEquals("CREATE", parsed.get("operation"),
                "Spec requires UPPERCASE BudgetOperation on the wire (CREATE/UPDATE/CREDIT/...)");

        // Confirm round-trip with @JsonIgnoreProperties strict — unknown extras must fail.
        String badJson = """
                {"ledger_id":"lg_1","unknown_field":"oops","operation":"CREATE"}
                """;
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> mapper.readValue(badJson, EventDataBudgetLifecycle.class),
                "EventDataBudgetLifecycle must reject unknown fields per spec additionalProperties:false");
    }

    @Test
    void budgetThreshold_directionEnum_serializesLowercase() throws Exception {
        EventDataBudgetThreshold data = EventDataBudgetThreshold.builder()
                .scope("tenant:tenant-1").unit(UnitEnum.USD_MICROCENTS)
                .threshold(0.9).utilization(0.92)
                .direction(ThresholdDirection.RISING).build();
        Map<?, ?> parsed = mapper.readValue(mapper.writeValueAsString(data), Map.class);
        assertEquals("rising", parsed.get("direction"),
                "Spec requires lowercase direction values (rising/falling)");
    }

    @Test
    void rateSpike_metricEnum_serializesSnakeCase() throws Exception {
        EventDataRateSpike data = EventDataRateSpike.builder()
                .metric(RateSpikeMetric.DENIAL_RATE)
                .currentRate(0.15).thresholdRate(0.10)
                .windowSeconds(300).sampleCount(200)
                .build();
        Map<?, ?> parsed = mapper.readValue(mapper.writeValueAsString(data), Map.class);
        assertEquals("denial_rate", parsed.get("metric"),
                "Spec requires snake_case metric values (denial_rate/expiry_rate/auth_failure_rate)");
    }

    @Test
    void tenantLifecycle_statusEnums_serializeUppercase() throws Exception {
        EventDataTenantLifecycle data = EventDataTenantLifecycle.builder()
                .tenantId("tenant-1")
                .previousStatus(TenantStatus.ACTIVE)
                .newStatus(TenantStatus.SUSPENDED)
                .changedFields(List.of("status"))
                .build();
        Map<?, ?> parsed = mapper.readValue(mapper.writeValueAsString(data), Map.class);
        assertEquals("ACTIVE", parsed.get("previous_status"));
        assertEquals("SUSPENDED", parsed.get("new_status"));
    }

    @Test
    void apiKey_statusEnums_serializeUppercase() throws Exception {
        EventDataApiKey data = EventDataApiKey.builder()
                .keyId("key_1").keyName("svc")
                .previousStatus(ApiKeyStatus.ACTIVE)
                .newStatus(ApiKeyStatus.REVOKED)
                .build();
        Map<?, ?> parsed = mapper.readValue(mapper.writeValueAsString(data), Map.class);
        assertEquals("ACTIVE", parsed.get("previous_status"));
        assertEquals("REVOKED", parsed.get("new_status"));
    }

    @Test
    void eventDataBudgetDebtIncurred_fullShape() throws Exception {
        // Spec fields: scope, unit, reservation_id, debt_incurred, total_debt,
        //              overdraft_limit, overage_policy
        EventDataBudgetDebtIncurred data = EventDataBudgetDebtIncurred.builder()
                .scope("tenant:tenant-1").unit(UnitEnum.USD_MICROCENTS)
                .reservationId("rsv_1")
                .debtIncurred(5000L).totalDebt(12000L).overdraftLimit(50000L)
                .overagePolicy(CommitOveragePolicy.ALLOW_WITH_OVERDRAFT)
                .build();
        String json = mapper.writeValueAsString(data);
        assertTrue(json.contains("\"reservation_id\""));
        assertTrue(json.contains("\"overage_policy\""));
        assertTrue(json.contains("\"debt_incurred\""));
        // Round-trip integrity
        assertEquals(data, mapper.readValue(json, EventDataBudgetDebtIncurred.class));
    }

    @Test
    void eventDataSystem_severityEnum() throws Exception {
        EventDataSystem data = EventDataSystem.builder()
                .component("redis").message("connection timeout")
                .severity(SystemSeverity.WARNING)
                .build();
        Map<?, ?> parsed = mapper.readValue(mapper.writeValueAsString(data), Map.class);
        // Spec enum: [info, warning, critical] — lowercase
        assertEquals("warning", parsed.get("severity"));
    }

    @Test
    void event_dataField_acceptsMappedPayload_asMap() throws Exception {
        // Producer pattern: EventDataX -> Map<String,Object> -> Event.data
        EventDataBudgetLifecycle payload = EventDataBudgetLifecycle.builder()
                .ledgerId("lg_1").scope("tenant:tenant-1").unit(UnitEnum.USD_MICROCENTS)
                .operation(BudgetOperation.CREDIT)
                .reason("monthly top-up")
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = mapper.convertValue(payload, Map.class);
        Event event = Event.builder()
                .eventId("evt_123")
                .eventType(EventType.BUDGET_FUNDED)
                .category(EventCategory.BUDGET)
                .tenantId("tenant-1").source("cycles-admin")
                .timestamp(Instant.parse("2026-04-12T00:00:00Z"))
                .data(dataMap)
                .build();
        String eventJson = mapper.writeValueAsString(event);

        // The reverse trip: pull data back out and re-type as its payload class.
        // Proves the (EventType, data) tuple the producer emitted is internally
        // consistent with EventPayloadTypeMapping.
        Event deserialized = mapper.readValue(eventJson, Event.class);
        Class<?> expectedPayloadClass = EventPayloadTypeMapping
                .payloadClass(deserialized.getEventType()).orElseThrow();
        Object roundTripped = mapper.convertValue(deserialized.getData(), expectedPayloadClass);
        assertEquals(payload, roundTripped,
                "Producer-emitted Map<String,Object> must round-trip cleanly through its "
                        + "EventPayloadTypeMapping-assigned class");
    }
}
