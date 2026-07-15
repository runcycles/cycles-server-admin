package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryAction;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryRepairOutcome;
import io.runcycles.admin.data.repository.WebhookRepository.ReconcileResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookCategoryBoundaryReconcilerTest {

    @Mock private WebhookRepository webhookRepository;
    @InjectMocks private WebhookCategoryBoundaryReconciler reconciler;

    private ReconcileResult complete(CategoryBoundaryRepairOutcome... outcomes) {
        return new ReconcileResult(List.of(outcomes), 0);
    }

    private void tune(boolean dryRun, int maxAttempts) {
        ReflectionTestUtils.setField(reconciler, "dryRun", dryRun);
        ReflectionTestUtils.setField(reconciler, "maxAttempts", maxAttempts);
        ReflectionTestUtils.setField(reconciler, "initialBackoffMs", 0L); // no sleep in tests
    }

    @Test
    void run_flagDisabled_skipsReconcile() {
        ReflectionTestUtils.setField(reconciler, "reconcileOnStartup", false);
        reconciler.run(mock(ApplicationArguments.class));
        verifyNoInteractions(webhookRepository);
    }

    @Test
    void run_flagEnabled_startsBackgroundReconcile() {
        ReflectionTestUtils.setField(reconciler, "reconcileOnStartup", true);
        tune(false, 1);
        when(webhookRepository.reconcileTenantCategoryBoundary(false)).thenReturn(complete());

        reconciler.run(mock(ApplicationArguments.class));

        // Runs on a daemon thread; give it a moment to invoke the repo.
        verify(webhookRepository, timeout(2000)).reconcileTenantCategoryBoundary(false);
    }

    @Test
    void reconcile_passesDryRunFlag() {
        tune(true, 1);
        when(webhookRepository.reconcileTenantCategoryBoundary(true)).thenReturn(complete());

        reconciler.reconcile();

        verify(webhookRepository).reconcileTenantCategoryBoundary(true);
    }

    @Test
    void reconcile_returnsOutcomes_andLogsSummary() {
        tune(false, 1);
        when(webhookRepository.reconcileTenantCategoryBoundary(false)).thenReturn(complete(
                new CategoryBoundaryRepairOutcome("wh1", "t1", CategoryBoundaryAction.STRIPPED_ADMIN_SELECTORS, List.of("api_key")),
                new CategoryBoundaryRepairOutcome("wh2", "t2", CategoryBoundaryAction.DISABLED_EMPTY_BOTH, List.of())));

        ReconcileResult r = reconciler.reconcile();

        assertThat(r.repaired()).hasSize(2);
        assertThat(r.isComplete()).isTrue();
    }

    @Test
    void reconcile_noOutcomes_returnsComplete() {
        tune(false, 1);
        when(webhookRepository.reconcileTenantCategoryBoundary(false)).thenReturn(complete());

        assertThat(reconciler.reconcile().repaired()).isEmpty();
    }

    @Test
    void reconcile_dryRun_reportsEveryRepairAction() {
        tune(true, 1);
        when(webhookRepository.reconcileTenantCategoryBoundary(true)).thenReturn(complete(
            new CategoryBoundaryRepairOutcome("wh1", "t1", CategoryBoundaryAction.STRIPPED_ADMIN_SELECTORS, List.of()),
            new CategoryBoundaryRepairOutcome("wh2", "t2", CategoryBoundaryAction.STRIPPED_AND_DISABLED, List.of()),
            new CategoryBoundaryRepairOutcome("wh3", "t3", CategoryBoundaryAction.DISABLED_EMPTY_BOTH, List.of()),
            new CategoryBoundaryRepairOutcome("wh4", null, CategoryBoundaryAction.NORMALIZED_NULL_OWNER, List.of()),
            new CategoryBoundaryRepairOutcome("wh5", null, CategoryBoundaryAction.INDEXED_SYSTEM_MEMBER, List.of())));

        ReconcileResult result = reconciler.reconcile();

        assertThat(result.repaired()).hasSize(5);
        assertThat(result.isComplete()).isTrue();
    }

    @Test
    void reconcileWithRetry_alreadyInterrupted_doesNotTouchRepository() {
        tune(false, 1);
        Thread.currentThread().interrupt();
        try {
            reconciler.reconcileWithRetry();
        } finally {
            // Do not leak interruption into the JUnit worker thread.
            Thread.interrupted();
        }

        verifyNoInteractions(webhookRepository);
    }

    @Test
    void reconcile_repositoryThrows_swallowed_returnsIncomplete() {
        tune(false, 1);
        when(webhookRepository.reconcileTenantCategoryBoundary(false))
                .thenThrow(new RuntimeException("redis down"));

        ReconcileResult r = reconciler.reconcile();

        assertThat(r.repaired()).isEmpty();
        assertThat(r.isComplete()).isFalse(); // failure recorded → caller retries
    }

    @Test
    void reconcileWithRetry_retriesUntilComplete() {
        tune(false, 3);
        when(webhookRepository.reconcileTenantCategoryBoundary(false))
                .thenReturn(new ReconcileResult(List.of(), 1))   // incomplete
                .thenReturn(new ReconcileResult(List.of(), 1))   // incomplete
                .thenReturn(complete());                          // done

        reconciler.reconcileWithRetry();

        verify(webhookRepository, times(3)).reconcileTenantCategoryBoundary(false);
    }

    @Test
    void reconcileWithRetry_givesUpAfterMaxAttempts_alerts() {
        tune(false, 2);
        when(webhookRepository.reconcileTenantCategoryBoundary(false))
                .thenReturn(new ReconcileResult(List.of(), 1)); // always incomplete

        reconciler.reconcileWithRetry();

        // Exactly maxAttempts calls, then gives up (logs ERROR alert) — never throws.
        verify(webhookRepository, times(2)).reconcileTenantCategoryBoundary(false);
    }

    @Test
    void reconcileWithRetry_exhaustedRetries_logsErrorAlert_neverThrows() {
        // Capture the reconciler's ERROR log to assert the alert fires on giving up.
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WebhookCategoryBoundaryReconciler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            tune(false, 2);
            when(webhookRepository.reconcileTenantCategoryBoundary(false))
                    .thenReturn(new ReconcileResult(List.of(), 1));

            reconciler.reconcileWithRetry(); // must not throw

            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getFormattedMessage()).contains("did NOT complete");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void run_thenShutdown_interruptsBackoff_stopsCleanly() {
        ReflectionTestUtils.setField(reconciler, "reconcileOnStartup", true);
        ReflectionTestUtils.setField(reconciler, "dryRun", false);
        ReflectionTestUtils.setField(reconciler, "maxAttempts", 5);
        // Long backoff so the daemon is asleep between retries when we shut down.
        ReflectionTestUtils.setField(reconciler, "initialBackoffMs", 60_000L);
        when(webhookRepository.reconcileTenantCategoryBoundary(false))
                .thenReturn(new ReconcileResult(List.of(), 1)); // always incomplete → will sleep

        reconciler.run(mock(ApplicationArguments.class));
        // Wait until the first pass has run (daemon is now sleeping the 60s backoff).
        verify(webhookRepository, timeout(2000).atLeastOnce()).reconcileTenantCategoryBoundary(false);

        // shutdownNow interrupts the backoff and awaitTermination returns promptly.
        long start = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(reconciler, "shutdown");
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(5_000L); // did NOT wait out the 60s backoff
    }
}
