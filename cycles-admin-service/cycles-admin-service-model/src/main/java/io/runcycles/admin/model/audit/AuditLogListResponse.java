package io.runcycles.admin.model.audit;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogListResponse {
    @JsonProperty("logs") private List<AuditLogEntry> logs;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
