package io.runcycles.admin.model.tenant;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantListResponse {
    @JsonProperty("tenants") private List<Tenant> tenants;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
