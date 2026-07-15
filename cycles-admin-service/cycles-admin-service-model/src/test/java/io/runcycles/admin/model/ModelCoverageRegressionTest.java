package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.auth.ApiKey;
import io.runcycles.admin.model.auth.Permission;
import io.runcycles.admin.model.event.ActorType;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.event.RateSpikeMetric;
import io.runcycles.admin.model.event.ThresholdDirection;
import io.runcycles.admin.model.shared.Subject;
import io.runcycles.admin.model.tenant.TenantBulkFilter;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.webhook.WebhookBulkFilter;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCoverageRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void apiKey_legacyEmptyObjectsDeserializeAsEmptyLists() throws Exception {
        ApiKey key = mapper.readValue(
            "{\"permissions\":{},\"scope_filter\":{}}", ApiKey.class);

        assertEquals(List.of(), key.getPermissions());
        assertEquals(List.of(), key.getScopeFilter());
    }

    @Test
    void apiKey_stringListsHandleArraysNullAndUnexpectedScalars() throws Exception {
        ApiKey arrays = mapper.readValue(
            "{\"permissions\":[\"budgets:read\"],\"scope_filter\":[\"tenant:t1\"]}",
            ApiKey.class);
        ApiKey nulls = mapper.readValue(
            "{\"permissions\":null,\"scope_filter\":\"legacy\"}", ApiKey.class);

        assertEquals(List.of("budgets:read"), arrays.getPermissions());
        assertEquals(List.of("tenant:t1"), arrays.getScopeFilter());
        assertNull(nulls.getPermissions());
        assertEquals(List.of(), nulls.getScopeFilter());
    }

    @Test
    void apiKey_nonEmptyObjectStillFailsFast() {
        assertThrows(JsonMappingException.class, () -> mapper.readValue(
            "{\"permissions\":{\"unexpected\":true}}", ApiKey.class));
    }

    @Test
    void subject_requiresAtLeastOneNonBlankStandardField() {
        Subject subject = new Subject();
        subject.setTenant("  ");
        assertFalse(subject.hasAtLeastOneStandardField());

        subject.setAgent("agent-1");
        assertTrue(subject.isHasAtLeastOneStandardField());
    }

    @Test
    void bulkFiltersDistinguishEmptyAndPopulatedRequests() {
        TenantBulkFilter tenantFilter = TenantBulkFilter.builder().build();
        WebhookBulkFilter webhookFilter = WebhookBulkFilter.builder().build();
        assertTrue(tenantFilter.isEmpty());
        assertTrue(webhookFilter.isEmpty());

        tenantFilter.setStatus(TenantStatus.CLOSED);
        webhookFilter.setStatus(WebhookStatus.PAUSED);
        assertFalse(tenantFilter.isEmpty());
        assertFalse(webhookFilter.isEmpty());
    }

    @Test
    void tenantBulkFilter_coversEveryShortCircuitPath() {
        assertTrue(TenantBulkFilter.builder().parentTenantId(" ").observeMode("\t").search("\n").build().isEmpty());
        assertFalse(TenantBulkFilter.builder().status(TenantStatus.ACTIVE).build().isEmpty());
        assertFalse(TenantBulkFilter.builder().parentTenantId("parent-1").build().isEmpty());
        assertFalse(TenantBulkFilter.builder().parentTenantId(" ").observeMode("observe").build().isEmpty());
        assertFalse(TenantBulkFilter.builder().parentTenantId(" ").observeMode(" ").search("acme").build().isEmpty());
    }

    @Test
    void webhookBulkFilter_coversEveryShortCircuitPath() {
        assertTrue(WebhookBulkFilter.builder().tenantId(" ").search("\t").build().isEmpty());
        assertFalse(WebhookBulkFilter.builder().tenantId("tenant-1").build().isEmpty());
        assertFalse(WebhookBulkFilter.builder().tenantId(" ").status(WebhookStatus.ACTIVE).build().isEmpty());
        assertFalse(WebhookBulkFilter.builder().tenantId(" ").eventType(EventType.BUDGET_CREATED).build().isEmpty());
        assertFalse(WebhookBulkFilter.builder().tenantId(" ").search("billing").build().isEmpty());
    }

    @Test
    void subject_acceptsEachStandardFieldAndRejectsAllBlank() {
        Subject subject = new Subject(" ", "\t", "\n", " ", "\t", "\n", null);
        assertFalse(subject.hasAtLeastOneStandardField());

        subject = new Subject();
        subject.setTenant("tenant-1");
        assertTrue(subject.hasAtLeastOneStandardField());
        subject = new Subject();
        subject.setWorkspace("workspace-1");
        assertTrue(subject.hasAtLeastOneStandardField());
        subject = new Subject();
        subject.setApp("app-1");
        assertTrue(subject.hasAtLeastOneStandardField());
        subject = new Subject();
        subject.setWorkflow("workflow-1");
        assertTrue(subject.hasAtLeastOneStandardField());
        subject = new Subject();
        subject.setAgent("agent-1");
        assertTrue(subject.hasAtLeastOneStandardField());
        subject = new Subject();
        subject.setToolset("toolset-1");
        assertTrue(subject.hasAtLeastOneStandardField());
    }

    @Test
    void permissionHelpers_coverNullKnownAndUnknownValues() {
        Permission last = Permission.values()[Permission.values().length - 1];
        assertEquals(Permission.RESERVATIONS_CREATE, Permission.fromValue("reservations:create"));
        assertEquals(last, Permission.fromValue(last.getValue()));
        assertThrows(IllegalArgumentException.class, () -> Permission.fromValue("unknown:permission"));

        assertFalse(Permission.isValid(null));
        assertTrue(Permission.isValid("reservations:create"));
        assertTrue(Permission.isValid(last.getValue()));
        assertFalse(Permission.isValid("unknown:permission"));

        assertNull(Permission.findUnknown(null));
        assertNull(Permission.findUnknown(List.of()));
        assertNull(Permission.findUnknown(List.of("reservations:create", last.getValue())));
        assertEquals("bad", Permission.findUnknown(List.of("reservations:create", "bad", last.getValue())));
    }

    @Test
    void webhookOwnershipBoundary_coversEveryOwnerShape() {
        assertTrue(WebhookSubscription.isSystemOwner(null));
        assertTrue(WebhookSubscription.isSystemOwner(WebhookSubscription.SYSTEM_TENANT));
        assertFalse(WebhookSubscription.isSystemOwner(""));
        assertFalse(WebhookSubscription.isSystemOwner(" "));
        assertFalse(WebhookSubscription.isSystemOwner("tenant-1"));
    }

    @Test
    void eventCategoryTenantBoundary_coversAllCategories() {
        for (EventCategory category : EventCategory.values()) {
            boolean expected = category == EventCategory.BUDGET
                || category == EventCategory.RESERVATION
                || category == EventCategory.TENANT;
            assertEquals(expected, category.isTenantAccessible(), category.name());
        }
        assertEquals(EventCategory.SYSTEM, EventCategory.fromValue("system"));
        assertThrows(IllegalArgumentException.class, () -> EventCategory.fromValue("unknown"));
    }

    @Test
    void wireEnumsDeserializeKnownValuesAndRejectUnknownValues() {
        assertEquals(ActorType.ADMIN_ON_BEHALF_OF,
            ActorType.fromValue("admin_on_behalf_of"));
        assertEquals(RateSpikeMetric.AUTH_FAILURE_RATE,
            RateSpikeMetric.fromValue("auth_failure_rate"));
        assertEquals(ThresholdDirection.FALLING,
            ThresholdDirection.fromValue("falling"));

        assertThrows(IllegalArgumentException.class, () -> ActorType.fromValue("operator"));
        assertThrows(IllegalArgumentException.class, () -> RateSpikeMetric.fromValue("unknown"));
        assertThrows(IllegalArgumentException.class, () -> ThresholdDirection.fromValue("flat"));
    }
}
