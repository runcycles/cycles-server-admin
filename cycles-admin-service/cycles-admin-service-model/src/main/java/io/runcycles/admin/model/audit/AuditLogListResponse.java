package io.runcycles.admin.model.audit;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditLogListResponse {
    @JsonProperty("logs") private List<AuditLogEntry> logs;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor") private String nextCursor;
}
