package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Cross-plane read schema: runtime's {@code EventEmitterRepository} is
 * the authoritative writer of {@code event:*} Redis keys; admin only
 * reads these via {@link io.runcycles.admin.data.repository.EventRepository}.
 *
 * <p>v0.1.25.32: explicit {@code ignoreUnknown=true} so runtime shipping
 * an additive field in a patch release cannot cause admin's
 * {@code listEvents} to fail with {@code UnrecognizedPropertyException}
 * until admin lockstep-updates the POJO. Declared at the class level
 * rather than relying on the mapper's global {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * default so tolerance is guaranteed even when a caller constructs a
 * bare {@code new ObjectMapper()}. Strict mode on admin-owned schemas
 * (package siblings) stays — this tolerance is scoped to schemas
 * runtime writes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class Event {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private EventType eventType;

    @JsonProperty("category")
    private EventCategory category;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("scope")
    private String scope;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("actor")
    private Actor actor;

    @JsonProperty("source")
    private String source;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("data")
    private Map<String, Object> data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("correlation_id")
    private String correlationId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("request_id")
    private String requestId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("trace_id")
    private String traceId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("metadata")
    private Map<String, Object> metadata;
}
