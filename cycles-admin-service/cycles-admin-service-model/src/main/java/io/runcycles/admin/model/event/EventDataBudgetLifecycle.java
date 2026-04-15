package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = false)
public class EventDataBudgetLifecycle {

    @JsonProperty("ledger_id")
    private String ledgerId;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private UnitEnum unit;

    @JsonProperty("operation")
    private BudgetOperation operation;

    @JsonProperty("previous_state")
    private BudgetState previousState;

    @JsonProperty("new_state")
    private BudgetState newState;

    /**
     * Only populated on {@code budget.reset_spent}. True when the request
     * explicitly supplied a {@code spent} field (migration, proration,
     * compensation, or correction), false when spent defaulted to 0
     * (routine period rollover).
     * Added in spec v0.1.25.17.
     */
    @JsonProperty("spent_override_provided")
    private Boolean spentOverrideProvided;

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

        /**
         * Spent component of the budget state. Populated on
         * {@code budget.reset_spent} pre/post snapshots so consumers
         * can see the transition. Added in spec v0.1.25.17.
         */
        @JsonProperty("spent")
        private Long spent;

        /**
         * Reserved component of the budget state. Populated on
         * {@code budget.reset_spent} pre/post snapshots so consumers
         * can see what carried forward across the period boundary.
         * Added in spec v0.1.25.17.
         */
        @JsonProperty("reserved")
        private Long reserved;

        @JsonProperty("status")
        private String status;
    }
}
