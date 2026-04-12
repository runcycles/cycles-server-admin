package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.tenant.TenantStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class EventDataTenantLifecycle {

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("previous_status")
    private TenantStatus previousStatus;

    @JsonProperty("new_status")
    private TenantStatus newStatus;

    @JsonProperty("changed_fields")
    private List<String> changedFields;
}
