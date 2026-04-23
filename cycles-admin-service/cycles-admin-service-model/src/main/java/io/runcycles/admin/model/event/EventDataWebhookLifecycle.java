package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.webhook.WebhookStatus;
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
public class EventDataWebhookLifecycle {

    @JsonProperty("subscription_id")
    private String subscriptionId;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("previous_status")
    private WebhookStatus previousStatus;

    @JsonProperty("new_status")
    private WebhookStatus newStatus;

    @JsonProperty("changed_fields")
    private List<String> changedFields;

    @JsonProperty("disable_reason")
    private String disableReason;
}
