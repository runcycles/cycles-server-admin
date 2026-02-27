package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.Amount;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
public class BudgetFundingRequest {
    @NotNull @JsonProperty("operation") private FundingOperation operation;
    @NotNull @Valid @JsonProperty("amount") private Amount amount;
    @Size(max = 512) @JsonProperty("reason") private String reason;
    @NotBlank @JsonProperty("idempotency_key") private String idempotencyKey;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
