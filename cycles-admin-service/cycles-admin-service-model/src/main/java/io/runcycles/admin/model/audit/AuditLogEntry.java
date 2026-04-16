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
     * Sentinel tenant id used on failure audit entries where no authenticated
     * tenant is bound to the request (missing / invalid API key, pre-auth
     * path-traversal rejection, admin-key-only requests that fail before the
     * controller runs). Preserves the spec's {@code tenant_id: required}
     * invariant without a wire-format change, and gives ops a queryable
     * filter value: {@code GET /v1/admin/audit/logs?tenant_id=%3Cunauthenticated%3E}.
     *
     * <p>Tenants use the grammar {@code ^[a-z0-9-]+$} per AuditController
     * validation, so the angle brackets guarantee no collision with any
     * real tenant id.
     *
     * <p>Defined at the model layer so both the failure-side writer
     * ({@code AuditFailureService}) and the data-layer repository
     * ({@code AuditRepository} — which branches on this value for tiered
     * TTL) can reference it without a cross-layer dependency.
     *
     * @since 0.1.25.20
     */
    public static final String UNAUTHENTICATED_TENANT = "<unauthenticated>";

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
