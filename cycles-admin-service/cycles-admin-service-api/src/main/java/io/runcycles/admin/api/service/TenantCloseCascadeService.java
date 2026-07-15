package io.runcycles.admin.api.service;

import static io.runcycles.admin.api.logging.LogSanitizer.safe;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.data.repository.TenantCloseWorkRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.support.TenantCloseOutboxItem;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.event.Actor;
import io.runcycles.admin.model.event.ActorType;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spec v0.1.25.29 CASCADE SEMANTICS (Rule 1 orchestration).
 *
 * <p>Drives the {@code * → CLOSED} cascade for one tenant. Called by
 * {@code TenantController} both from the PATCH path and the bulk-action
 * CLOSE path. Sequencing matches the spec:
 * <ol>
 *   <li>Close all owned {@code BudgetLedger} rows (ACTIVE|FROZEN → CLOSED,
 *       draining {@code reserved} back to {@code remaining} — this is the
 *       admin-plane analogue of releasing every open reservation).</li>
 *   <li>Disable all owned {@code WebhookSubscription} rows
 *       (ACTIVE|PAUSED → DISABLED).</li>
 *   <li>Revoke all owned ACTIVE API keys.</li>
 * </ol>
 *
 * <p>The caller MUST flip {@code tenant.status = CLOSED} BEFORE invoking
 * this service: flipping first activates Rule 2's mutation guard during
 * the cascade window so a concurrent user PATCH on an owned object 409s
 * rather than racing against the cascade. On partial cascade failure the
 * tenant remains CLOSED and the bounded reconciler retries automatically;
 * an operator can also re-issue the close. As of v0.1.25.37 both
 * {@code PATCH /v1/admin/tenants/{id}} with
 * {@code status=CLOSED} and bulk-action {@code CLOSE} re-invoke this
 * service on already-CLOSED tenants (spec v0.1.25.31 Rule 1(c)
 * bounded-convergence). Each cascade step is idempotent — the repository
 * cascade queries filter by non-terminal status so already-terminal
 * children are skipped and no duplicate audit/event rows are emitted.
 *
 * <p>Audit: every mutated owned object gets one {@code AuditLogEntry} with
 * {@code operation = "tenant_close_cascade"} and resource_type /
 * resource_id naming the child. All entries share {@code request_id} and
 * {@code trace_id} with the originating {@code tenant.closed} entry, so
 * operators can JOIN by either identifier — this is the admin-plane
 * realisation of the spec's "same correlation_id" requirement.
 *
 * <p>Events: one {@code *_via_tenant_cascade} event per mutated owned
 * object, carrying a correlation_id string
 * {@code "tenant_close_cascade:<tenantId>:<requestId>"} so downstream
 * subscribers can group the cascade as one logical operation.
 */
@Service
public class TenantCloseCascadeService {
    private static final Logger LOG = LoggerFactory.getLogger(TenantCloseCascadeService.class);

    @Autowired private BudgetRepository budgetRepository;
    @Autowired private WebhookRepository webhookRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private TenantCloseWorkRepository workRepository;

    /**
     * Result of a cascade run: counts per owned-type so the caller can
     * surface the scale (bulk-action response, audit metadata, integration-
     * test assertions).
     */
    public record CascadeResult(int budgetsClosed, int webhooksDisabled,
                                int apiKeysRevoked, long reservationsReleased,
                                List<String> failedResources) {
        public CascadeResult {
            failedResources = List.copyOf(failedResources);
        }
        public CascadeResult(int budgetsClosed, int webhooksDisabled,
                             int apiKeysRevoked, long reservationsReleased) {
            this(budgetsClosed, webhooksDisabled, apiKeysRevoked,
                reservationsReleased, List.of());
        }
        public static CascadeResult empty() { return new CascadeResult(0, 0, 0, 0L); }
        public boolean complete() { return failedResources.isEmpty(); }
    }

    /**
     * Build the shared correlation_id used to join every cascade-emitted
     * event (TENANT_CLOSED + each {@code *_via_tenant_cascade} child) for
     * one logical tenant-close operation. Exposed as a static helper so
     * the calling controller can stamp the same id on the TENANT_CLOSED
     * event that this service stamps on every child event.
     */
    public static String correlationIdFor(String tenantId, HttpServletRequest httpRequest) {
        String requestId = attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return "tenant_close_cascade:" + tenantId + ":"
            + (requestId != null ? requestId : "no-req");
    }

