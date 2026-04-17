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

    // ---- v0.1.25.8: AdminOverviewResponse extensibility ----

    @Test
    void adminOverviewResponse_v0_1_25_8Fields_roundTrip() throws Exception {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        AdminOverviewResponse.QuotaHealth quotaHealth = AdminOverviewResponse.QuotaHealth.builder()
                .countersAbove80Pct(3)
                .countersAtLimit(1)
                .topOffenders(java.util.List.of(
                        AdminOverviewResponse.QuotaOffender.builder()
                                .scope("tenant:acme/agent:support")
                                .actionKind("payment.charge")
                                .window("1h")
                                .used(95L)
                                .limit(100L)
                                .utilizationPct(0.95)
                                .build()
                ))
                .build();

        AdminOverviewResponse.AccessControlStats accessStats = AdminOverviewResponse.AccessControlStats.builder()
                .policiesWithAllowList(5)
                .policiesWithDenyList(2)
                .build();

        AdminOverviewResponse.TenantCounts tenantCounts = AdminOverviewResponse.TenantCounts.builder()
                .total(10).active(8).suspended(1).closed(1).inObserveMode(3).build();

        AdminOverviewResponse response = AdminOverviewResponse.builder()
                .asOf(java.time.Instant.parse("2026-04-10T00:00:00Z"))
                .eventWindowSeconds(3600)
                .tenantCounts(tenantCounts)
                .recentDenialsByReason(java.util.Map.of("BUDGET_EXCEEDED", 12, "ACTION_QUOTA_EXCEEDED", 7))
                .quotaHealth(quotaHealth)
                .accessControlStats(accessStats)
                .build();

        String json = m.writeValueAsString(response);
        assertTrue(json.contains("\"in_observe_mode\":3"));
        assertTrue(json.contains("\"recent_denials_by_reason\""));
        assertTrue(json.contains("\"quota_health\""));
        assertTrue(json.contains("\"counters_above_80pct\":3"));
        assertTrue(json.contains("\"top_offenders\""));
        assertTrue(json.contains("\"access_control_stats\""));
        assertTrue(json.contains("\"policies_with_allow_list\":5"));
        assertTrue(json.contains("\"action_kind\":\"payment.charge\""));

        AdminOverviewResponse roundTrip = m.readValue(json, AdminOverviewResponse.class);
        assertEquals(3, roundTrip.getTenantCounts().getInObserveMode());
        assertEquals(12, roundTrip.getRecentDenialsByReason().get("BUDGET_EXCEEDED"));
        assertEquals(1, roundTrip.getQuotaHealth().getCountersAtLimit());
        assertEquals(5, roundTrip.getAccessControlStats().getPoliciesWithAllowList());
        assertEquals("payment.charge", roundTrip.getQuotaHealth().getTopOffenders().get(0).getActionKind());
    }

    // ---- v0.1.25.20: SortDirection + SortSpec shared types ----

    @Test
    void sortDirection_fromWire_parsesCaseInsensitive() {
        assertEquals(SortDirection.ASC, SortDirection.fromWire("asc"));
        assertEquals(SortDirection.ASC, SortDirection.fromWire("ASC"));
        assertEquals(SortDirection.ASC, SortDirection.fromWire("Asc"));
        assertEquals(SortDirection.DESC, SortDirection.fromWire("desc"));
        assertEquals(SortDirection.DESC, SortDirection.fromWire("DESC"));
    }

    @Test
    void sortDirection_fromWire_nullReturnsNull() {
        assertNull(SortDirection.fromWire(null));
    }

    @Test
    void sortDirection_fromWire_invalidThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> SortDirection.fromWire("sideways"));
        assertTrue(ex.getMessage().contains("sideways"));
        assertTrue(ex.getMessage().contains("asc"));
    }

    @Test
    void sortDirection_wireValueRoundTrip() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertEquals("\"asc\"", m.writeValueAsString(SortDirection.ASC));
        assertEquals("\"desc\"", m.writeValueAsString(SortDirection.DESC));
        assertEquals(SortDirection.ASC, m.readValue("\"asc\"", SortDirection.class));
        assertEquals(SortDirection.DESC, m.readValue("\"DESC\"", SortDirection.class));
    }

    @Test
    void sortSpec_of_buildsRecord() {
        SortSpec spec = SortSpec.of("created_at", SortDirection.ASC);
        assertEquals("created_at", spec.field());
        assertEquals(SortDirection.ASC, spec.direction());
        assertTrue(spec.isAscending());
    }

    @Test
    void sortSpec_resolve_usesDefaultsWhenBlank() {
        java.util.Set<String> allowed = java.util.Set.of("name", "created_at");
        SortSpec spec = SortSpec.resolve(null, null, allowed, "created_at");
        assertEquals("created_at", spec.field());
        assertEquals(SortDirection.DESC, spec.direction());
        assertFalse(spec.isAscending());
    }

    @Test
    void sortSpec_resolve_usesDefaultOnBlankString() {
        java.util.Set<String> allowed = java.util.Set.of("name", "created_at");
        SortSpec spec = SortSpec.resolve("   ", SortDirection.ASC, allowed, "name");
        assertEquals("name", spec.field());
        assertEquals(SortDirection.ASC, spec.direction());
    }

    @Test
    void sortSpec_resolve_rejectsUnknownField() {
        java.util.Set<String> allowed = java.util.Set.of("name", "created_at");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> SortSpec.resolve("bogus", SortDirection.ASC, allowed, "name"));
        assertTrue(ex.getMessage().contains("bogus"));
    }

    @Test
    void sortSpec_resolve_acceptsExplicitField() {
        java.util.Set<String> allowed = java.util.Set.of("name", "created_at");
        SortSpec spec = SortSpec.resolve("name", SortDirection.ASC, allowed, "created_at");
        assertEquals("name", spec.field());
        assertTrue(spec.isAscending());
    }

    @Test
    void adminOverviewResponse_v0_1_25_8Fields_omittedWhenNull() throws Exception {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        // Build a minimal v0.1.25.7-style response — new fields left null
        AdminOverviewResponse response = AdminOverviewResponse.builder()
                .asOf(java.time.Instant.parse("2026-04-10T00:00:00Z"))
                .eventWindowSeconds(3600)
                .tenantCounts(AdminOverviewResponse.TenantCounts.builder()
                        .total(10).active(8).suspended(1).closed(1).build())
                .build();

        String json = m.writeValueAsString(response);
        // @JsonInclude(NON_NULL) on new fields: must be absent when null
        assertFalse(json.contains("in_observe_mode"));
        assertFalse(json.contains("recent_denials_by_reason"));
        assertFalse(json.contains("quota_health"));
        assertFalse(json.contains("access_control_stats"));
    }
}
