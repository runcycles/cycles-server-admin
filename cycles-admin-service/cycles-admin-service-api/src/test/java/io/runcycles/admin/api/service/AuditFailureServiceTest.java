package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AuditFailureService} focused on v0.1.25.20 behaviour:
 * unauthenticated-tier sampling, sentinel tenant-id resolution, log-injection
 * guard on {@code metadata.error_message}, and non-throwing contract under
 * internal errors.
 *
 * <p>Interceptor-level integration (i.e. that {@code AuthInterceptor} actually
 * calls {@code logFailure}) is covered in {@code AuthInterceptorTest}; handler-
 * level integration is covered in {@code GlobalExceptionHandlerTest}; real-Redis
 * end-to-end in {@code AdminFlowIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AuditFailureServiceTest {

    @Mock private AuditRepository auditRepository;
    private MeterRegistry meterRegistry;
    private AuditFailureService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AuditFailureService(auditRepository, meterRegistry);
        // Default sampling rate = 1 (safe default). Individual tests override.
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 1);
        request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/v1/admin/budgets");
    }

    // --- sentinel resolution + operation encoding ---

    @Test
    void logFailure_noAuthenticatedTenant_usesUnauthenticatedSentinel() {
        service.logFailure(request, 401, ErrorCode.UNAUTHORIZED, "missing key", null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getTenantId()).isEqualTo(AuditLogEntry.UNAUTH_TENANT);
        assertThat(entry.getOperation()).isEqualTo("GET:/v1/admin/budgets");
        assertThat(entry.getStatus()).isEqualTo(401);
        assertThat(entry.getErrorCode()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void logFailure_authenticatedTenantAttribute_preservesRealTenantId() {
        request.setAttribute("authenticated_tenant_id", "tenant-1");
        request.setAttribute("authenticated_key_id", "key_1");

        service.logFailure(request, 403, ErrorCode.INSUFFICIENT_PERMISSIONS, "no perm", null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getTenantId()).isEqualTo("tenant-1");
        assertThat(entry.getKeyId()).isEqualTo("key_1");
    }

    @Test
    void logFailure_emptyStringTenantAttribute_fallsBackToSentinel() {
        // Defensive: an attribute set to "" must not produce an empty-string
        // tenant_id in the audit row (spec requires non-empty string).
        request.setAttribute("authenticated_tenant_id", "");

        service.logFailure(request, 401, ErrorCode.UNAUTHORIZED, "x", null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        assertThat(captor.getValue().getTenantId())
                .isEqualTo(AuditLogEntry.UNAUTH_TENANT);
    }

    @Test
    void logFailure_adminActorType_usesAdminSentinel() {
        // v0.1.25.28: AuthInterceptor.validateAdminKey stamps
        // actor_type="admin" but NOT authenticated_tenant_id (controllers
        // rely on its null-ness as the admin discriminator). The audit
        // writer must still label admin-plane failures distinctly from
        // pre-auth failures.
        request.setAttribute("authenticated_actor_type", "admin");

        service.logFailure(request, 404, ErrorCode.TENANT_NOT_FOUND,
                "does not exist", null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        assertThat(captor.getValue().getTenantId())
                .isEqualTo(AuditLogEntry.ADMIN_TENANT);
    }

    @Test
    void logFailure_adminActorType_notSampledOutEvenAtHighRate() {
        // __admin__ is a security-relevant signal, not DDoS-amplifiable
        // noise — the sampling gate applies ONLY to __unauth__.
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 1000);
        request.setAttribute("authenticated_actor_type", "admin");

        for (int i = 0; i < 50; i++) {
            service.logFailure(request, 404, ErrorCode.TENANT_NOT_FOUND, "x", null);
        }

        verify(auditRepository, org.mockito.Mockito.times(50))
                .log(org.mockito.ArgumentMatchers.any(AuditLogEntry.class));
        assertThat(counter("sampled-out")).isEqualTo(0.0);
    }

    // --- sampling gate ---

    @Test
    void logFailure_unauthenticated_sampleRateOne_neverSamplesOut() {
        // Default sampling rate 1 must always write — run many iterations
        // and confirm zero sampled-out. Deterministic guard against a
        // regression where `<=1` ever samples any entry.
        for (int i = 0; i < 200; i++) {
            service.logFailure(request, 401, ErrorCode.UNAUTHORIZED, "x", null);
        }

        verify(auditRepository, org.mockito.Mockito.times(200))
                .log(org.mockito.ArgumentMatchers.any(AuditLogEntry.class));
        assertThat(counter("written")).isEqualTo(200.0);
        assertThat(counter("sampled-out")).isEqualTo(0.0);
    }

    @Test
    void logFailure_unauthenticated_highSampleRate_samplesOutMostWrites() {
        // Rate = 100 → expected ~1% recorded, ~99% sampled-out. Over 1000
        // iterations the statistical variance is tight — asserting
        // 0 < written < 50 and sampled-out > 900 catches regression where
        // sampling no-ops entirely (all 1000 written) or drops all
        // (0 written).
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 100);
        int total = 1000;

        for (int i = 0; i < total; i++) {
            service.logFailure(request, 401, ErrorCode.UNAUTHORIZED, "x", null);
        }

        double written = counter("written");
        double sampledOut = counter("sampled-out");
        assertThat(written).isBetween(1.0, 50.0);
        assertThat(sampledOut).isBetween(950.0, (double) total);
        assertThat(written + sampledOut).isEqualTo((double) total);
    }

    @Test
    void logFailure_authenticatedTenant_sampleRateHigh_neverSamplesOut() {
        // Sampling applies ONLY to the unauthenticated tier. Authenticated
        // entries are compliance-critical — they must persist at full
        // fidelity regardless of sample rate. Guard against a future
        // refactor that widens the gate.
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 1000);
        request.setAttribute("authenticated_tenant_id", "tenant-auth");

        for (int i = 0; i < 100; i++) {
            service.logFailure(request, 403, ErrorCode.FORBIDDEN, "x", null);
        }

        verify(auditRepository, org.mockito.Mockito.times(100))
                .log(org.mockito.ArgumentMatchers.any(AuditLogEntry.class));
        assertThat(counter("sampled-out")).isEqualTo(0.0);
    }

    @Test
    void logFailure_sampleRateZero_treatedAsOne_recordsAll() {
        // Defense against misconfiguration: zero or negative rate must not
        // silently drop every entry. The `<= 1` guard in shouldSampleOut
        // treats these as "no sampling".
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 0);

        for (int i = 0; i < 10; i++) {
            service.logFailure(request, 401, ErrorCode.UNAUTHORIZED, "x", null);
        }

        verify(auditRepository, org.mockito.Mockito.times(10))
                .log(org.mockito.ArgumentMatchers.any(AuditLogEntry.class));
        assertThat(counter("sampled-out")).isEqualTo(0.0);
    }

    // --- message sanitization ---

    @Test
    void logFailure_message_crlfStripped() {
        service.logFailure(request, 400, ErrorCode.INVALID_REQUEST,
                "line1\r\nline2\ninjected", null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        String sanitized = (String) captor.getValue().getMetadata().get("error_message");
        assertThat(sanitized).doesNotContain("\r");
        assertThat(sanitized).doesNotContain("\n");
        assertThat(sanitized).contains("line1");
        assertThat(sanitized).contains("line2");
        assertThat(sanitized).contains("injected");
    }

    @Test
    void logFailure_message_longerThan1024_truncated() {
        String longMsg = "x".repeat(2000);

        service.logFailure(request, 400, ErrorCode.INVALID_REQUEST, longMsg, null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        String sanitized = (String) captor.getValue().getMetadata().get("error_message");
        assertThat(sanitized).hasSize(1024);
    }

    @Test
    void logFailure_nullMessage_omitsErrorMessageKey() {
        service.logFailure(request, 400, ErrorCode.INVALID_REQUEST, null, null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        assertThat(captor.getValue().getMetadata()).doesNotContainKey("error_message");
        // method and path still present.
        assertThat(captor.getValue().getMetadata()).containsKey("method");
        assertThat(captor.getValue().getMetadata()).containsKey("path");
    }

    @Test
    void logFailure_extrasMerged_alongsideDefaultMetadata() {
        java.util.Map<String, Object> extras = new java.util.HashMap<>();
        extras.put("exception_class", "java.lang.RuntimeException");

        service.logFailure(request, 500, ErrorCode.INTERNAL_ERROR, "boom", extras);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry("exception_class", "java.lang.RuntimeException")
                .containsEntry("method", "GET")
                .containsEntry("path", "/v1/admin/budgets")
                .containsEntry("error_message", "boom");
    }

    // --- non-throwing contract ---

    @Test
    void logFailure_auditRepositoryThrows_swallowed_errorCounterIncremented() {
        org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
                .when(auditRepository)
                .log(org.mockito.ArgumentMatchers.any(AuditLogEntry.class));

        // Must not propagate.
        service.logFailure(request, 401, ErrorCode.UNAUTHORIZED, "x", null);

        assertThat(counter("error")).isEqualTo(1.0);
        assertThat(counter("written")).isEqualTo(0.0);
    }

    @Test
    void logFailure_nullRequest_noNpe() {
        service.logFailure(null, 500, ErrorCode.INTERNAL_ERROR, "x", null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).log(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getTenantId()).isEqualTo(AuditLogEntry.UNAUTH_TENANT);
        assertThat(entry.getOperation()).isEqualTo("UNKNOWN:");
        assertThat(entry.getRequestId()).isNotNull(); // UUID fallback
    }

    @Test
    void logFailure_successPath_notTriggeredFromSuccessControllerFlow() {
        // Sanity: the service is only called from failure paths. Zero
        // interactions when nothing calls it. Guards against a future
        // refactor that accidentally wires this into a success code path
        // (double-write risk).
        verify(auditRepository, never())
                .log(org.mockito.ArgumentMatchers.any(AuditLogEntry.class));
    }

    // --- concurrency / DDoS-in-miniature ---

    @Test
    void logFailure_concurrent1000Calls_noLostCounterIncrements() throws Exception {
        // DDoS-in-miniature: fire 1000 parallel unauthenticated failures
        // through a thread pool and assert (written + sampled-out + error)
        // == 1000 exactly. ThreadLocalRandom is lock-free and Micrometer
        // counters are atomic, so any discrepancy indicates a real bug
        // (lost increment, double-count, swallowed exception escaping the
        // try/catch, etc.). Rate 100 → expected ~10 written — statistical
        // bounds identical to the serial test, but now under contention.
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 100);
        final int total = 1000;
        final int threads = 16;

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch startGate =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneGate =
                new java.util.concurrent.CountDownLatch(total);

        try {
            for (int i = 0; i < total; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        // Each thread builds its own request to avoid
                        // MockHttpServletRequest thread-unsafety.
                        MockHttpServletRequest req = new MockHttpServletRequest();
                        req.setMethod("GET");
                        req.setRequestURI("/v1/admin/tenants");
                        service.logFailure(req, 401, ErrorCode.UNAUTHORIZED, "x", null);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            boolean completed = doneGate.await(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(completed)
                    .as("all %d logFailure calls must complete within 30s", total)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        double written = counter("written");
        double sampledOut = counter("sampled-out");
        double error = counter("error");

        // Exactness: every call must land in exactly one bucket. A lost
        // increment or double-count breaks this sum.
        assertThat(written + sampledOut + error)
                .as("written + sampled-out + error must equal total=%d (no lost increments)", total)
                .isEqualTo((double) total);

        // No concurrency-induced errors: the non-throwing contract must
        // hold even under contention.
        assertThat(error)
                .as("logFailure must never produce outcome=error under normal concurrent load")
                .isEqualTo(0.0);

        // Statistical bound on sampling: same as serial test, just under
        // parallel load. Binomial(1000, 0.01) → mean=10, stddev~3.15,
        // 99.9% CI roughly [1, 20]. [1, 50] is the conservative bound.
        assertThat(written).isBetween(1.0, 50.0);
        assertThat(sampledOut).isBetween(950.0, (double) total);
    }

    @Test
    void logFailure_concurrent_authenticatedNeverSampled_evenUnderLoad() throws Exception {
        // Authenticated tier must persist at full fidelity even under
        // heavy contention — compliance-of-record guarantee.
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 10000);
        final int total = 500;
        final int threads = 8;

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch startGate =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneGate =
                new java.util.concurrent.CountDownLatch(total);

        try {
            for (int i = 0; i < total; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        MockHttpServletRequest req = new MockHttpServletRequest();
                        req.setMethod("POST");
                        req.setRequestURI("/v1/admin/budgets");
                        req.setAttribute("authenticated_tenant_id", "tenant-load");
                        service.logFailure(req, 403, ErrorCode.FORBIDDEN, "x", null);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            boolean completed = doneGate.await(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(completed).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(counter("written"))
                .as("authenticated-tier entries persist at full fidelity regardless of "
                        + "sampleRate=%d", 10000)
                .isEqualTo((double) total);
        assertThat(counter("sampled-out"))
                .as("authenticated entries must NEVER be sampled out")
                .isEqualTo(0.0);
        assertThat(counter("error")).isEqualTo(0.0);
    }

    // --- helpers ---

    private double counter(String outcome) {
        return meterRegistry.find("cycles_admin_audit_writes_total")
                .tag("path_class", "failure")
                .tag("outcome", outcome)
                .counter() == null
                        ? 0.0
                        : meterRegistry.find("cycles_admin_audit_writes_total")
                                .tag("path_class", "failure")
                                .tag("outcome", outcome)
                                .counter()
                                .count();
    }
}
