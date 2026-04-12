package io.runcycles.admin.model.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Derived boolean capabilities for dashboard UI. Per spec, all 8 fields are
 * always present (explicit false, never omitted).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Capabilities {
    @JsonProperty("view_overview") private boolean viewOverview;
    @JsonProperty("view_budgets") private boolean viewBudgets;
    @JsonProperty("view_events") private boolean viewEvents;
    @JsonProperty("view_webhooks") private boolean viewWebhooks;
    @JsonProperty("view_audit") private boolean viewAudit;
    @JsonProperty("view_tenants") private boolean viewTenants;
    @JsonProperty("view_api_keys") private boolean viewApiKeys;
    @JsonProperty("view_policies") private boolean viewPolicies;
}
