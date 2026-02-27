package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.Amount;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.time.Instant;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetFundingResponse {
    @JsonProperty("operation") private String operation;
    @JsonProperty("previous_allocated") private Amount previousAllocated;
    @JsonProperty("new_allocated") private Amount newAllocated;
    @JsonProperty("previous_remaining") private Amount previousRemaining;
    @JsonProperty("new_remaining") private Amount newRemaining;
    @JsonProperty("previous_debt") private Amount previousDebt;
    @JsonProperty("new_debt") private Amount newDebt;
    @JsonProperty("timestamp") private Instant timestamp;
}
