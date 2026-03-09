package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
public class TenantUpdateRequest {
    @JsonProperty("name") private String name;
    @JsonProperty("status") private TenantStatus status;
    @Size(max = 32) @JsonProperty("metadata") private Map<String, String> metadata;
}
