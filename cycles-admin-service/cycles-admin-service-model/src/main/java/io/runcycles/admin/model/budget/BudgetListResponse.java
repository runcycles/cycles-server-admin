package io.runcycles.admin.model.budget;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BudgetListResponse {
    @JsonProperty("ledgers") private List<BudgetLedger> ledgers;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor") private String nextCursor;
}