    /**
     * Orchestrate the cascade for one tenant. The caller MUST have already
     * flipped {@code tenant.status = CLOSED} — this service fans out the
     * children's terminal-state transitions and emits the audit + event
     * rows under a shared correlation_id.
     */
    public CascadeResult cascade(String tenantId, HttpServletRequest httpRequest) {
        prepare(tenantId, httpRequest);
        String leaseToken = workRepository.tryAcquireLease(tenantId);
        if (leaseToken == null) {
            workRepository.reschedule(tenantId, 1_000L);
            return new CascadeResult(0, 0, 0, 0L,
                List.of("cascade:lease:in_progress"));
        }
        try {
            TenantCloseWorkRepository.Intent intent = workRepository.findIntent(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant-close intent disappeared"));
            List<String> failures = new ArrayList<>();

            var budgetResult = budgetRepository.cascadeClose(tenantId);
            List<BudgetRepository.CascadeCloseBudgetOutcome> budgets = budgetResult.succeeded();
            budgetResult.failed().forEach(f -> failures.add(f.resourceId()));
            long reservationsReleased = budgets.stream()
                .mapToLong(BudgetRepository.CascadeCloseBudgetOutcome::releasedReservedAmount).sum();

            var webhookResult = webhookRepository.cascadeDisable(tenantId);
            List<WebhookRepository.CascadeDisableOutcome> webhooks = webhookResult.succeeded();
            webhookResult.failed().forEach(f -> failures.add("webhook:" + f.resourceId()));

            var keyResult = apiKeyRepository.cascadeRevoke(tenantId, "tenant_closed");
            List<ApiKeyRepository.CascadeRevokeOutcome> keys = keyResult.succeeded();
            keyResult.failed().forEach(f -> failures.add("api_key:" + f.resourceId()));

            drainOutbox(tenantId, intent, failures);
            if (!failures.isEmpty() || !workRepository.completeIfDrained(tenantId)) {
                workRepository.reschedule(tenantId, 30_000L);
            }

            LOG.info("Tenant-close cascade completed: tenant_id={} budgets_closed={} webhooks_disabled={} api_keys_revoked={} reserved_released={} failed_count={} correlation_id={} request_id={} trace_id={} source_ip={}",
                tenantId, budgets.size(), webhooks.size(), keys.size(), reservationsReleased,
                failures.size(), intent.correlationId(), intent.requestId(), intent.traceId(), intent.sourceIp());
            return new CascadeResult(budgets.size(), webhooks.size(), keys.size(),
                reservationsReleased, failures);
        } finally {
            workRepository.releaseLease(tenantId, leaseToken);
        }
    }

    private void drainOutbox(String tenantId, TenantCloseWorkRepository.Intent intent,
                             List<String> failures) {
        for (TenantCloseOutboxItem item : workRepository.listOutbox(tenantId)) {
            try {
                switch (item.resourceType()) {
                    case "budget" -> emitBudgetItem(item, tenantId, intent);
                    case "webhook_subscription" -> emitWebhookItem(item, tenantId, intent);
                    case "api_key" -> emitApiKeyItem(item, tenantId, intent);
                    default -> throw new IllegalArgumentException(
                        "Unknown tenant-close outbox resource type: " + item.resourceType());
                }
                workRepository.acknowledge(tenantId, item.itemId());
            } catch (Exception e) {
                failures.add("outbox:" + item.itemId());
                LOG.error("Tenant-close outbox emission failed: tenant_id={} item_id={} resource_type={}",
                    tenantId, item.itemId(), item.resourceType(), e);
            }
        }
    }

    private void emitBudgetItem(TenantCloseOutboxItem item, String tenantId,
                                TenantCloseWorkRepository.Intent intent) {
        Map<String, Object> meta = baseCascadeData(item, "CLOSED");
        if (item.releasedReservedAmount() > 0) {
            meta.put("released_reserved_amount", item.releasedReservedAmount());
        }
        writeRequiredAudit(item, tenantId, intent, meta);
        emitRequired(EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE, tenantId, item.scope(),
            meta, intent, stableId("evt_", tenantId, item.itemId(), "budget-closed"));
        if (item.releasedReservedAmount() > 0) {
            Map<String, Object> release = new LinkedHashMap<>();
            release.put("ledger_id", item.resourceId());
            release.put("scope", item.scope());
            release.put("unit", item.unit());
            release.put("released_amount", item.releasedReservedAmount());
            release.put("cascade_reason", "tenant_closed");
            emitRequired(EventType.RESERVATION_RELEASED_VIA_TENANT_CASCADE,
                tenantId, item.scope(), release, intent,
                stableId("evt_", tenantId, item.itemId(), "reservation-release"));
        }
    }

    private void emitWebhookItem(TenantCloseOutboxItem item, String tenantId,
                                 TenantCloseWorkRepository.Intent intent) {
        Map<String, Object> data = baseCascadeData(item, "DISABLED");
        writeRequiredAudit(item, tenantId, intent, data);
        emitRequired(EventType.WEBHOOK_DISABLED_VIA_TENANT_CASCADE, tenantId, null,
            data, intent, stableId("evt_", tenantId, item.itemId(), "webhook-disabled"));
    }

    private void emitApiKeyItem(TenantCloseOutboxItem item, String tenantId,
                                TenantCloseWorkRepository.Intent intent) {
        Map<String, Object> data = baseCascadeData(item, "REVOKED");
        writeRequiredAudit(item, tenantId, intent, data);
        emitRequired(EventType.API_KEY_REVOKED_VIA_TENANT_CASCADE, tenantId, null,
            data, intent, stableId("evt_", tenantId, item.itemId(), "api-key-revoked"));
    }

    private Map<String, Object> baseCascadeData(TenantCloseOutboxItem item,
                                                String newStatus) {
        Map<String, Object> data = new LinkedHashMap<>();
        if ("budget".equals(item.resourceType())) data.put("ledger_id", item.resourceId());
        if ("webhook_subscription".equals(item.resourceType())) data.put("subscription_id", item.resourceId());
        if ("api_key".equals(item.resourceType())) data.put("key_id", item.resourceId());
        if (item.scope() != null) data.put("scope", item.scope());
        if (item.unit() != null) data.put("unit", item.unit());
        if (item.name() != null) data.put("name", item.name());
        data.put("prior_status", item.priorStatus());
        data.put("new_status", newStatus);
        data.put("cascade_reason", "tenant_closed");
        return data;
    }

    private void writeRequiredAudit(TenantCloseOutboxItem item, String tenantId,
                                    TenantCloseWorkRepository.Intent intent,
                                    Map<String, Object> metadata) {
        Map<String, Object> auditMeta = new LinkedHashMap<>(metadata);
        auditMeta.put("cascade", "tenant_close");
        auditRepository.logRequired(AuditLogEntry.builder()
            .logId(stableId("log_", tenantId, item.itemId(), "audit"))
            .tenantId(tenantId).resourceType(item.resourceType())
            .resourceId(item.resourceId()).operation("tenant_close_cascade")
            .status(200).requestId(intent.requestId()).traceId(intent.traceId())
            .sourceIp(intent.sourceIp()).userAgent(intent.userAgent())
            .metadata(auditMeta).build());
    }

    private void emitRequired(EventType type, String tenantId, String scope,
                              Map<String, Object> data,
                              TenantCloseWorkRepository.Intent intent,
                              String eventId) {
        eventService.emitRequired(Event.builder()
            .eventId(eventId).eventType(type).category(type.getCategory())
            .timestamp(Instant.now()).tenantId(tenantId).scope(scope)
            .source("cycles-admin").actor(Actor.builder().type(ActorType.ADMIN).build())
            .data(data).correlationId(intent.correlationId())
            .requestId(intent.requestId()).traceId(intent.traceId()).build());
    }

    private static String stableId(String prefix, String tenantId, String itemId,
                                   String kind) {
        UUID uuid = UUID.nameUUIDFromBytes(
            (tenantId + "|" + itemId + "|" + kind).getBytes(StandardCharsets.UTF_8));
        return prefix + uuid.toString().replace("-", "").substring(0, 16);
    }

    private static String attr(HttpServletRequest request, String name) {
        if (request == null) return null;
        Object v = request.getAttribute(name);
        return safe(v);
    }

    /** Persist retry intent before the tenant status flip is attempted. */
    public void prepare(String tenantId, HttpServletRequest httpRequest) {
        workRepository.prepare(new TenantCloseWorkRepository.Intent(
            tenantId,
            attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE),
            attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE),
            correlationIdFor(tenantId, httpRequest),
            sourceIp(httpRequest), header(httpRequest, "User-Agent"), Instant.now()));
    }

    private static String sourceIp(HttpServletRequest request) {
        return request != null ? safe(request.getRemoteAddr()) : "reconciler";
    }

    private static String header(HttpServletRequest request, String name) {
        return request != null ? safe(request.getHeader(name)) : null;
    }
}
