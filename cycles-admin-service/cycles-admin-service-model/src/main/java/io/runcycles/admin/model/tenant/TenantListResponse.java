package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TenantListResponse {
    @JsonProperty("tenants") private List<Tenant> tenants;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor") private String nextCursor;
}
