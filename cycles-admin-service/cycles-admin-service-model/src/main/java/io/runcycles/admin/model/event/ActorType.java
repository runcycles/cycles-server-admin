package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActorType {
    ADMIN("admin"),
    API_KEY("api_key"),
    // v0.1.25.14 (spec v0.1.25.13): admin operator performing a write
    // (createBudget, createPolicy, updatePolicy) on behalf of a tenant via
    // the new dual-auth allowance. Distinct from ADMIN (system-level
    // operations like introspect, audit query) so security review can
    // tell admin-driven tenant-resource creates apart from tenant
    // self-service. Audit log records this; events emitted from these
    // calls also use it in Actor.type.
    ADMIN_ON_BEHALF_OF("admin_on_behalf_of"),
    SYSTEM("system"),
    SCHEDULER("scheduler");

    private final String value;

    ActorType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ActorType fromValue(String value) {
        for (ActorType t : values()) {
            if (t.value.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown actor type: " + value);
    }
}
