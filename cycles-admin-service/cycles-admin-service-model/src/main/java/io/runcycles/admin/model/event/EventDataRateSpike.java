package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDataRateSpike {

    @JsonProperty("metric")
    private String metric;

    @JsonProperty("current_rate")
    private Double currentRate;

    @JsonProperty("threshold_rate")
    private Double thresholdRate;

    @JsonProperty("window_seconds")
    private Integer windowSeconds;

    @JsonProperty("sample_count")
    private Integer sampleCount;
}
