package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryAction;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryRepairOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time (idempotent) startup cleanup for #209 — the (d2) half of the fix.
 * On boot it finds TENANT-owned webhook subscriptions carrying admin-only
 * {@code event_categories} (placed there via the admin plane before the
 * boundary was enforced in this release), plus legacy empty-both match-ALL
 * rows, and <b>DISABLEs</b> them. See
 * {@link WebhookRepository#reconcileTenantCategoryBoundary(boolean)} for the
 * per-row rules and why it disables (conservative) rather than silently strips.
 *
 * <p>Implemented as a startup {@link ApplicationRunner} rather than a new admin
 * endpoint: the migration touches no public wire surface (an undocumented
 * endpoint would fail the OpenAPI contract-diff check), and it mirrors the
 * repo's existing background-maintenance precedent (the {@code @Scheduled}
 * audit-index sweep and the {@code CommandLineRunner} startup banner).
 * Disabling is reversible and idempotent, so re-running on every boot is safe;
 * operators can turn it off after the one-time run with
 * {@code webhook.category-boundary.reconcile-on-startup=false}, or set
 * {@code webhook.category-boundary.reconcile-dry-run=true} to only REPORT the
 * offenders (log, no mutation) for review before the disabling pass.
 */
@Component
public class WebhookCategoryBoundaryReconciler implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookCategoryBoundaryReconciler.class);

    @Autowired private WebhookRepository webhookRepository;

    @Value("${webhook.category-boundary.reconcile-on-startup:true}")
    private boolean reconcileOnStartup;

    /** When true, report offenders (log) without disabling any row. */
    @Value("${webhook.category-boundary.reconcile-dry-run:false}")
    private boolean dryRun;

    @Override
    public void run(ApplicationArguments args) {
        if (!reconcileOnStartup) {
            LOG.info("Webhook category-boundary reconcile: skipped (reconcile-on-startup=false)");
            return;
        }
        reconcile();
    }

    /**
     * Run the reconcile and log a summary. Returns the per-row outcomes (empty
     * when there was nothing to repair). Never throws — a cleanup failure must
     * not block startup.
     */
    public List<CategoryBoundaryRepairOutcome> reconcile() {
        try {
            List<CategoryBoundaryRepairOutcome> outcomes = webhookRepository.reconcileTenantCategoryBoundary(dryRun);
            if (outcomes.isEmpty()) {
                LOG.info("Webhook category-boundary reconcile: no offender rows (#209 cleanup, idempotent, dry_run={})", dryRun);
            } else {
                long adminCats = outcomes.stream().filter(o -> o.action() == CategoryBoundaryAction.DISABLED_ADMIN_CATEGORIES).count();
                long emptyBoth = outcomes.stream().filter(o -> o.action() == CategoryBoundaryAction.DISABLED_EMPTY_BOTH).count();
                LOG.warn("Webhook category-boundary reconcile: {} {} offender row(s) (#209) — admin_categories={} empty_both={}. Genuine per-tenant admin monitoring should move to a __system__ subscription filtered by event_categories, with per-tenant selection done client-side on the envelope tenant_id (admin events are null-scoped, so a scope_filter would exclude them).",
                    dryRun ? "REPORTED (dry-run, no rows changed):" : "DISABLED",
                    outcomes.size(), adminCats, emptyBoth);
            }
            return outcomes;
        } catch (Exception e) {
            LOG.error("Webhook category-boundary reconcile failed (#209 cleanup); startup continues", e);
            return List.of();
        }
    }
}
