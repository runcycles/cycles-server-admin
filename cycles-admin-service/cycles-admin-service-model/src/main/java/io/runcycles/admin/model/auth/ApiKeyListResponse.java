package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKeyListResponse {
    @JsonProperty("keys") private List<ApiKeyResponse> keys;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor") private String nextCursor;
}
