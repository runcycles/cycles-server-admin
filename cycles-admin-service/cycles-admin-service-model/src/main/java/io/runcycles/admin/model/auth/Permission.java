package io.runcycles.admin.model.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * API key permission values, per cycles-governance-admin-v0.1.25 spec (schemas.Permission).
 * Serializes to/from the spec's colon-separated string form (e.g. {@code "budgets:write"}).
 * Jackson rejects unknown strings on deserialization via {@link #fromValue(String)}.
 */
public enum Permission {
    // Tenant runtime permissions
    RESERVATIONS_CREATE("reservations:create"),
    RESERVATIONS_COMMIT("reservations:commit"),
    RESERVATIONS_RELEASE("reservations:release"),
    RESERVATIONS_EXTEND("reservations:extend"),
    RESERVATIONS_LIST("reservations:list"),
    BALANCES_READ("balances:read"),
    BUDGETS_READ("budgets:read"),
    BUDGETS_WRITE("budgets:write"),
    POLICIES_READ("policies:read"),
    POLICIES_WRITE("policies:write"),
    WEBHOOKS_READ("webhooks:read"),
    WEBHOOKS_WRITE("webhooks:write"),
    EVENTS_READ("events:read"),
    // Admin wildcard permissions (backward compatible)
    ADMIN_READ("admin:read"),
    ADMIN_WRITE("admin:write"),
    // Granular admin permissions
    ADMIN_TENANTS_READ("admin:tenants:read"),
    ADMIN_TENANTS_WRITE("admin:tenants:write"),
    ADMIN_BUDGETS_READ("admin:budgets:read"),
    ADMIN_BUDGETS_WRITE("admin:budgets:write"),
    ADMIN_POLICIES_READ("admin:policies:read"),
    ADMIN_POLICIES_WRITE("admin:policies:write"),
    ADMIN_APIKEYS_READ("admin:apikeys:read"),
    ADMIN_APIKEYS_WRITE("admin:apikeys:write"),
    ADMIN_WEBHOOKS_READ("admin:webhooks:read"),
    ADMIN_WEBHOOKS_WRITE("admin:webhooks:write"),
    ADMIN_EVENTS_READ("admin:events:read"),
    ADMIN_AUDIT_READ("admin:audit:read");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Permission fromValue(String value) {
        for (Permission p : values()) {
            if (p.value.equals(value)) return p;
        }
        throw new IllegalArgumentException("Unknown permission: " + value);
    }

    /** True iff the given string matches a known permission value. */
    public static boolean isValid(String value) {
        if (value == null) return false;
        for (Permission p : values()) {
            if (p.value.equals(value)) return true;
        }
        return false;
    }

    /**
     * Returns the first permission string in the input that is not a known
     * {@link Permission} value, or {@code null} if all are valid. Used by the
     * create/update controllers to produce a 400 that names the exact offender
     * instead of the generic "Malformed request body" Jackson would emit when
     * binding to a strict {@code List<Permission>}.
     */
    public static String findUnknown(java.util.List<String> values) {
        if (values == null) return null;
        for (String v : values) {
            if (!isValid(v)) return v;
        }
        return null;
    }
}
