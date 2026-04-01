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
public class EventDataBurnRateAnomaly {

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private UnitEnum unit;

    @JsonProperty("current_rate")
    private Double currentRate;

    @JsonProperty("baseline_rate")
    private Double baselineRate;

    @JsonProperty("multiplier")
    private Double multiplier;

    @JsonProperty("window_seconds")
    private Integer windowSeconds;

    @JsonProperty("projected_exhaustion_minutes")
    private Double projectedExhaustionMinutes;
}
