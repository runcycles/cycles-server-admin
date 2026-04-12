package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Direction of a budget threshold crossing. Per spec
 * {@code EventDataBudgetThreshold.direction} enum {@code [rising, falling]} — lowercase
 * on the wire, so Jackson serializes via {@link #getValue()} and rejects unknowns
 * via {@link #fromValue(String)}.
 */
public enum ThresholdDirection {
    RISING("rising"),
    FALLING("falling");

    private final String value;

    ThresholdDirection(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ThresholdDirection fromValue(String value) {
        for (ThresholdDirection d : values()) {
            if (d.value.equals(value)) return d;
        }
        throw new IllegalArgumentException("Unknown direction: " + value);
    }
}
