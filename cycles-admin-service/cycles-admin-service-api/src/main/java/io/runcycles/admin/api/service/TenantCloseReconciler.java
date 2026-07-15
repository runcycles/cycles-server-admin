package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.TenantCloseWorkRepository;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

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
                Tenant tenant = findTenantOrNull(tenantId);
                if (tenant == null || tenant.getStatus() != TenantStatus.CLOSED) {
                    cleanupUncommittedIntent(tenantId);
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

    private Tenant findTenantOrNull(String tenantId) {
        try {
            return tenantRepository.get(tenantId);
        } catch (GovernanceException e) {
            if (e.getErrorCode() == ErrorCode.TENANT_NOT_FOUND) return null;
            throw e;
        }
    }

    private void cleanupUncommittedIntent(String tenantId) {
        var intent = workRepository.findIntent(tenantId);
        if (intent.isEmpty()) return;
        Instant createdAt = intent.get().createdAt();
        long ageMillis = createdAt != null
            ? Duration.between(createdAt, Instant.now()).toMillis()
            : TenantCloseWorkRepository.PREPARE_GRACE_MILLIS;
        if (ageMillis < TenantCloseWorkRepository.PREPARE_GRACE_MILLIS) {
            workRepository.reschedule(tenantId,
                TenantCloseWorkRepository.PREPARE_GRACE_MILLIS - ageMillis);
            return;
        }
        String cleanupLease = workRepository.tryAcquireLease(tenantId);
        if (cleanupLease == null) {
            workRepository.reschedule(tenantId, 1_000L);
            return;
        }
        try {
            // Atomic with the close-commit marker and tenant status: it cannot
            // delete an intent concurrently consumed by a successful flip.
            if (!workRepository.discardIfUncommitted(tenantId)) {
                workRepository.reschedule(tenantId, 1_000L);
            }
        } finally {
            workRepository.releaseLease(tenantId, cleanupLease);
        }
    }
}
