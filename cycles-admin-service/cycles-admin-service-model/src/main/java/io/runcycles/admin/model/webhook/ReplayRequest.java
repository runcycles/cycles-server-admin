package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.EventType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;
import java.time.Instant;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReplayRequest {
    @JsonProperty("from") private Instant from;
    @JsonProperty("to") private Instant to;
    @JsonProperty("event_types") private List<EventType> eventTypes;
    // Governance spec: minimum 1, maximum 1000, default 100. Out-of-range values
    // are REJECTED (400) by @Valid on the controller, not silently clamped.
    @Min(1) @Max(1000) @JsonProperty("max_events") @Builder.Default private Integer maxEvents = 100;
}
