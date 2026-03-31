package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class EventDataTenantLifecycle {

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("previous_status")
    private String previousStatus;

    @JsonProperty("new_status")
    private String newStatus;

    @JsonProperty("changed_fields")
    private List<String> changedFields;
}
