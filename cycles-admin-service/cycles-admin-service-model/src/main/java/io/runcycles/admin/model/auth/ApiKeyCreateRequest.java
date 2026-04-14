package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;
import java.time.Instant;
import java.util.*;
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
public class ApiKeyCreateRequest {
    @NotBlank @JsonProperty("tenant_id") private String tenantId;
    @NotBlank @Size(max = 256) @JsonProperty("name") private String name;
    @Size(max = 1024) @JsonProperty("description") private String description;
    // See rationale on ApiKeyUpdateRequest.permissions — accepted as raw
    // strings so unknown values produce a descriptive 400 instead of
    // Jackson's opaque "Malformed request body".
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonProperty("expires_at") private Instant expiresAt;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
