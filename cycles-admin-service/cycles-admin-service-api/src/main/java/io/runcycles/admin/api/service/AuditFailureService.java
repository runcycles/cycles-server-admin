package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared failure-path audit writer. Used by {@code GlobalExceptionHandler}
 * (controller-level exceptions → 4xx/5xx) and {@code AuthInterceptor}
 * (pre-controller auth rejections → 401/403/400/500 before any controller
 * runs).
 *
 * <p><b>Design (v0.1.25.20).</b> Admin server writes audit entries on
 * success paths from within each controller method; failures never reached
 * those writes (controller throws → {@code GlobalExceptionHandler} runs →
 * no audit entry). This service closes that gap.
 *
 * <p><b>Single-write invariant by construction.</b> Success-path writes
 * execute only after the repository/service call returns, so a thrown
 * exception can never produce both a success entry and a failure entry
 * for the same request. Pre-controller auth failures never reach the
 * controller at all.
 *
 * <p><b>Non-fatal contract preserved.</b> {@code AuditRepository.log()}
 * already swallows all exceptions. This service wraps its own code in
 * {@code try/catch} as defense-in-depth — an audit-write failure must
 * never mask the real error response. The {@code outcome=error} counter
 * increment lets ops alert on silent coverage loss.
 *
 * <p><b>tenant_id sentinels.</b> Admin-plane spec marks {@code tenant_id}
 * as required on every audit entry. Two sentinels are written here:
 * {@link AuditLogEntry#UNAUTH_TENANT} for pre-auth failures (missing /
 * invalid / revoked key, missing admin key); requests that authenticated
 * via admin-key but failed downstream arrive with
 * {@link AuditLogEntry#ADMIN_TENANT} already stamped on the request by
 * {@code AuthInterceptor} — this service just reads what's there.
 * Both are queryable:
 * {@code GET /v1/admin/audit/logs?tenant_id=__unauth__} or {@code __admin__}.
 */
@Service
public class AuditFailureService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditFailureService.class);

    /**
     * Tenant-id sentinel for pre-auth failures.
     *
     * <p><b>Source of truth lives on {@link AuditLogEntry#UNAUTH_TENANT}</b>
     * so {@code AuditRepository} (data layer) can reference it for tiered-TTL
     * decisions without depending on this api/service-layer class. This
     * reference is kept as a convenience alias for callers already on this
     * service.
     */
    public static final String UNAUTH_TENANT = AuditLogEntry.UNAUTH_TENANT;

    /**
     * Cap on {@code metadata.error_message} length. Bounds audit row size,
     * prevents a pathological error message from bloating Redis memory, and
     * limits exposure surface if a message ever carries caller-controlled
     * content (defense in depth — exception messages SHOULD be server-side
     * strings, but sanitize regardless).
     */
    private static final int ERROR_MESSAGE_MAX_LEN = 1024;

    private final AuditRepository auditRepository;
    private final MeterRegistry meterRegistry;

    /**
     * Sampling rate for {@link #UNAUTH_TENANT} failure entries —
     * record 1 in N. Value {@code 1} (default) records every attempt —
     * safe default preserving full fidelity for small deployments.
     * Set to {@code 100} (or higher) in DDoS-exposed deployments to cut
     * Redis write volume by 100×; aggregate attempt rate stays visible
     * via {@code cycles_admin_audit_writes_total{outcome=sampled-out}}.
     *
     * <p>Authenticated failure entries (valid tenant key, 403 / 404 /
     * 409 / 500) are <b>never</b> sampled — they're compliance-relevant
     * security signals, not DDoS noise.
     *
     * <p>Values {@code <= 0} are treated as {@code 1} (no sampling) for
     * safety: a misconfigured zero must not silently drop all audit writes.
     *
     * @since 0.1.25.20
     */
    @Value("${audit.sample.unauthenticated:1}")
    private int unauthenticatedSampleRate;

    public AuditFailureService(AuditRepository auditRepository, MeterRegistry meterRegistry) {
        this.auditRepository = auditRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Write a failure-path audit entry. Never throws — an exception here
     * must not mask the caller's pending error response.
     *
     * @param request     the servlet request (required — used to derive
     *                    tenant_id, request_id, source_ip, user_agent,
     *                    operation="METHOD:/path")
     * @param status      HTTP status code of the pending error response
     *                    (e.g. 401, 403, 400, 404, 409, 500)
     * @param code        structured error code (non-null)
     * @param message     server-side error message (nullable; sanitized
     *                    + length-capped before write)
     * @param extras      optional per-handler metadata to merge into the
     *                    entry's {@code metadata} (nullable)
     */
    public void logFailure(HttpServletRequest request,
                           int status,
                           ErrorCode code,
                           String message,
                           Map<String, Object> extras) {
        try {
            String tenantId = resolveTenantId(request);

            // v0.1.25.20: sampling gate for the DDoS-amplifiable tier.
            // Only the __unauth__ (pre-auth failure) sentinel is sampled;
            // every authenticated failure — __admin__ platform-plane ops
            // AND valid-tenant-key 403/404/409/500 — persists at full
            // fidelity because it's a real security signal. Default
            // sampleRate=1 means "record every attempt" — operator must
            // opt in to reduce volume.
            if (UNAUTH_TENANT.equals(tenantId) && shouldSampleOut()) {
                recordWrite("failure", "sampled-out");
                return;
            }

            String method = request != null ? request.getMethod() : "UNKNOWN";
            String uri = request != null ? request.getRequestURI() : "";
            String operation = method + ":" + uri;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("method", method);
            metadata.put("path", uri);
            String sanitized = sanitizeMessage(message);
            if (sanitized != null) {
                metadata.put("error_message", sanitized);
            }
            if (extras != null) {
                metadata.putAll(extras);
            }

            AuditLogEntry entry = AuditLogEntry.builder()
                    .tenantId(tenantId)
                    .keyId(resolveAttr(request, "authenticated_key_id"))
                    .userAgent(request != null ? request.getHeader("User-Agent") : null)
                    .sourceIp(request != null ? request.getRemoteAddr() : null)
                    .operation(operation)
                    .requestId(resolveRequestId(request))
                    .status(status)
                    .errorCode(code != null ? code.name() : null)
                    .metadata(metadata)
                    .build();

            auditRepository.log(entry);
            recordWrite("failure", "written");
        } catch (Exception e) {
            // Defense in depth: AuditRepository.log() already swallows. If
            // anything here still throws (e.g. MeterRegistry wedged), don't
            // propagate — the real error response MUST go out.
            recordWrite("failure", "error");
            LOG.warn("Failed to write failure audit entry (non-fatal): {}", e.getMessage());
        }
    }

    private String resolveTenantId(HttpServletRequest request) {
        Object t = resolveAttrRaw(request, "authenticated_tenant_id");
        if (t != null) {
            String s = t.toString();
            if (!s.isEmpty()) {
                return s;
            }
        }
        // v0.1.25.28: admin-key auth stamps actor_type="admin" but NOT
        // authenticated_tenant_id (controllers use that attr's null-ness
        // as the "is this admin?" discriminator). Pick the __admin__
        // sentinel here so admin-plane failures don't fall into __unauth__.
        Object actor = resolveAttrRaw(request, "authenticated_actor_type");
        if (actor != null && "admin".equals(actor.toString())) {
            return AuditLogEntry.ADMIN_TENANT;
        }
        return UNAUTH_TENANT;
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = resolveAttrRaw(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return attr != null ? attr.toString() : UUID.randomUUID().toString();
    }

    private String resolveAttr(HttpServletRequest request, String name) {
        Object v = resolveAttrRaw(request, name);
        return v != null ? v.toString() : null;
    }

    private Object resolveAttrRaw(HttpServletRequest request, String name) {
        return request != null ? request.getAttribute(name) : null;
    }

    /**
     * Decide whether to drop this unauthenticated-tier write under the
     * current sampling rate. Called ONLY after confirming the entry is
     * the unauthenticated tier.
     *
     * <p>Rate {@code <= 1} never samples out — the {@code > 1} gate
     * preserves full fidelity at default config and defends against a
     * misconfigured {@code 0} or negative value that would otherwise
     * silently drop every audit entry.
     */
    private boolean shouldSampleOut() {
        int rate = unauthenticatedSampleRate;
        if (rate <= 1) {
            return false;
        }
        // Keep 1 slot out of N → sample-out probability (N-1)/N.
        // ThreadLocalRandom is lock-free and preferred over Math.random()
        // under contention.
        return ThreadLocalRandom.current().nextInt(rate) != 0;
    }

    /**
     * Strip CR/LF (log-injection guard) and cap length. Null in → null out.
     */
    private String sanitizeMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            return null;
        }
        String stripped = msg.replace('\r', ' ').replace('\n', ' ');
        return stripped.length() > ERROR_MESSAGE_MAX_LEN
                ? stripped.substring(0, ERROR_MESSAGE_MAX_LEN)
                : stripped;
    }

    private void recordWrite(String pathClass, String outcome) {
        try {
            Counter.builder("cycles_admin_audit_writes_total")
                    .description("Count of audit-log write attempts, labelled by path class "
                            + "(success|failure) and outcome "
                            + "(written|error|sampled-out).")
                    .tag("path_class", pathClass)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        } catch (Exception ignored) {
            // Metrics must never break the main path.
        }
    }
}
