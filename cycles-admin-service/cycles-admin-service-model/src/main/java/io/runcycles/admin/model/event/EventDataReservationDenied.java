package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.shared.UnitEnum;
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
@JsonIgnoreProperties(ignoreUnknown = false)
public class EventDataReservationDenied {

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("unit")
    private UnitEnum unit;

    @JsonProperty("reason_code")
    private String reasonCode;

    @JsonProperty("requested_amount")
    private Long requestedAmount;

    @JsonProperty("remaining")
    private Long remaining;

    @JsonProperty("action")
    private Map<String, Object> action;

    @JsonProperty("subject")
    private Map<String, Object> subject;

    // v0.1.25.8: which policy caused the denial (optional)
    @JsonProperty("policy_id")
    private String policyId;

    // v0.1.25.8: extension-defined structured context (optional, populated by v0.1.26+ servers)
    @JsonProperty("deny_detail")
    private Map<String, Object> denyDetail;
}
