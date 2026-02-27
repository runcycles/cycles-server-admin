package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;
import java.time.Instant;
import java.util.*;
@Data @NoArgsConstructor @AllArgsConstructor
public class ApiKeyCreateRequest {
    @NotBlank @JsonProperty("tenant_id") private String tenantId;
    @NotBlank @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonProperty("expires_at") private Instant expiresAt;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
