package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.UnitEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDataBudgetDebtIncurred {

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private UnitEnum unit;

    @JsonProperty("reservation_id")
    private String reservationId;

    @JsonProperty("debt_incurred")
    private Long debtIncurred;

    @JsonProperty("total_debt")
    private Long totalDebt;

    @JsonProperty("overdraft_limit")
    private Long overdraftLimit;

    @JsonProperty("overage_policy")
    private CommitOveragePolicy overagePolicy;
}
