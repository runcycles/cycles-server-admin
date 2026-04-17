package io.runcycles.admin.model.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Sort direction for admin list endpoints (spec v0.1.25.20). Wire format
 * is lowercase ("asc" / "desc") — matches the OpenAPI enum verbatim so
 * clients can pass the raw query-param string without case massaging.
 */
public enum SortDirection {
    ASC("asc"),
    DESC("desc");

    private final String wire;

    SortDirection(String wire) { this.wire = wire; }

    @JsonValue
    public String getWire() { return wire; }

    /**
     * Case-insensitive parse, so `asc`, `ASC`, `Asc` all resolve. Unknown
     * values throw IllegalArgumentException — controllers catch and map
     * to a 400 with a spec-consistent error message.
     */
    @JsonCreator
    public static SortDirection fromWire(String value) {
        if (value == null) return null;
        for (SortDirection d : values()) {
            if (d.wire.equalsIgnoreCase(value)) return d;
        }
        throw new IllegalArgumentException(
            "Invalid sort_dir '" + value + "'; expected one of: asc, desc");
    }
}
