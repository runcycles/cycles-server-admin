package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKey {
    @JsonProperty("key_id") private String keyId;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("key_prefix") private String keyPrefix;
    @JsonProperty(value = "key_hash", access = JsonProperty.Access.WRITE_ONLY) private String keyHash;
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonProperty("status") private ApiKeyStatus status;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("last_used_at") private Instant lastUsedAt;
    @JsonProperty("expires_at") private Instant expiresAt;
    @JsonProperty("revoked_at") private Instant revokedAt;
    @JsonProperty("revoked_reason") private String revokedReason;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
