package io.runcycles.admin.api.service;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.BudgetRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.event.Actor;
import io.runcycles.admin.model.event.ActorType;
import io.runcycles.admin.model.event.EventType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * tenant remains CLOSED and an operator re-issues the close — as of
 * v0.1.25.37 both {@code PATCH /v1/admin/tenants/{id}} with
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

    /**
     * Result of a cascade run: counts per owned-type so the caller can
     * surface the scale (bulk-action response, audit metadata, integration-
     * test assertions).
     */
    public record CascadeResult(int budgetsClosed, int webhooksDisabled,
                                int apiKeysRevoked, long reservationsReleased) {
        public static CascadeResult empty() { return new CascadeResult(0, 0, 0, 0L); }
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
        String requestId = attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String traceId   = attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE);
        String correlationId = correlationIdFor(tenantId, httpRequest);

        List<BudgetRepository.CascadeCloseBudgetOutcome> budgets =
            budgetRepository.cascadeClose(tenantId);
        long reservationsReleased = 0L;
        for (var b : budgets) {
            reservationsReleased += b.releasedReservedAmount();
            emitBudgetAudit(b, tenantId, requestId, traceId, httpRequest);
            emitBudgetEvent(b, tenantId, correlationId, requestId);
            if (b.releasedReservedAmount() > 0) {
                emitReservationReleaseEvent(b, tenantId, correlationId, requestId);
            }
        }

        List<WebhookRepository.CascadeDisableOutcome> webhooks =
            webhookRepository.cascadeDisable(tenantId);
        for (var w : webhooks) {
            emitWebhookAudit(w, tenantId, requestId, traceId, httpRequest);
            emitWebhookEvent(w, tenantId, correlationId, requestId);
        }

        List<ApiKeyRepository.CascadeRevokeOutcome> keys =
            apiKeyRepository.cascadeRevoke(tenantId, "tenant_closed");
        for (var k : keys) {
            emitApiKeyAudit(k, tenantId, requestId, traceId, httpRequest);
            emitApiKeyEvent(k, tenantId, correlationId, requestId);
        }

        LOG.info("Tenant-close cascade completed: tenant_id={} budgets_closed={} webhooks_disabled={} api_keys_revoked={} reserved_released={} correlation_id={} request_id={} trace_id={} source_ip={}",
            tenantId, budgets.size(), webhooks.size(), keys.size(), reservationsReleased,
            correlationId, requestId, traceId, httpRequest != null ? httpRequest.getRemoteAddr() : null);
        return new CascadeResult(budgets.size(), webhooks.size(), keys.size(), reservationsReleased);
    }

    private void emitBudgetAudit(BudgetRepository.CascadeCloseBudgetOutcome b,
                                  String tenantId, String requestId, String traceId,
                                  HttpServletRequest httpRequest) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cascade", "tenant_close");
        meta.put("prior_status", b.priorStatus().name());
        meta.put("new_status", "CLOSED");
        meta.put("scope", b.scope());
        meta.put("unit", b.unit() != null ? b.unit().name() : null);
        if (b.releasedReservedAmount() > 0) {
            meta.put("released_reserved_amount", b.releasedReservedAmount());
        }
        auditRepository.log(AuditLogEntry.builder()
            .tenantId(tenantId)
            .resourceType("budget")
            .resourceId(b.ledgerId())
            .operation("tenant_close_cascade")
            .status(200)
            .requestId(requestId)
            .traceId(traceId)
            .sourceIp(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .metadata(meta)
            .build());
    }

    private void emitBudgetEvent(BudgetRepository.CascadeCloseBudgetOutcome b,
                                  String tenantId, String correlationId, String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ledger_id", b.ledgerId());
        data.put("scope", b.scope());
        data.put("unit", b.unit() != null ? b.unit().name() : null);
        data.put("prior_status", b.priorStatus().name());
        data.put("new_status", "CLOSED");
        data.put("cascade_reason", "tenant_closed");
        eventService.emit(EventType.BUDGET_CLOSED_VIA_TENANT_CASCADE, tenantId, b.scope(),
            "cycles-admin",
            Actor.builder().type(ActorType.ADMIN).build(),
            data, correlationId, requestId);
    }

    private void emitReservationReleaseEvent(BudgetRepository.CascadeCloseBudgetOutcome b,
                                              String tenantId, String correlationId, String requestId) {
        // Runtime-plane reservations live outside the admin store; this event
        // is the admin-plane's aggregate signal that every reservation held
        // against the closed budget was effectively released by the CLOSE
        // (reserved drained to 0 → remaining bumped by the prior reserved
        // amount). One event per budget that had reserved > 0 at close time.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ledger_id", b.ledgerId());
        data.put("scope", b.scope());
        data.put("unit", b.unit() != null ? b.unit().name() : null);
        data.put("released_amount", b.releasedReservedAmount());
        data.put("cascade_reason", "tenant_closed");
        eventService.emit(EventType.RESERVATION_RELEASED_VIA_TENANT_CASCADE, tenantId, b.scope(),
            "cycles-admin",
            Actor.builder().type(ActorType.ADMIN).build(),
            data, correlationId, requestId);
    }

    private void emitWebhookAudit(WebhookRepository.CascadeDisableOutcome w,
                                   String tenantId, String requestId, String traceId,
                                   HttpServletRequest httpRequest) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cascade", "tenant_close");
        meta.put("prior_status", w.priorStatus().name());
        meta.put("new_status", "DISABLED");
        if (w.name() != null) meta.put("name", w.name());
        auditRepository.log(AuditLogEntry.builder()
            .tenantId(tenantId)
            .resourceType("webhook_subscription")
            .resourceId(w.subscriptionId())
            .operation("tenant_close_cascade")
            .status(200)
            .requestId(requestId)
            .traceId(traceId)
            .sourceIp(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .metadata(meta)
            .build());
    }

    private void emitWebhookEvent(WebhookRepository.CascadeDisableOutcome w,
                                   String tenantId, String correlationId, String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subscription_id", w.subscriptionId());
        data.put("prior_status", w.priorStatus().name());
        data.put("new_status", "DISABLED");
        if (w.name() != null) data.put("name", w.name());
        data.put("cascade_reason", "tenant_closed");
        eventService.emit(EventType.WEBHOOK_DISABLED_VIA_TENANT_CASCADE, tenantId, null,
            "cycles-admin",
            Actor.builder().type(ActorType.ADMIN).build(),
            data, correlationId, requestId);
    }

    private void emitApiKeyAudit(ApiKeyRepository.CascadeRevokeOutcome k,
                                  String tenantId, String requestId, String traceId,
                                  HttpServletRequest httpRequest) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cascade", "tenant_close");
        meta.put("prior_status", k.priorStatus().name());
        meta.put("new_status", "REVOKED");
        if (k.name() != null) meta.put("name", k.name());
        auditRepository.log(AuditLogEntry.builder()
            .tenantId(tenantId)
            .resourceType("api_key")
            .resourceId(k.keyId())
            .operation("tenant_close_cascade")
            .status(200)
            .requestId(requestId)
            .traceId(traceId)
            .sourceIp(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .metadata(meta)
            .build());
    }

    private void emitApiKeyEvent(ApiKeyRepository.CascadeRevokeOutcome k,
                                  String tenantId, String correlationId, String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key_id", k.keyId());
        data.put("prior_status", k.priorStatus().name());
        data.put("new_status", "REVOKED");
        if (k.name() != null) data.put("name", k.name());
        data.put("cascade_reason", "tenant_closed");
        eventService.emit(EventType.API_KEY_REVOKED_VIA_TENANT_CASCADE, tenantId, null,
            "cycles-admin",
            Actor.builder().type(ActorType.ADMIN).build(),
            data, correlationId, requestId);
    }

    private static String attr(HttpServletRequest request, String name) {
        if (request == null) return null;
        Object v = request.getAttribute(name);
        return v != null ? v.toString() : null;
    }
}
