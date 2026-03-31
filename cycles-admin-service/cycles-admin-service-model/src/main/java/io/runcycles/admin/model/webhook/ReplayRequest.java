package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.EventType;
import lombok.*;
import java.time.Instant;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReplayRequest {
    @JsonProperty("from") private Instant from;
    @JsonProperty("to") private Instant to;
    @JsonProperty("event_types") private List<EventType> eventTypes;
    @JsonProperty("max_events") @Builder.Default private Integer maxEvents = 100;
}
