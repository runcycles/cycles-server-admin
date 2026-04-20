package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Spec v0.1.25.29 CASCADE SEMANTICS — Rule 2 (terminal-owner mutation guard).
 *
 * <p>Any mutating operation on an object whose owning tenant is
 * {@link TenantStatus#CLOSED} returns 409 {@link ErrorCode#TENANT_CLOSED}.
 * The tenant-close cascade (Rule 1) drains each owned-object type into
 * its terminal state at close time; Rule 2 is the defense-in-depth layer
 * that keeps race-window mutations from resurrecting a closed-tenant child.
 *
 * <p>Webhooks deserve special attention: the {@code WebhookSubscription}
 * enum has no truly-terminal value — a {@code DISABLED} subscription can
 * be re-enabled via PATCH. Rule 1 cascades to {@code DISABLED} and Rule 2
 * then prevents re-enable, so {@code DISABLED} becomes effectively-
 * terminal for closed-owner webhooks without widening the enum and
 * breaking wire compatibility.
 *
 * <p>Resolver helpers exist for the types whose mutation paths carry a
 * resource identifier rather than a bare tenant id:
 * <ul>
 *   <li>{@link #assertOpenForScope} — parses {@code tenant:<id>[/...]}
 *       from budget scopes.</li>
 *   <li>{@link #assertOpenForWebhook} — resolves the owning tenant via
 *       {@link WebhookRepository#findById}.</li>
 * </ul>
 * Callers that already hold a tenant id call {@link #assertTenantOpen}
 * directly.
 */
@Service
public class TerminalOwnerMutationGuard {
    @Autowired private TenantRepository tenantRepository;
    @Autowired private WebhookRepository webhookRepository;

    public void assertTenantOpen(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        Tenant t;
        try {
            t = tenantRepository.get(tenantId);
        } catch (GovernanceException e) {
            // If the tenant itself is missing, let the caller's own
            // validation surface the right error — we only care about
            // the CLOSED-terminal case here.
            return;
        }
        if (t != null && t.getStatus() == TenantStatus.CLOSED) {
            throw new GovernanceException(
                ErrorCode.TENANT_CLOSED,
                "Tenant " + tenantId + " is closed; owned objects are read-only",
                409);
        }
    }

    /**
     * Parse the owning tenant id from a budget scope string and assert
     * the tenant is not CLOSED. Scopes follow the canonical grammar
     * {@code tenant:<id>[/<kind>:<id>]*}; anything else is ignored so
     * ScopeValidator's own 400 path remains authoritative for malformed
     * input.
     */
    public void assertOpenForScope(String scope) {
        if (scope == null || scope.isBlank()) return;
        String tenantId = extractTenantIdFromScope(scope);
        if (tenantId != null) assertTenantOpen(tenantId);
    }

    public void assertOpenForWebhook(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) return;
        WebhookSubscription w;
        try {
            w = webhookRepository.findById(subscriptionId);
        } catch (GovernanceException e) {
            return;
        }
        if (w != null) assertTenantOpen(w.getTenantId());
    }

    static String extractTenantIdFromScope(String scope) {
        if (scope == null) return null;
        String s = scope.trim();
        if (!s.startsWith("tenant:")) return null;
        int cut = s.indexOf('/', "tenant:".length());
        String raw = cut < 0 ? s.substring("tenant:".length()) : s.substring("tenant:".length(), cut);
        return raw.isBlank() ? null : raw;
    }
}
