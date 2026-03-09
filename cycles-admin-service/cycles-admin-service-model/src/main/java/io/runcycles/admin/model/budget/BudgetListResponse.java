package io.runcycles.admin.model.budget;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetListResponse {
    @JsonProperty("ledgers") private List<BudgetLedger> ledgers;
    @JsonProperty("has_more") private boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
