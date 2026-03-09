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
    @JsonProperty("log_id") private String logId;
    @JsonProperty("timestamp") private Instant timestamp;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("key_id") private String keyId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("user_agent") private String userAgent;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("source_ip") private String sourceIp;
    @JsonProperty("operation") private String operation;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("request_id") private String requestId;
    @JsonProperty("status") private Integer status;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("error_code") private String errorCode;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("subject") private Subject subject;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("action") private Action action;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("amount") private Amount amount;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("metadata") private Map<String, Object> metadata;
}
