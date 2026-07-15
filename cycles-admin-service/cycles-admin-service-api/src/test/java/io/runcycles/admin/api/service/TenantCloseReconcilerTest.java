package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.TenantCloseWorkRepository;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantCloseReconcilerTest {
    @Mock private TenantRepository tenantRepository;
    @Mock private TenantCloseWorkRepository workRepository;
    @Mock private TenantCloseCascadeService cascadeService;
    private SimpleMeterRegistry meterRegistry;
    private TenantCloseReconciler reconciler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new TenantCloseReconciler(
            tenantRepository, workRepository, cascadeService, meterRegistry);
        ReflectionTestUtils.setField(reconciler, "enabled", true);
        ReflectionTestUtils.setField(reconciler, "maxTenantsPerRun", 100);
    }

    @Test
    void reconcileClosedTenants_retriesOnlyClosedRows() {
        Tenant closed = Tenant.builder().tenantId("tenant-closed").status(TenantStatus.CLOSED).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-closed"));
        when(tenantRepository.get("tenant-closed")).thenReturn(closed);
        when(cascadeService.cascade("tenant-closed", null))
            .thenReturn(TenantCloseCascadeService.CascadeResult.empty());

        reconciler.reconcileClosedTenants();

        verify(cascadeService).cascade("tenant-closed", null);
        verify(workRepository).dueTenantIds(100);
    }

    @Test
    void reconcileClosedTenants_recordsIncompleteRowsAndContinuesAfterErrors() {
        Tenant first = Tenant.builder().tenantId("tenant-1").status(TenantStatus.CLOSED).build();
        Tenant second = Tenant.builder().tenantId("tenant-2").status(TenantStatus.CLOSED).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-1", "tenant-2"));
        when(tenantRepository.get("tenant-1")).thenReturn(first);
        when(tenantRepository.get("tenant-2")).thenReturn(second);
        when(cascadeService.cascade("tenant-1", null))
            .thenReturn(new TenantCloseCascadeService.CascadeResult(
                0, 0, 0, 0, List.of("budget:led-1", "api_key:key-1")));
        when(cascadeService.cascade("tenant-2", null))
            .thenThrow(new IllegalStateException("Redis unavailable"));

        reconciler.reconcileClosedTenants();

        assertThat(meterRegistry.get("cycles_admin_tenant_close_reconcile_incomplete_total")
            .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("cycles_admin_tenant_close_reconcile_errors_total")
            .counter().count()).isEqualTo(1.0);
        verify(cascadeService).cascade("tenant-2", null);
        verify(workRepository).reschedule("tenant-2", 30_000L);
    }

    @Test
    void reconcileClosedTenants_healthyLeaseContentionDoesNotPolluteIncompleteMetric() {
        Tenant closed = Tenant.builder().tenantId("tenant-1").status(TenantStatus.CLOSED).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-1"));
        when(tenantRepository.get("tenant-1")).thenReturn(closed);
        when(cascadeService.cascade("tenant-1", null))
            .thenReturn(TenantCloseCascadeService.CascadeResult.leaseInProgress());

        reconciler.reconcileClosedTenants();

        assertThat(meterRegistry.get("cycles_admin_tenant_close_reconcile_incomplete_total")
            .counter().count()).isZero();
        assertThat(meterRegistry.get("cycles_admin_tenant_close_reconcile_errors_total")
            .counter().count()).isZero();
    }

    @Test
    void reconcileClosedTenants_discardsPrepareWhenParentFlipDidNotCommit() {
        Tenant active = Tenant.builder().tenantId("tenant-active")
            .status(TenantStatus.ACTIVE).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-active"));
        when(tenantRepository.get("tenant-active")).thenReturn(active);
        when(workRepository.findIntent("tenant-active")).thenReturn(Optional.of(
            new TenantCloseWorkRepository.Intent("tenant-active", "request", "trace",
                "correlation", "127.0.0.1", "test",
                Instant.now().minusMillis(
                    TenantCloseWorkRepository.PREPARE_GRACE_MILLIS + 1L))));
        when(workRepository.tryAcquireLease("tenant-active")).thenReturn("cleanup-token");
        when(workRepository.discardIfUncommitted("tenant-active")).thenReturn(true);

        reconciler.reconcileClosedTenants();

        verify(workRepository).discardIfUncommitted("tenant-active");
        verify(workRepository).releaseLease("tenant-active", "cleanup-token");
        verifyNoInteractions(cascadeService);
    }

    @Test
    void reconcileClosedTenants_discardsAgedIntentWhenTenantIsMissing() {
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-missing"));
        when(tenantRepository.get("tenant-missing"))
            .thenThrow(GovernanceException.tenantNotFound("tenant-missing"));
        when(workRepository.findIntent("tenant-missing")).thenReturn(Optional.of(
            new TenantCloseWorkRepository.Intent("tenant-missing", "request", "trace",
                "correlation", "127.0.0.1", "test",
                Instant.now().minusMillis(
                    TenantCloseWorkRepository.PREPARE_GRACE_MILLIS + 1L))));
        when(workRepository.tryAcquireLease("tenant-missing")).thenReturn("cleanup-token");
        when(workRepository.discardIfUncommitted("tenant-missing")).thenReturn(true);

        reconciler.reconcileClosedTenants();

        verify(workRepository).discardIfUncommitted("tenant-missing");
        verify(workRepository).releaseLease("tenant-missing", "cleanup-token");
        verifyNoInteractions(cascadeService);
    }

    @Test
    void reconcileClosedTenants_preservesFreshPrepareDuringFlipWindow() {
        Tenant active = Tenant.builder().tenantId("tenant-active")
            .status(TenantStatus.ACTIVE).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-active"));
        when(tenantRepository.get("tenant-active")).thenReturn(active);
        when(workRepository.findIntent("tenant-active")).thenReturn(Optional.of(
            new TenantCloseWorkRepository.Intent("tenant-active", "request", "trace",
                "correlation", "127.0.0.1", "test", Instant.now())));

        reconciler.reconcileClosedTenants();

        verify(workRepository).reschedule(eq("tenant-active"), longThat(delay -> delay > 0));
        verify(workRepository, never()).discardIfUncommitted(anyString());
        verifyNoInteractions(cascadeService);
    }

    @Test
    void reconcileClosedTenants_disabledDoesNoWork() {
        ReflectionTestUtils.setField(reconciler, "enabled", false);

        reconciler.reconcileClosedTenants();

        verifyNoInteractions(tenantRepository, workRepository, cascadeService);
    }

    @Test
    void reconcileClosedTenants_nonPositiveBatchSizeDoesNoWork() {
        ReflectionTestUtils.setField(reconciler, "maxTenantsPerRun", 0);

        reconciler.reconcileClosedTenants();

        verifyNoInteractions(tenantRepository, workRepository, cascadeService);
    }

    @Test
    void reconcileClosedTenants_propagatesNonNotFoundLookupIntoRetryHandling() {
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-broken"));
        when(tenantRepository.get("tenant-broken")).thenThrow(
            new GovernanceException(ErrorCode.INTERNAL_ERROR, "lookup failed", 500));

        reconciler.reconcileClosedTenants();

        verify(workRepository).reschedule("tenant-broken", 30_000L);
        assertThat(meterRegistry.get("cycles_admin_tenant_close_reconcile_errors_total")
            .counter().count()).isEqualTo(1.0);
    }

    @Test
    void reconcileClosedTenants_ignoresNonClosedTenantWithoutIntent() {
        Tenant active = Tenant.builder().tenantId("tenant-active")
            .status(TenantStatus.ACTIVE).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-active"));
        when(tenantRepository.get("tenant-active")).thenReturn(active);
        when(workRepository.findIntent("tenant-active")).thenReturn(Optional.empty());

        reconciler.reconcileClosedTenants();

        verify(workRepository, never()).tryAcquireLease(anyString());
        verifyNoInteractions(cascadeService);
    }

    @Test
    void reconcileClosedTenants_nullIntentTimestampAndHeldCleanupLeaseReschedule() {
        Tenant active = Tenant.builder().tenantId("tenant-active")
            .status(TenantStatus.ACTIVE).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-active"));
        when(tenantRepository.get("tenant-active")).thenReturn(active);
        when(workRepository.findIntent("tenant-active")).thenReturn(Optional.of(
            new TenantCloseWorkRepository.Intent("tenant-active", "request", "trace",
                "correlation", "127.0.0.1", "test", null)));
        when(workRepository.tryAcquireLease("tenant-active")).thenReturn(null);

        reconciler.reconcileClosedTenants();

        verify(workRepository).reschedule("tenant-active", 1_000L);
        verify(workRepository, never()).discardIfUncommitted(anyString());
    }

    @Test
    void reconcileClosedTenants_reschedulesWhenAtomicDiscardSeesACommit() {
        Tenant active = Tenant.builder().tenantId("tenant-active")
            .status(TenantStatus.ACTIVE).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-active"));
        when(tenantRepository.get("tenant-active")).thenReturn(active);
        when(workRepository.findIntent("tenant-active")).thenReturn(Optional.of(
            new TenantCloseWorkRepository.Intent("tenant-active", "request", "trace",
                "correlation", "127.0.0.1", "test",
                Instant.now().minusMillis(TenantCloseWorkRepository.PREPARE_GRACE_MILLIS + 1L))));
        when(workRepository.tryAcquireLease("tenant-active")).thenReturn("cleanup-token");
        when(workRepository.discardIfUncommitted("tenant-active")).thenReturn(false);

        reconciler.reconcileClosedTenants();

        verify(workRepository).reschedule("tenant-active", 1_000L);
        verify(workRepository).releaseLease("tenant-active", "cleanup-token");
    }

    @Test
    void reconcileClosedTenants_usesDurableDueQueueAcrossRuns() {
        ReflectionTestUtils.setField(reconciler, "maxTenantsPerRun", 1);
        Tenant first = Tenant.builder().tenantId("tenant-1").status(TenantStatus.CLOSED).build();
        Tenant second = Tenant.builder().tenantId("tenant-2").status(TenantStatus.CLOSED).build();
        when(workRepository.dueTenantIds(1))
            .thenReturn(List.of("tenant-1"), List.of("tenant-2"));
        when(tenantRepository.get("tenant-1")).thenReturn(first);
        when(tenantRepository.get("tenant-2")).thenReturn(second);
        when(cascadeService.cascade(anyString(), isNull()))
            .thenReturn(TenantCloseCascadeService.CascadeResult.empty());

        reconciler.reconcileClosedTenants();
        reconciler.reconcileClosedTenants();

        verify(cascadeService).cascade("tenant-1", null);
        verify(cascadeService).cascade("tenant-2", null);
    }
}
