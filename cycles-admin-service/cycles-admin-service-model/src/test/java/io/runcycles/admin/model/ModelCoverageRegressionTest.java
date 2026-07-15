package io.runcycles.admin.model;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.model.auth.ApiKey;
import io.runcycles.admin.model.event.ActorType;
import io.runcycles.admin.model.event.RateSpikeMetric;
import io.runcycles.admin.model.event.ThresholdDirection;
import io.runcycles.admin.model.shared.Subject;
import io.runcycles.admin.model.tenant.TenantBulkFilter;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.webhook.WebhookBulkFilter;
import io.runcycles.admin.model.webhook.WebhookStatus;
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
