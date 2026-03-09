package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKeyValidationResponse {
    @JsonProperty("valid") private Boolean valid;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("key_id") private String keyId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("permissions") private List<String> permissions;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("expires_at") private Instant expiresAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reason") private String reason;
}
