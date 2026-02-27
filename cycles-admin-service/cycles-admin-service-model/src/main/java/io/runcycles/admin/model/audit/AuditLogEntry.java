package io.runcycles.admin.model.audit;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogEntry {
    @JsonProperty("log_id") private String logId;
    @JsonProperty("timestamp") private Instant timestamp;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("key_id") private String keyId;
    @JsonProperty("user_agent") private String userAgent;
    @JsonProperty("source_ip") private String sourceIp;
    @JsonProperty("operation") private String operation;
    @JsonProperty("request_id") private String requestId;
    @JsonProperty("status") private Integer status;
    @JsonProperty("error_code") private String errorCode;
    @JsonProperty("subject") private Object subject;
    @JsonProperty("action") private Object action;
    @JsonProperty("amount") private Object amount;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
