package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReplayResponse {
    @JsonProperty("replay_id") private String replayId;
    @JsonProperty("events_queued") private Integer eventsQueued;
    @JsonProperty("estimated_completion_seconds") private Integer estimatedCompletionSeconds;
}
