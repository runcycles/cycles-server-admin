package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.List;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class ApiKeyUpdateRequest {
    @Size(max = 256) @JsonProperty("name") private String name;
    @Size(max = 1024) @JsonProperty("description") private String description;
    @JsonProperty("permissions") private List<Permission> permissions;
    @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonProperty("metadata") private Map<String, Object> metadata;

    /** Returns permissions as wire-format strings, or null if unset. */
    public List<String> getPermissionsAsStrings() {
        return permissions == null ? null : permissions.stream().map(Permission::getValue).toList();
    }
}
