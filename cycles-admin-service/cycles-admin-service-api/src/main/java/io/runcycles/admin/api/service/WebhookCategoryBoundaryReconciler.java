package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryAction;
import io.runcycles.admin.data.repository.WebhookRepository.ReconcileResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-time (idempotent, resumable) startup cleanup for #209 — the (d2) half of
 * the fix. It finds webhook subscriptions that violate the tenant-accessible
 * boundary (concrete-tenant rows carrying admin-only event_types/categories,
 * plus legacy empty-both match-ALL rows) and DISABLEs them. See
 * {@link WebhookRepository#reconcileTenantCategoryBoundary(boolean)} for the
 * per-row rules and why it disables (conservative) rather than silently strips.
 *
 * <p>Robustness / readiness posture (finding 7):
 * <ul>
 *   <li>The pass runs on a <b>background daemon thread</b> — startup is never
 *       blocked, and readiness is NOT gated on the migration completing. A
 *       migration bug must not brick the service, and the create/update gate
 *       already stops NEW offenders; legacy offenders simply remain
 *       DISABLED-pending until a clean pass finishes.</li>
 *   <li>An incomplete pass (row error, or a compare-and-set miss from a
 *       concurrent update) is <b>retried with exponential backoff</b>. The
 *       sweep is idempotent, so retries are safe. If retries are exhausted
 *       without a clean pass, a loud {@code ERROR} alert is emitted and the
 *       service stays up.</li>
 * </ul>
 * No new API surface (an undocumented endpoint would fail the OpenAPI
 * contract-diff check); mirrors the repo's {@code @Scheduled} audit-sweep /
 * {@code CommandLineRunner} precedent. Config:
 * {@code webhook.category-boundary.reconcile-on-startup} (default {@code true}),
 * {@code webhook.category-boundary.reconcile-dry-run} (default {@code false} —
 * {@code true} REPORTS offenders in the logs without disabling anything).
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

    /** Max attempts before giving up (and alerting) on an incomplete pass. */
    @Value("${webhook.category-boundary.reconcile-max-attempts:5}")
    private int maxAttempts;

    /** Initial backoff between retries; doubles each attempt, capped at 30s. */
    @Value("${webhook.category-boundary.reconcile-initial-backoff-ms:1000}")
    private long initialBackoffMs;

    @Override
    public void run(ApplicationArguments args) {
        if (!reconcileOnStartup) {
            LOG.info("Webhook category-boundary reconcile: skipped (reconcile-on-startup=false)");
            return;
        }
        // Background daemon thread: never block startup or readiness on the migration.
        Thread t = new Thread(this::reconcileWithRetry, "webhook-category-boundary-reconcile");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Run the sweep, retrying with exponential backoff until a pass completes
     * (no failures) or attempts are exhausted. Idempotent and never throws.
     */
    public void reconcileWithRetry() {
        long backoff = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ReconcileResult result = reconcile();
            if (result.isComplete()) {
                return;
            }
            LOG.warn("Webhook category-boundary reconcile incomplete (#209): attempt={} failures={} — retrying",
                attempt, result.failures());
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000L);
            }
        }
        LOG.error("Webhook category-boundary reconcile did NOT complete after {} attempts (#209); service is up and NEW offenders are blocked by the write-path gate, but legacy offenders may remain until the next restart or a manual re-run. ALERT.",
            maxAttempts);
    }

    /**
     * A single reconcile pass. Logs a summary and returns its result (with the
     * failure count that drives {@link #reconcileWithRetry()}). Public so tests
     * exercise it directly; never throws.
     */
    public ReconcileResult reconcile() {
        try {
            ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(dryRun);
            if (result.repaired().isEmpty() && result.isComplete()) {
                LOG.info("Webhook category-boundary reconcile: no offender rows (#209 cleanup, idempotent, dry_run={})", dryRun);
            } else {
                long adminSel = result.repaired().stream().filter(o -> o.action() == CategoryBoundaryAction.DISABLED_ADMIN_SELECTORS).count();
                long emptyBoth = result.repaired().stream().filter(o -> o.action() == CategoryBoundaryAction.DISABLED_EMPTY_BOTH).count();
                LOG.warn("Webhook category-boundary reconcile: {} {} offender row(s) (#209), failures={} — admin_selectors={} empty_both={}. Genuine per-tenant admin monitoring should move to a __system__ subscription filtered by event_categories (select tenant client-side on the envelope tenant_id).",
                    dryRun ? "REPORTED (dry-run, no rows changed):" : "DISABLED",
                    result.repaired().size(), result.failures(), adminSel, emptyBoth);
            }
            return result;
        } catch (Exception e) {
            LOG.error("Webhook category-boundary reconcile failed (#209 cleanup); startup continues", e);
            return new ReconcileResult(java.util.List.of(), 1);
        }
    }
}
