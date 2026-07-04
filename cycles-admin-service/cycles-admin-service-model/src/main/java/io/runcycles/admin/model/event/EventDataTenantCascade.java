package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.shared.UnitEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the four {@code *_via_tenant_cascade} events emitted by the
 * tenant-close cascade (spec CASCADE SEMANTICS Rule 1; schema
 * {@code EventDataTenantCascade}, governance spec v0.1.25.35). One shared
 * shape for all four kinds — each event identifies the owned object it
 * transitioned (exactly one of {@code ledger_id} / {@code subscription_id} /
 * {@code key_id} is present, matching the event's category), the status
 * transition, and the cascade provenance.
 *
 * <p>{@code reservation.released_via_tenant_cascade} is a LEDGER-LEVEL
 * AGGREGATE: reservation objects live on the runtime plane, so the admin
 * plane emits one event per closed budget whose {@code reserved > 0} at
 * close time, carrying the drained amount as {@code released_amount} — it
 * identifies the budget via {@code ledger_id}, not an individual
 * reservation, and carries no {@code new_status} of its own.
 *
 * <p>Statuses are strings (not a shared enum) because the transition spans
 * three different status vocabularies: BudgetStatus → CLOSED,
 * WebhookStatus → DISABLED, ApiKeyStatus → REVOKED.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class EventDataTenantCascade {

    @JsonProperty("ledger_id")
    private String ledgerId;

    @JsonProperty("subscription_id")
    private String subscriptionId;

    @JsonProperty("key_id")
    private String keyId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private UnitEnum unit;

    @JsonProperty("prior_status")
    private String priorStatus;

    @JsonProperty("new_status")
    private String newStatus;

    @JsonProperty("released_amount")
    private Long releasedAmount;

    @JsonProperty("cascade_reason")
    private String cascadeReason;
}
