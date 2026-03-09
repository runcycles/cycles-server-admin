package io.runcycles.admin.model.budget;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class BalanceQueryResponse {
    @NotNull @JsonProperty("balances") private List<BudgetLedger> balances;
    @JsonProperty("has_more") private Boolean hasMore;
    @JsonProperty("next_cursor") private String nextCursor;
}
