package io.runcycles.admin.model.webhook;
import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.EventType;
import lombok.*;
import java.time.Instant;
/**
 * Cross-plane read schema: runtime's {@code EventEmitterRepository} is
 * the authoritative writer of {@code delivery:*} Redis keys; admin only
 * reads these via {@link io.runcycles.admin.data.repository.WebhookDeliveryRepository}.
 *
 * <p>v0.1.25.32: explicit {@code ignoreUnknown=true} so runtime shipping
 * an additive field in a patch release cannot cause admin's
 * {@code listWebhookDeliveries} to fail with
 * {@code UnrecognizedPropertyException}. Declared at the class level
 * (not inherited from mapper config) so tolerance survives a bare
 * {@code new ObjectMapper()} caller. Admin-owned schemas (subscriptions,
 * configs) keep strict mode — this tolerance is scoped to schemas
 * runtime writes.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookDelivery {
    @JsonProperty("delivery_id") private String deliveryId;
    @JsonProperty("subscription_id") private String subscriptionId;
    @JsonProperty("event_id") private String eventId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("event_type") private EventType eventType;
    @JsonProperty("status") private DeliveryStatus status;
    @JsonProperty("attempted_at") private Instant attemptedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("completed_at") private Instant completedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("attempts") private Integer attempts;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("response_status") private Integer responseStatus;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("response_time_ms") private Integer responseTimeMs;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("error_message") private String errorMessage;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_retry_at") private Instant nextRetryAt;

    /**
     * W3C Trace Context trace-id captured at dispatch time from the originating event.
     * The cycles-server-events sidecar uses this (plus {@link #traceFlags} and
     * {@link #traceparentInboundValid}) to construct an outbound {@code traceparent}
     * header with a fresh span-id on HTTP delivery.
     * <p>Inbound trace-flags are preserved when {@code traceparentInboundValid=true};
     * otherwise the sidecar defaults to {@code 01} per
     * cycles-protocol-v0 §CORRELATION AND TRACING.
     * @since 0.1.25.31
     */
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("trace_id") private String traceId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("trace_flags") private String traceFlags;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("traceparent_inbound_valid") private Boolean traceparentInboundValid;
}
