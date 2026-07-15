package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.TenantCloseWorkRepository;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Drives interrupted Mode-B tenant-close cascades to bounded convergence. */
@Service
public class TenantCloseReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(TenantCloseReconciler.class);
    private final TenantRepository tenantRepository;
    private final TenantCloseWorkRepository workRepository;
    private final TenantCloseCascadeService cascadeService;
    private final Counter incompleteCounter;
    private final Counter errorCounter;

    @Value("${tenant-close.reconciler.enabled:true}")
    private boolean enabled;

    @Value("${tenant-close.reconciler.max-tenants-per-run:100}")
    private int maxTenantsPerRun;

    public TenantCloseReconciler(TenantRepository tenantRepository,
                                 TenantCloseWorkRepository workRepository,
                                 TenantCloseCascadeService cascadeService,
                                 MeterRegistry meterRegistry) {
        this.tenantRepository = tenantRepository;
        this.workRepository = workRepository;
        this.cascadeService = cascadeService;
        this.incompleteCounter = Counter.builder("cycles_admin_tenant_close_reconcile_incomplete_total")
            .description("Closed-tenant reconciliation attempts that still had failed child rows")
            .register(meterRegistry);
        this.errorCounter = Counter.builder("cycles_admin_tenant_close_reconcile_errors_total")
            .description("Closed-tenant reconciliation attempts that failed before completion")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${tenant-close.reconciler.interval-ms:300000}")
    public void reconcileClosedTenants() {
        if (!enabled || maxTenantsPerRun <= 0) return;
        for (String tenantId : workRepository.dueTenantIds(maxTenantsPerRun)) {
            try {
                Tenant tenant = tenantRepository.get(tenantId);
                if (tenant.getStatus() != TenantStatus.CLOSED) {
                    // A prepare-before-flip whose flip failed is stale work.
                    workRepository.discard(tenantId);
                    continue;
                }
                var result = cascadeService.cascade(tenantId, null);
                if (!result.complete()) {
                    incompleteCounter.increment();
                    LOG.warn("Tenant-close reconciliation remains incomplete: tenant_id={} failed_resources={}",
                        tenantId, result.failedResources());
                }
            } catch (Exception e) {
                errorCounter.increment();
                workRepository.reschedule(tenantId, 30_000L);
                LOG.error("Tenant-close reconciliation failed: tenant_id={}", tenantId, e);
            }
        }
    }
}
