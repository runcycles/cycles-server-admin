package io.runcycles.admin.model.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AuthIntrospectResponse {
    @JsonProperty("authenticated") private boolean authenticated;
    @JsonProperty("auth_type") private String authType;
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("capabilities") private Capabilities capabilities;

    /**
     * REQUIRED when auth_type=tenant — the tenant this API key is bound to.
     * MUST be absent when auth_type=admin (admin keys have no effective tenant).
     * Added in v0.1.25.19 per spec v0.1.25.15. Per-field NON_NULL overrides
     * the class-level ALWAYS so admin-shape responses still omit this field.
     */
    @JsonProperty("tenant_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String tenantId;

    /**
     * OPTIONAL (tenant auth only). Present and non-empty when the API key has
     * scope restrictions (see ApiKey.scope_filter). Absent or empty means no
     * scope narrowing. MUST be absent when auth_type=admin.
     * Added in v0.1.25.19 per spec v0.1.25.15.
     */
    @JsonProperty("scope_filter")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> scopeFilter;
}
