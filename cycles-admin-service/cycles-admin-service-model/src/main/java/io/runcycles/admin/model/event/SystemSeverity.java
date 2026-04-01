package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SystemSeverity {
    INFO("info"),
    WARNING("warning"),
    CRITICAL("critical");

    private final String value;

    SystemSeverity(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SystemSeverity fromValue(String value) {
        for (SystemSeverity s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown system severity: " + value);
    }
}
