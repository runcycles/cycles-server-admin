package io.runcycles.admin.model.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
public class ApiKey {
    @JsonProperty("key_id") private String keyId;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("key_prefix") private String keyPrefix;
    // Internal field — required for Redis serialization; never returned in API responses (use ApiKeyResponse DTO)
    @JsonProperty("key_hash") private String keyHash;
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    // Lenient deserialization: legacy records written by the pre-v0.1.25.17
    // revoke path may contain {} instead of [] due to Redis cjson empty-array
    // round-trip. The deserializer accepts both shapes so the admin list
    // endpoint doesn't silently drop those records. See LenientStringListDeserializer.
    @JsonProperty("permissions")
    @JsonDeserialize(using = LenientStringListDeserializer.class)
    private List<String> permissions;
    @JsonProperty("scope_filter")
    @JsonDeserialize(using = LenientStringListDeserializer.class)
    private List<String> scopeFilter;
    @JsonProperty("status") private ApiKeyStatus status;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("last_used_at") private Instant lastUsedAt;
    @JsonProperty("expires_at") private Instant expiresAt;
    @JsonProperty("revoked_at") private Instant revokedAt;
    @JsonProperty("revoked_reason") private String revokedReason;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
