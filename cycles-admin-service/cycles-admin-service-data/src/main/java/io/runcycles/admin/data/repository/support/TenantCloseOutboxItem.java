package io.runcycles.admin.data.repository.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Durable description of one child mutation performed by a tenant-close cascade. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantCloseOutboxItem(
        String itemId,
        String tenantId,
        String resourceType,
        String resourceId,
        String name,
        String scope,
        String unit,
        String priorStatus,
        long releasedReservedAmount) {
}
