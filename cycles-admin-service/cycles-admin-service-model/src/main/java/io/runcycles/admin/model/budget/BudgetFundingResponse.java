package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.Amount;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.time.Instant;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BudgetFundingResponse {
    @NotNull @JsonProperty("operation") private FundingOperation operation;
    @NotNull @JsonProperty("previous_allocated") private Amount previousAllocated;
    @NotNull @JsonProperty("new_allocated") private Amount newAllocated;
    @NotNull @JsonProperty("previous_remaining") private Amount previousRemaining;
    @NotNull @JsonProperty("new_remaining") private Amount newRemaining;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("previous_debt") private Amount previousDebt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("new_debt") private Amount newDebt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("timestamp") private Instant timestamp;
}
