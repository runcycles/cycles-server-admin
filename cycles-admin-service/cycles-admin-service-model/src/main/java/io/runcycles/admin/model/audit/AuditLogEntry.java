package io.runcycles.admin.model.audit;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.shared.Action;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.Subject;
import lombok.*;
import java.time.Instant;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditLogEntry {

    /**
     * Sentinel tenant id for pre-auth failures (missing/invalid/revoked
     * tenant key, missing admin key, path-traversal rejection — anything
     * that fails before the controller runs). Subject to the
     * unauthenticated-tier TTL and the DDoS-sampling gate.
     *
     * <p>Queryable: {@code GET /v1/admin/audit/logs?tenant_id=__unauth__}
     * — double-underscore delimiters are URL-safe so no percent encoding
     * is needed. Tenant grammar {@code ^[a-z0-9-]+$} excludes underscores,
     * guaranteeing no collision with real tenant ids.
     *
     * <p>Defined at the model layer so both the failure-side writer
     * ({@code AuditFailureService}) and the data-layer repository
     * ({@code AuditRepository} — which branches on this value for tiered
     * TTL) can reference it without a cross-layer dependency.
     *
     * @since 0.1.25.20 (introduced as {@code <unauthenticated>})
     * @since 0.1.25.28 (renamed to {@code __unauth__} alongside the
     *         {@link #ADMIN_TENANT} split)
     */
    public static final String UNAUTH_TENANT = "__unauth__";

    /**
     * Sentinel tenant id for requests authenticated via {@code AdminKeyAuth}
     * (platform-admin credential) that are not scoped to any single tenant.
     * Covers governance ops, cross-tenant reads, and admin-plane 4xx/5xx
     * failures.
     *
     * <p>Distinct from {@link #UNAUTH_TENANT} because admin-plane activity
     * is a high-signal security event, not DDoS-amplifiable noise. Entries
     * with this sentinel persist at the authenticated-tier TTL and are
     * never sampled out.
     *
     * <p>Queryable: {@code GET /v1/admin/audit/logs?tenant_id=__admin__}.
     *
     * @since 0.1.25.28
     */
    public static final String ADMIN_TENANT = "__admin__";

    /**
     * Legacy sentinel value written by v0.1.25.20..v0.1.25.27. No longer
     * emitted, but historical Redis rows carrying this value MUST still
     * route to the unauthenticated-tier TTL so they age out on the same
     * schedule as fresh {@link #UNAUTH_TENANT} rows. Data-layer code in
     * {@code AuditRepository} checks this legacy constant alongside
     * {@code UNAUTH_TENANT}; no other code should reference it.
     *
     * @since 0.1.25.28 (introduced to preserve TTL routing for legacy
     *         rows after the sentinel rename).
     */
    public static final String LEGACY_UNAUTHENTICATED_TENANT = "<unauthenticated>";

    @JsonProperty("log_id") private String logId;
    @JsonProperty("timestamp") private Instant timestamp;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("key_id") private String keyId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("user_agent") private String userAgent;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("source_ip") private String sourceIp;
    @JsonProperty("operation") private String operation;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resource_type") private String resourceType;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resource_id") private String resourceId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("request_id") private String requestId;
    @JsonProperty("status") private Integer status;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("error_code") private String errorCode;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("subject") private Subject subject;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("action") private Action action;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("amount") private Amount amount;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("metadata") private Map<String, Object> metadata;
}
