package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class EventDataBudgetLifecycle {

    @JsonProperty("ledger_id")
    private String ledgerId;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private UnitEnum unit;

    @JsonProperty("operation")
    private String operation;

    @JsonProperty("previous_state")
    private BudgetState previousState;

    @JsonProperty("new_state")
    private BudgetState newState;

    @JsonProperty("reason")
    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BudgetState {

        @JsonProperty("allocated")
        private Long allocated;

        @JsonProperty("remaining")
        private Long remaining;

        @JsonProperty("debt")
        private Long debt;

        @JsonProperty("status")
        private String status;
    }
}
