package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDataSystem {

    @JsonProperty("component")
    private String component;

    @JsonProperty("message")
    private String message;

    @JsonProperty("severity")
    private SystemSeverity severity;

    @JsonProperty("details")
    private Map<String, Object> details;
}
