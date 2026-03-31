package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventListResponse {

    @JsonProperty("events")
    private List<Event> events;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("next_cursor")
    private String nextCursor;

    @JsonProperty("has_more")
    private boolean hasMore;
}
