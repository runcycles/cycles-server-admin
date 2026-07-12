package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source of truth for the tenant-accessible webhook boundary
 * ({@code budget.*}, {@code reservation.*}, {@code tenant.*}): admin-only
 * event types/categories ({@code api_key}, {@code policy}, {@code webhook},
 * {@code system}) may not be placed on a TENANT-owned subscription.
 *
 * <p>Two callers share this component so the boundary cannot drift:
 * <ul>
 *   <li>{@code WebhookTenantController} (create/update on the tenant plane) —
 *       its target is always a concrete tenant, so it validates
 *       unconditionally (governance revision v0.1.25.38).</li>
 *   <li>{@code WebhookAdminController} (create/update on the admin plane) —
 *       validates only when the target subscription's owning tenant is a
 *       CONCRETE tenant, i.e. not the {@link #SYSTEM_TENANT} sentinel. This
 *       closes the admin-plane CARRIER source: an operator / admin-on-behalf-of
 *       could otherwise place admin-only categories on a tenant-owned
 *       subscription, and since {@code matchesEventType} treats categories as
 *       an ADDITIVE union, that tenant (which controls the endpoint URL +
 *       signing secret) would receive admin-only governance/security
 *       telemetry. Governance revision v0.1.25.40 makes this
 *       normative. {@code __system__} subscriptions are NOT tenant-owned —
 *       admin-only categories on them are legitimate system-wide monitoring
 *       and remain allowed.</li>
 * </ul>
 *
 * <p>The boundary itself is derived from {@link EventCategory#isTenantAccessible()}
 * (to which {@link EventType#isTenantAccessible()} delegates), so the
 * type-level and category-level checks and both planes stay in lockstep.
 */
@Component
public class WebhookCategoryBoundaryValidator {

    /** Sentinel owning-tenant for admin-provisioned, non-tenant-owned subscriptions. */
    public static final String SYSTEM_TENANT = WebhookSubscription.SYSTEM_TENANT;

    /**
     * Validate a request's {@code event_types}/{@code event_categories} against
     * the tenant-accessible boundary <b>only when the target is a concrete
     * tenant</b>. No-op for the {@link #SYSTEM_TENANT} sentinel (or a null/blank
     * owner, treated as system): those subscriptions are not tenant-owned.
     * Null arrays are skipped (partial updates validate only what they provide).
     *
     * @param targetTenantId the OWNING tenant of the subscription being written
     *                       (on create: the {@code tenant_id} param; on update:
     *                       the stored subscription's {@code tenant_id})
     */
    public void validateForTarget(String targetTenantId,
                                  List<EventType> eventTypes,
                                  List<EventCategory> eventCategories) {
        if (isSystemTarget(targetTenantId)) {
            return;
        }
        validateEventTypes(eventTypes);
        validateEventCategories(eventCategories);
    }

    /**
     * True when the owning tenant is system-owned (not tenant-owned). Delegates
     * to {@link WebhookSubscription#isSystemOwner} so the write-path validator
     * and the cleanup reconciler share ONE predicate. Per gov v0.1.25.40 only
     * null/omitted and the literal {@code __system__} sentinel are exempt; a
     * blank (whitespace-only) tenant_id is treated as CONCRETE and validated.
     */
    public boolean isSystemTarget(String tenantId) {
        return WebhookSubscription.isSystemOwner(tenantId);
    }

    /**
     * True when this event type is admin-only (outside the tenant-accessible
     * boundary). Single source used by the write-path gate AND the fail-closed
     * dispatch boundary ({@code WebhookDispatchService}) so delivery filtering
     * and validation share one definition.
     */
    public boolean isAdminOnly(EventType type) {
        return type != null && !type.isTenantAccessible();
    }

    /**
     * True when this event category is admin-only. Companion to
     * {@link #isAdminOnly(EventType)} — {@code Event.category} is an independent
     * cross-plane field (not always derived from the type), so the fail-closed
     * dispatch boundary checks BOTH.
     */
    public boolean isAdminOnly(EventCategory category) {
        return category != null && !category.isTenantAccessible();
    }

    /** Reject any admin-only event type. Null list is a no-op. */
    public void validateEventTypes(List<EventType> eventTypes) {
        if (eventTypes == null) return;
        for (EventType type : eventTypes) {
            if (!type.isTenantAccessible()) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    "Event type " + type.getValue()
                        + " is admin-only; tenants can subscribe to budget.*, reservation.*, tenant.* only",
                    400);
            }
        }
    }

    /** Reject any admin-only event category. Null list is a no-op. */
    public void validateEventCategories(List<EventCategory> eventCategories) {
        if (eventCategories == null) return;
        for (EventCategory category : eventCategories) {
            if (!category.isTenantAccessible()) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    "Event category " + category.getValue()
                        + " is admin-only; tenants can subscribe to budget.*, reservation.*, tenant.* only",
                    400);
            }
        }
    }
}
