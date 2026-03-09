package io.runcycles.admin.model.policy;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PolicyListResponse {
    @JsonProperty("policies") private List<Policy> policies;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor") private String nextCursor;
}
