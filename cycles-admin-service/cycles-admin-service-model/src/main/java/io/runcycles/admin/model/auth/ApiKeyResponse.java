package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKeyResponse {
    // Required per spec: key_id, tenant_id, key_prefix, status, created_at, expires_at
    @JsonProperty("key_id") private String keyId;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("key_prefix") private String keyPrefix;
    @JsonProperty("status") private ApiKeyStatus status;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("expires_at") private Instant expiresAt;
    // Optional fields (omit when null)
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("name") private String name;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("description") private String description;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("permissions") private List<String> permissions;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("last_used_at") private Instant lastUsedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("revoked_at") private Instant revokedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("revoked_reason") private String revokedReason;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("metadata") private Map<String, Object> metadata;
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
