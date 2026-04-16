package io.runcycles.admin.model.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Derived boolean capabilities for dashboard UI.
 *
 * The 8 required `view_*` fields are always present (explicit false, never
 * omitted) per spec v0.1.25.9+.
 *
 * The 7 optional fields added in v0.1.25.19 per spec v0.1.25.15
 * (`view_reservations`, `manage_budgets`, `manage_policies`, `manage_webhooks`,
 * `manage_tenants`, `manage_api_keys`, `manage_reservations`) are serialized
 * only when set. Using boxed `Boolean` + per-field `@JsonInclude(NON_NULL)`
 * overrides the class-level `ALWAYS`, so legacy admin-only responses that
 * don't populate them stay wire-identical to pre-v0.1.25.19 output.
 *
 * Capability-derivation rules are NORMATIVE under `auth_type=tenant` per
 * spec yaml:3105-3166. `AuthController.deriveTenantCapabilities` implements
 * the table; admin-plane caps (view_tenants, view_api_keys, view_audit,
 * view_overview, manage_tenants, manage_api_keys) are forced to `false`
 * under tenant auth regardless of admin:* permissions.
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

    // --- v0.1.25.19 optional fields (spec v0.1.25.15). Absent unless set. ---

    @JsonProperty("view_reservations")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean viewReservations;

    @JsonProperty("manage_budgets")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean manageBudgets;

    @JsonProperty("manage_policies")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean managePolicies;

    @JsonProperty("manage_webhooks")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean manageWebhooks;

    @JsonProperty("manage_tenants")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean manageTenants;

    @JsonProperty("manage_api_keys")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean manageApiKeys;

    @JsonProperty("manage_reservations")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean manageReservations;
}
