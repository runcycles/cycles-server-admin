package io.runcycles.admin.model.budget;
import io.runcycles.admin.model.shared.Amount;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
public class BudgetFundingRequest {
    @NotNull @JsonProperty("operation") private FundingOperation operation;
    @NotNull @Valid @JsonProperty("amount") private Amount amount;

    /**
     * Only honoured for {@link FundingOperation#RESET_SPENT}; ignored for other operations.
     * When omitted on a RESET_SPENT, spent defaults to 0 (fresh period).
     * When supplied, sets spent to this exact value — must be {@code >= 0}.
     * The amount unit must match the request's {@link #amount} unit (enforced in the controller).
     */
    @Valid @JsonProperty("spent") private Amount spent;

    @Size(max = 512) @JsonProperty("reason") private String reason;
    @Size(min = 1, max = 256) @JsonProperty("idempotency_key") private String idempotencyKey;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
