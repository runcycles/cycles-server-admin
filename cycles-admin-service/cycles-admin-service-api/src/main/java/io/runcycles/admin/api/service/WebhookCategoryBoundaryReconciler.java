package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryAction;
import io.runcycles.admin.data.repository.WebhookRepository.ReconcileResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort startup HYGIENE for #209 — the (d2) half of the fix. It reconciles
 * webhook subscriptions that violate the tenant-accessible boundary
 * (concrete-tenant rows carrying admin-only event_types/categories → strip those
 * selectors; empty-both match-ALL rows → disable; null-owner rows → normalize to
 * {@code __system__}). See
 * {@link WebhookRepository#reconcileTenantCategoryBoundary(boolean)} for the
 * per-row rules.
 *
 * <p><b>This is not the security mechanism.</b> The durable confidentiality
 * guarantee is the fail-closed DISPATCH boundary
 * ({@code WebhookDispatchService}) — a concrete-tenant subscription never
 * receives admin-only events, immediately and unconditionally. This reconciler
 * only brings STORAGE in line with that already-enforced delivery behavior, so
 * it is safe for it to be best-effort: it runs on a managed background thread
 * (startup/readiness is never blocked), retries an incomplete pass (row error or
 * CAS miss) with exponential backoff, and on exhausted retries logs a loud
 * {@code ERROR} and lets the service stay up (a hygiene bug must not brick the
 * service; delivery is already safe). No new API surface — an undocumented
 * endpoint would fail the OpenAPI contract-diff check.
 *
 * <p>Config: {@code webhook.category-boundary.reconcile-on-startup} (default
 * {@code true}), {@code webhook.category-boundary.reconcile-dry-run} (default
 * {@code false} — {@code true} REPORTS the actions in the logs without mutating).
 */
@Component
public class WebhookCategoryBoundaryReconciler implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookCategoryBoundaryReconciler.class);

    @Autowired private WebhookRepository webhookRepository;

    @Value("${webhook.category-boundary.reconcile-on-startup:true}")
    private boolean reconcileOnStartup;

    /** When true, report actions (log) without mutating any row. */
    @Value("${webhook.category-boundary.reconcile-dry-run:false}")
    private boolean dryRun;

    /** Max attempts before giving up (and alerting) on an incomplete pass. */
    @Value("${webhook.category-boundary.reconcile-max-attempts:5}")
    private int maxAttempts;

    /** Initial backoff between retries; doubles each attempt, capped at 30s. */
    @Value("${webhook.category-boundary.reconcile-initial-backoff-ms:1000}")
    private long initialBackoffMs;

    /**
     * Managed single-thread executor for the background sweep. A managed
     * executor (not a raw {@code new Thread}) is interrupted and awaited on
     * Spring context shutdown, so the sweep stops cleanly instead of leaking a
     * thread that keeps sleeping through a retry backoff.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "webhook-category-boundary-reconcile");
            t.setDaemon(true);
            return t;
        }
    });

    @Override
    public void run(ApplicationArguments args) {
        if (!reconcileOnStartup) {
            LOG.info("Webhook category-boundary reconcile: skipped (reconcile-on-startup=false)");
            return;
        }
        // Background: never block startup or readiness on this hygiene sweep.
        executor.submit(this::reconcileWithRetry);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow(); // interrupts an in-flight retry backoff
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Run the sweep, retrying with exponential backoff until a pass completes
     * (no failures) or attempts are exhausted. Idempotent and never throws.
     */
    public void reconcileWithRetry() {
        long backoff = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
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
        LOG.error("Webhook category-boundary reconcile did NOT complete after {} attempts (#209); service is up and delivery is already safe (the dispatch boundary blocks admin-only events to concrete-tenant subs), but storage hygiene may be incomplete until the next restart or manual re-run. ALERT.",
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
                LOG.info("Webhook category-boundary reconcile: nothing to reconcile (#209 hygiene, idempotent, dry_run={})", dryRun);
            } else {
                long stripped = result.repaired().stream().filter(o -> o.action() == CategoryBoundaryAction.STRIPPED_ADMIN_SELECTORS).count();
                long strippedDisabled = result.repaired().stream().filter(o -> o.action() == CategoryBoundaryAction.STRIPPED_AND_DISABLED).count();
                long disabledEmptyBoth = result.repaired().stream().filter(o -> o.action() == CategoryBoundaryAction.DISABLED_EMPTY_BOTH).count();
                long normalized = result.repaired().stream().filter(o -> o.action() == CategoryBoundaryAction.NORMALIZED_NULL_OWNER).count();
                LOG.warn("Webhook category-boundary reconcile: {} {} row(s) (#209 hygiene), failures={} — stripped={} stripped_and_disabled={} disabled_empty_both={} normalized_null_owner={}",
                    dryRun ? "REPORTED (dry-run, no rows changed):" : "repaired",
                    result.repaired().size(), result.failures(), stripped, strippedDisabled, disabledEmptyBoth, normalized);
            }
            return result;
        } catch (Exception e) {
            LOG.error("Webhook category-boundary reconcile failed (#209 hygiene); service continues", e);
            return new ReconcileResult(java.util.List.of(), 1);
        }
    }
}
