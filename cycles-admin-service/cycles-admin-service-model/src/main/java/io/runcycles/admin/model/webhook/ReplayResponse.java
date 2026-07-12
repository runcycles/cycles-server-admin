package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReplayResponse {
    @JsonProperty("replay_id") private String replayId;
    /**
     * The number of selected events ACCEPTED onto the dispatch queue (governance
     * #130 best-effort contract). Selection is complete for the window, but
     * enqueue is best-effort: on a transient backend failure this may be fewer
     * than the events selected (the shortfall is logged server-side). Replay is
     * NOT idempotent — delivery IDs are random, so a retry may duplicate
     * already-queued deliveries.
     */
    @JsonProperty("events_queued") private Integer eventsQueued;
    @JsonProperty("estimated_completion_seconds") private Integer estimatedCompletionSeconds;
}
