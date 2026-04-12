package io.runcycles.admin.model.event;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical mapping from {@link EventType} to the Java class that models its
 * {@code data} payload per the admin spec.
 *
 * <p>Makes the event-type → payload-class relationship explicit data rather
 * than implicit convention. Used by {@code EventPayloadContractTest} to
 * enforce:
 * <ul>
 *   <li>every event type has a documented payload class (or is explicitly
 *       marked payload-less);</li>
 *   <li>the payload class is a real, Jackson-roundtrippable class.</li>
 * </ul>
 *
 * <p>A future extension can wire this into {@code EventService.emit} to
 * reject malformed payloads at the producer boundary, turning what is
 * currently producer discipline into a machine-checked contract.
 *
 * <p>When a new {@link EventType} is added, register its payload class here
 * alongside the spec schema reference in the event-type javadoc / test.
 */
public final class EventPayloadTypeMapping {

    private static final Map<EventType, Class<?>> MAPPING;

    static {
        EnumMap<EventType, Class<?>> m = new EnumMap<>(EventType.class);

        // Budget lifecycle (create/update/fund/debit/reset/debt-repay/freeze/unfreeze/close)
        m.put(EventType.BUDGET_CREATED, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_UPDATED, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_FUNDED, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_DEBITED, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_RESET, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_DEBT_REPAID, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_FROZEN, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_UNFROZEN, EventDataBudgetLifecycle.class);
        m.put(EventType.BUDGET_CLOSED, EventDataBudgetLifecycle.class);

        // Budget threshold / overage / debt
        m.put(EventType.BUDGET_THRESHOLD_CROSSED, EventDataBudgetThreshold.class);
        m.put(EventType.BUDGET_EXHAUSTED, EventDataBudgetThreshold.class);
        m.put(EventType.BUDGET_OVER_LIMIT_ENTERED, EventDataBudgetOverLimit.class);
        m.put(EventType.BUDGET_OVER_LIMIT_EXITED, EventDataBudgetOverLimit.class);
        m.put(EventType.BUDGET_DEBT_INCURRED, EventDataBudgetDebtIncurred.class);
        m.put(EventType.BUDGET_BURN_RATE_ANOMALY, EventDataBurnRateAnomaly.class);

        // Reservations
        m.put(EventType.RESERVATION_DENIED, EventDataReservationDenied.class);
        m.put(EventType.RESERVATION_DENIAL_RATE_SPIKE, EventDataRateSpike.class);
        m.put(EventType.RESERVATION_EXPIRED, EventDataReservationExpired.class);
        m.put(EventType.RESERVATION_EXPIRY_RATE_SPIKE, EventDataRateSpike.class);
        m.put(EventType.RESERVATION_COMMIT_OVERAGE, EventDataCommitOverage.class);

        // Tenant lifecycle
        m.put(EventType.TENANT_CREATED, EventDataTenantLifecycle.class);
        m.put(EventType.TENANT_UPDATED, EventDataTenantLifecycle.class);
        m.put(EventType.TENANT_SUSPENDED, EventDataTenantLifecycle.class);
        m.put(EventType.TENANT_REACTIVATED, EventDataTenantLifecycle.class);
        m.put(EventType.TENANT_CLOSED, EventDataTenantLifecycle.class);
        m.put(EventType.TENANT_SETTINGS_CHANGED, EventDataTenantLifecycle.class);

        // API keys
        m.put(EventType.API_KEY_CREATED, EventDataApiKey.class);
        m.put(EventType.API_KEY_REVOKED, EventDataApiKey.class);
        m.put(EventType.API_KEY_EXPIRED, EventDataApiKey.class);
        m.put(EventType.API_KEY_PERMISSIONS_CHANGED, EventDataApiKey.class);
        m.put(EventType.API_KEY_AUTH_FAILED, EventDataApiKey.class);
        m.put(EventType.API_KEY_AUTH_FAILURE_RATE_SPIKE, EventDataRateSpike.class);

        // Policies
        m.put(EventType.POLICY_CREATED, EventDataPolicy.class);
        m.put(EventType.POLICY_UPDATED, EventDataPolicy.class);
        m.put(EventType.POLICY_DELETED, EventDataPolicy.class);

        // System
        m.put(EventType.SYSTEM_STORE_CONNECTION_LOST, EventDataSystem.class);
        m.put(EventType.SYSTEM_STORE_CONNECTION_RESTORED, EventDataSystem.class);
        m.put(EventType.SYSTEM_HIGH_LATENCY, EventDataSystem.class);
        m.put(EventType.SYSTEM_WEBHOOK_DELIVERY_FAILED, EventDataSystem.class);
        m.put(EventType.SYSTEM_WEBHOOK_TEST, EventDataSystem.class);

        MAPPING = Collections.unmodifiableMap(m);
    }

    private EventPayloadTypeMapping() {}

    /** Returns the payload class for the given event type, or empty if none. */
    public static Optional<Class<?>> payloadClass(EventType type) {
        return Optional.ofNullable(MAPPING.get(type));
    }

    /** Returns an unmodifiable view of the full mapping. */
    public static Map<EventType, Class<?>> all() {
        return MAPPING;
    }
}
