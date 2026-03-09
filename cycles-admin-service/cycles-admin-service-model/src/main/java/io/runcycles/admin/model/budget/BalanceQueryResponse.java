package io.runcycles.admin.model.budget;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BalanceQueryResponse {
    @JsonProperty("balances") private List<BudgetLedger> balances;
    @JsonProperty("has_more") private boolean hasMore;
}
