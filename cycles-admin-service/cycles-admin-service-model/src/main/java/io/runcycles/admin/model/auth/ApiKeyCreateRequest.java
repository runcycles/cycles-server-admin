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
    @JsonProperty("permissions") private List<Permission> permissions;
    @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonProperty("expires_at") private Instant expiresAt;
    @JsonProperty("metadata") private Map<String, Object> metadata;

    /** Returns permissions as wire-format strings, or null if unset. */
    public List<String> getPermissionsAsStrings() {
        return permissions == null ? null : permissions.stream().map(Permission::getValue).toList();
    }
}
