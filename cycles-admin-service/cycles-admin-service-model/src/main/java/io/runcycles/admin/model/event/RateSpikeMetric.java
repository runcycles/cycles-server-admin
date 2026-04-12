package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Metric identifier for a reservation/auth rate-spike event. Per spec
 * {@code EventDataRateSpike.metric} enum
 * {@code [denial_rate, expiry_rate, auth_failure_rate]} — snake_case on the wire.
 */
public enum RateSpikeMetric {
    DENIAL_RATE("denial_rate"),
    EXPIRY_RATE("expiry_rate"),
    AUTH_FAILURE_RATE("auth_failure_rate");

    private final String value;

    RateSpikeMetric(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RateSpikeMetric fromValue(String value) {
        for (RateSpikeMetric m : values()) {
            if (m.value.equals(value)) return m;
        }
        throw new IllegalArgumentException("Unknown rate-spike metric: " + value);
    }
}
