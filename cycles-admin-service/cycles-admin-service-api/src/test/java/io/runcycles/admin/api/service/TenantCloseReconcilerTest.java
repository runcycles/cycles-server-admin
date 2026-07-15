package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.TenantCloseWorkRepository;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

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
    void reconcileClosedTenants_discardsPrepareWhenParentFlipDidNotCommit() {
        Tenant active = Tenant.builder().tenantId("tenant-active")
            .status(TenantStatus.ACTIVE).build();
        when(workRepository.dueTenantIds(100)).thenReturn(List.of("tenant-active"));
        when(tenantRepository.get("tenant-active")).thenReturn(active);

        reconciler.reconcileClosedTenants();

        verify(workRepository).discard("tenant-active");
        verifyNoInteractions(cascadeService);
    }

    @Test
    void reconcileClosedTenants_disabledDoesNoWork() {
        ReflectionTestUtils.setField(reconciler, "enabled", false);

        reconciler.reconcileClosedTenants();

        verifyNoInteractions(tenantRepository, workRepository, cascadeService);
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
