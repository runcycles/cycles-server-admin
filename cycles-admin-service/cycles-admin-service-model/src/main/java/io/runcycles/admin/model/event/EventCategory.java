package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EventCategory {
    BUDGET("budget"),
    TENANT("tenant"),
    API_KEY("api_key"),
    POLICY("policy"),
    RESERVATION("reservation"),
    WEBHOOK("webhook"),
    SYSTEM("system");

    private final String value;

    EventCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * The tenant-plane accessibility boundary: tenants can subscribe to /
     * query {@code budget.*}, {@code reservation.*}, {@code tenant.*} only;
     * API_KEY / POLICY / WEBHOOK / SYSTEM are admin-only (governance spec;
     * revision v0.1.25.38 extends the rule to webhook
     * {@code event_categories}). Single source of truth for the boundary -
     * {@link EventType#isTenantAccessible()} delegates here, so the
     * type-level and category-level checks can never drift.
     */
    public boolean isTenantAccessible() {
        return this == BUDGET || this == RESERVATION || this == TENANT;
    }

    @JsonCreator
    public static EventCategory fromValue(String value) {
        for (EventCategory c : values()) {
            if (c.value.equals(value)) return c;
        }
        throw new IllegalArgumentException("Unknown event category: " + value);
    }
}
