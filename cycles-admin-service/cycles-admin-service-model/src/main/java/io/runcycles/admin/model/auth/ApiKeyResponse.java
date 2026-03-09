package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyResponse {
    @JsonProperty("key_id") private String keyId;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("key_prefix") private String keyPrefix;
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
    public static ApiKeyResponse from(ApiKey key) {
        return ApiKeyResponse.builder()
            .keyId(key.getKeyId())
            .tenantId(key.getTenantId())
            .keyPrefix(key.getKeyPrefix())
            .name(key.getName())
            .description(key.getDescription())
            .permissions(key.getPermissions())
            .scopeFilter(key.getScopeFilter())
            .status(key.getStatus())
            .createdAt(key.getCreatedAt())
            .lastUsedAt(key.getLastUsedAt())
            .expiresAt(key.getExpiresAt())
            .revokedAt(key.getRevokedAt())
            .revokedReason(key.getRevokedReason())
            .metadata(key.getMetadata())
            .build();
    }
}
