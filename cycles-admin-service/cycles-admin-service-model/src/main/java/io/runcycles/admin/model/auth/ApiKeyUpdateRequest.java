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
    // Accept permissions as raw strings rather than binding to the Permission
    // enum at Jackson level. An unknown value now produces a clear
    // GovernanceException("Unrecognized permission: <value>", 400) in the
    // repository, which is far more actionable than Jackson's opaque
    // "Malformed request body" 400 (which also obscured which of N
    // permissions was the offender, a pain when the dashboard round-trips
    // the full list on every edit). Validation still rejects unknown
    // values — spec-compliant per yaml line 4383.
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
