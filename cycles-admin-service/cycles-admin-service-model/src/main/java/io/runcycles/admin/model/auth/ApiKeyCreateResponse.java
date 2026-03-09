package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKeyCreateResponse {
    @JsonProperty("key_id") private String keyId;
    @JsonProperty("key_secret") private String keySecret;
    @JsonProperty("key_prefix") private String keyPrefix;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("created_at") private Instant createdAt;
    @JsonProperty("expires_at") private Instant expiresAt;
}
