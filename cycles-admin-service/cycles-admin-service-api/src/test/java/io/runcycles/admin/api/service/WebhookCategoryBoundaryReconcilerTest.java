package io.runcycles.admin.api.service;

import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryAction;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryRepairOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookCategoryBoundaryReconcilerTest {

    @Mock private WebhookRepository webhookRepository;
    @InjectMocks private WebhookCategoryBoundaryReconciler reconciler;

    @Test
    void run_flagEnabled_invokesReconcile_notDryRunByDefault() {
        ReflectionTestUtils.setField(reconciler, "reconcileOnStartup", true);
        ReflectionTestUtils.setField(reconciler, "dryRun", false);
        when(webhookRepository.reconcileTenantCategoryBoundary(false)).thenReturn(List.of());

        reconciler.run(mock(ApplicationArguments.class));

        verify(webhookRepository).reconcileTenantCategoryBoundary(false);
    }

    @Test
    void run_dryRunFlag_passesDryRunTrue() {
        ReflectionTestUtils.setField(reconciler, "reconcileOnStartup", true);
        ReflectionTestUtils.setField(reconciler, "dryRun", true);
        when(webhookRepository.reconcileTenantCategoryBoundary(true)).thenReturn(List.of());

        reconciler.run(mock(ApplicationArguments.class));

        verify(webhookRepository).reconcileTenantCategoryBoundary(true);
    }

    @Test
    void run_flagDisabled_skipsReconcile() {
        ReflectionTestUtils.setField(reconciler, "reconcileOnStartup", false);

        reconciler.run(mock(ApplicationArguments.class));

        verifyNoInteractions(webhookRepository);
    }

    @Test
    void reconcile_returnsOutcomes_andLogsSummary() {
        ReflectionTestUtils.setField(reconciler, "dryRun", false);
        when(webhookRepository.reconcileTenantCategoryBoundary(false)).thenReturn(List.of(
                new CategoryBoundaryRepairOutcome("wh1", "t1", CategoryBoundaryAction.DISABLED_ADMIN_CATEGORIES, List.of("api_key")),
                new CategoryBoundaryRepairOutcome("wh2", "t2", CategoryBoundaryAction.DISABLED_ADMIN_CATEGORIES, List.of("policy")),
                new CategoryBoundaryRepairOutcome("wh3", "t3", CategoryBoundaryAction.DISABLED_EMPTY_BOTH, List.of())));

        List<CategoryBoundaryRepairOutcome> outcomes = reconciler.reconcile();

        assertThat(outcomes).hasSize(3);
    }

    @Test
    void reconcile_noOutcomes_returnsEmpty() {
        ReflectionTestUtils.setField(reconciler, "dryRun", false);
        when(webhookRepository.reconcileTenantCategoryBoundary(false)).thenReturn(List.of());

        assertThat(reconciler.reconcile()).isEmpty();
    }

    @Test
    void reconcile_repositoryThrows_swallowedReturnsEmpty() {
        // A cleanup failure must never block startup.
        ReflectionTestUtils.setField(reconciler, "dryRun", false);
        when(webhookRepository.reconcileTenantCategoryBoundary(false))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(reconciler.reconcile()).isEmpty();
    }
}
