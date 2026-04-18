package io.runcycles.admin.api.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.ErrorCode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * jqwik property-based verification of the 5 NORMATIVE invariants that
 * guard the v0.1.25.20 audit-write path. Mirrors cycles-server's
 * {@code BudgetExhaustionConcurrentPropertyTest} pattern — jqwik generates
 * random inputs, shrinks failing cases to the minimal reproducer, and the
 * nightly workflow runs at 5× PR depth via {@code -Djqwik.defaultTries=100}.
 *
 * <p><b>Invariants (as written in issue #102):</b>
 * <ul>
 *   <li><b>I1</b> — for every request, exactly one of
 *       {@code {success-write, failure-write, sampled-out-counter}} fires.
 *       Never zero. Never two. Tested by observing counter deltas.</li>
 *   <li><b>I2</b> — authenticated entries are never sampled out regardless
 *       of the configured rate, even at {@code Integer.MAX_VALUE}.
 *       Compliance-of-record guarantee.</li>
 *   <li><b>I3</b> — TTL tier always matches the entry's tenant_id:
 *       sentinel ⇒ unauthenticatedRetentionDays × 86400;
 *       real tenant ⇒ authenticatedRetentionDays × 86400;
 *       0 days ⇒ no expiry (TTL=0).</li>
 *   <li><b>I4</b> — {@code sanitizeMessage} output contains no {@code \r}
 *       or {@code \n} and has length ≤ 1024 regardless of input.</li>
 *   <li><b>I5</b> — {@code logFailure} never propagates an exception.
 *       Non-throwing contract holds under arbitrary failure modes.</li>
 * </ul>
 *
 * <p><b>Deliberately NOT a Spring-context test.</b> jqwik-spring integration
 * works but adds multi-minute context boot per try (multiplied by 100 tries
 * nightly = 200+ min CI time). These invariants hold at the unit level —
 * {@code AuditFailureService} + {@code AuditRepository} are plain Spring
 * beans and can be wired with mocks / real instances without
 * {@code @SpringBootTest}. Speed matters more than full-stack fidelity for
 * property-based coverage; the soak test and integration test cover the
 * real-Spring paths.
 *
 * <p><b>Excluded from PR CI</b> via {@code @Tag("property-tests")} —
 * default surefire has {@code <excludedGroups>soak,property-tests</excludedGroups>}.
 * Run locally with:
 * <pre>
 *   mvn test -Pproperty-tests --file cycles-admin-service/pom.xml
 * </pre>
 *
 * @since 0.1.25.21
 */
@Tag("property-tests")
class AuditCoverageInvariantsPropertyTest {

    private AuditRepository repo;
    private AuditFailureService service;
    private MeterRegistry meters;

    @BeforeTry
    void setUp() {
        // Per-try reset so counter deltas are measurable from zero and
        // mock interactions don't leak across properties. @BeforeTry fires
        // before each jqwik-generated input combination (unlike
        // @BeforeEach/@BeforeProperty which fire once per property method).
        repo = mock(AuditRepository.class);
        meters = new SimpleMeterRegistry();
        service = new AuditFailureService(repo, meters);
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", 1);
    }

    // ================================================================
    // I1: Exactly-one-outcome per request.
    // ================================================================
    //
    // No matter what inputs come in, logFailure produces exactly one of:
    //   - outcome=written + repo.log() called
    //   - outcome=error + repo.log() not called (exception inside service)
    //   - outcome=sampled-out + repo.log() not called (sampling gate)
    // Never zero buckets (every request accounted for). Never two (double-
    // count). This is the "no lost increments" contract tested at the unit
    // level — the concurrent tests and the soak test verify it holds under
    // contention and sustained load.

    @Property(shrinking = ShrinkingMode.FULL)
    void i1_exactlyOneOutcomeBucketFires(
            @ForAll("statusCodes") int status,
            @ForAll("errorCodes") ErrorCode code,
            @ForAll("tenantIds") String tenantId,
            @ForAll @IntRange(min = 1, max = 1000) int sampleRate) {
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", sampleRate);
        MockHttpServletRequest req = buildRequest(tenantId);

        service.logFailure(req, status, code, "msg", null);

        double written = outcomeCount("written");
        double error = outcomeCount("error");
        double sampledOut = outcomeCount("sampled-out");
        double total = written + error + sampledOut;

        assertThat(total)
                .as("I1: exactly one outcome must fire per call (tenantId=%s, rate=%d, "
                        + "written=%f, error=%f, sampled-out=%f)",
                        tenantId, sampleRate, written, error, sampledOut)
                .isEqualTo(1.0);
    }

    // ================================================================
    // I2: Authenticated entries are never sampled out.
    // ================================================================
    //
    // Regardless of the configured rate — even at Integer.MAX_VALUE — if
    // the entry's tenant_id is NOT the sentinel, the sampling gate must
    // never fire. Compliance-of-record guarantee: real tenant activity
    // must persist.

    @Property(shrinking = ShrinkingMode.FULL)
    void i2_authenticatedNeverSampledOut_evenAtExtremeRates(
            @ForAll("realTenantIds") String realTenantId,
            @ForAll("extremeRates") int rate) {
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", rate);
        MockHttpServletRequest req = buildRequest(realTenantId);

        service.logFailure(req, 403, ErrorCode.FORBIDDEN, "msg", null);

        assertThat(outcomeCount("sampled-out"))
                .as("I2: authenticated tenant %s with rate %d must never be sampled out",
                        realTenantId, rate)
                .isEqualTo(0.0);
        assertThat(outcomeCount("written"))
                .as("I2: authenticated tenant %s with rate %d must always persist",
                        realTenantId, rate)
                .isEqualTo(1.0);
    }

    // ================================================================
    // I3: TTL tier matches tenant_id.
    // ================================================================
    //
    // Tested directly on AuditRepository (not through the service) because
    // the TTL decision lives in resolveTtlSeconds(). This is the "tier
    // cannot mismatch" contract — a regression here would either silently
    // give unauth entries 400-day retention (DDoS memory amplification) or
    // give authenticated entries 30-day retention (compliance gap).

    @Property(shrinking = ShrinkingMode.FULL)
    void i3_ttlTierMatchesTenantId(
            @ForAll("tenantIds") String tenantId,
            @ForAll @IntRange(min = 0, max = 1000) int authDays,
            @ForAll @IntRange(min = 0, max = 1000) int unauthDays) {
        AuditRepository realRepo = new AuditRepository();
        ReflectionTestUtils.setField(realRepo, "authenticatedRetentionDays", authDays);
        ReflectionTestUtils.setField(realRepo, "unauthenticatedRetentionDays", unauthDays);

        long actual = realRepo.resolveTtlSeconds(
                AuditLogEntry.builder().tenantId(tenantId).build());

        boolean isUnauth = AuditLogEntry.UNAUTH_TENANT.equals(tenantId);
        int expectedDays = isUnauth ? unauthDays : authDays;
        long expected = expectedDays > 0 ? (long) expectedDays * 86400L : 0L;

        assertThat(actual)
                .as("I3: tenantId=%s authDays=%d unauthDays=%d — "
                        + "tier '%s' selected, expected TTL %d seconds, got %d",
                        tenantId, authDays, unauthDays,
                        isUnauth ? "unauth" : "auth", expected, actual)
                .isEqualTo(expected);
    }

    // ================================================================
    // I4: sanitizeMessage is bulletproof.
    // ================================================================
    //
    // Every {@code metadata.error_message} value must be free of CR/LF
    // (log-injection guard) and no longer than 1024 chars (audit row
    // bounding). The sanitize method is private so we verify it via the
    // metadata map captured from repo.log().

    @Property(shrinking = ShrinkingMode.FULL)
    void i4_sanitizedMessageHasNoCrlfAndIsBounded(
            @ForAll @StringLength(min = 0, max = 4096) String rawMessage) {
        MockHttpServletRequest req = buildRequest("tenant-1");

        service.logFailure(req, 500, ErrorCode.INTERNAL_ERROR, rawMessage, null);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repo).log(captor.capture());

        Object persisted = captor.getValue().getMetadata().get("error_message");
        if (rawMessage == null || rawMessage.isEmpty()) {
            // null / empty input ⇒ no error_message key in metadata.
            assertThat(persisted)
                    .as("I4: null/empty input must omit error_message key")
                    .isNull();
            return;
        }
        assertThat(persisted).isInstanceOf(String.class);
        String out = (String) persisted;
        assertThat(out)
                .as("I4: sanitized message must not contain \\r or \\n "
                        + "(log-injection guard). Raw length=%d",
                        rawMessage.length())
                .doesNotContain("\r")
                .doesNotContain("\n");
        assertThat(out.length())
                .as("I4: sanitized message must be ≤ 1024 chars. Raw length=%d, "
                        + "sanitized length=%d",
                        rawMessage.length(), out.length())
                .isLessThanOrEqualTo(1024);
    }

    // ================================================================
    // I5: logFailure never propagates.
    // ================================================================
    //
    // The real error response MUST reach the client even if the audit-
    // write path itself is broken. Drop AuditRepository into a faulty
    // mock, inject arbitrary failure modes, confirm logFailure never
    // throws. Verifies the non-throwing contract under adversarial
    // repo behaviour.

    @Property(shrinking = ShrinkingMode.FULL)
    void i5_logFailureNeverThrows_evenUnderAdversarialRepo(
            @ForAll("statusCodes") int status,
            @ForAll("errorCodes") ErrorCode code,
            @ForAll("tenantIds") String tenantId,
            @ForAll("throwables") Throwable repoException) {
        doThrow(wrapAsRuntime(repoException)).when(repo).log(any(AuditLogEntry.class));
        MockHttpServletRequest req = buildRequest(tenantId);

        // Must not propagate regardless of repo behavior or input shape.
        service.logFailure(req, status, code, "x", null);

        // Side-effects: outcome=error incremented. Total outcomes still == 1
        // (I1 invariant preserved even under repo failure).
        assertThat(outcomeCount("error"))
                .as("I5: repo failure must increment outcome=error exactly once")
                .isEqualTo(1.0);
        assertThat(outcomeCount("error") + outcomeCount("written")
                        + outcomeCount("sampled-out"))
                .as("I5: one outcome per call held under repo failure")
                .isEqualTo(1.0);
    }

    @Property(shrinking = ShrinkingMode.FULL)
    void i5b_logFailureNeverThrows_nullRequest_anyInput(
            @ForAll("statusCodes") int status,
            @ForAll("errorCodes") ErrorCode code,
            @ForAll @StringLength(min = 0, max = 2048) String msg) {
        // Request-missing case — null everywhere, arbitrary message shapes.
        // Must still not throw.
        service.logFailure(null, status, code, msg, null);

        // Null request routes to sentinel tenant → treated as unauth.
        // Should land as written (rate=1 default means no sampling).
        assertThat(outcomeCount("written") + outcomeCount("error"))
                .as("I5b: null request with any input must still produce exactly one outcome")
                .isEqualTo(1.0);
    }

    // ================================================================
    // Sampling-rate extreme: rate 0 / negative must not drop everything.
    // ================================================================
    //
    // Extra invariant not in issue #102's list but critical to prevent
    // silent audit loss from misconfiguration (default safety rail).
    // Property-style check spans the whole negative + zero space.

    @Property(shrinking = ShrinkingMode.FULL)
    void misconfigSafety_rateZeroOrNegative_treatedAsOne(
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = 1) int badRate) {
        ReflectionTestUtils.setField(service, "unauthenticatedSampleRate", badRate);
        MockHttpServletRequest req = buildRequest(
                AuditLogEntry.UNAUTH_TENANT);

        service.logFailure(req, 401, ErrorCode.UNAUTHORIZED, "x", null);

        assertThat(outcomeCount("sampled-out"))
                .as("misconfig safety: rate=%d must NOT sample out (would silently drop every audit)",
                        badRate)
                .isEqualTo(0.0);
        assertThat(outcomeCount("written"))
                .as("misconfig safety: rate=%d must record", badRate)
                .isEqualTo(1.0);
    }

    // ================================================================
    // Generators (@Provide)
    // ================================================================

    @Provide
    Arbitrary<Integer> statusCodes() {
        // Practical HTTP statuses the failure path emits. Excludes 2xx
        // (those never reach logFailure) and <400 (never emitted by admin
        // error paths). 300-399 excluded because redirect semantics don't
        // flow through our handlers.
        return Arbitraries.of(400, 401, 403, 404, 409, 422, 500, 502, 503);
    }

    @Provide
    Arbitrary<ErrorCode> errorCodes() {
        return Arbitraries.of(ErrorCode.values());
    }

    @Provide
    Arbitrary<String> tenantIds() {
        // Mix of the sentinel and typical tenant-id shapes per admin's
        // ^[a-z0-9-]+$ validation.
        return Arbitraries.oneOf(
                Arbitraries.just(AuditLogEntry.UNAUTH_TENANT),
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .withCharRange('0', '9')
                        .withChars('-')
                        .ofMinLength(3)
                        .ofMaxLength(64)
                        .filter(s -> !s.startsWith("-") && !s.endsWith("-")));
    }

    @Provide
    Arbitrary<String> realTenantIds() {
        // Same as tenantIds but with the sentinel explicitly excluded.
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('-')
                .ofMinLength(3)
                .ofMaxLength(64)
                .filter(s -> !s.startsWith("-") && !s.endsWith("-"));
    }

    @Provide
    Arbitrary<Integer> extremeRates() {
        // Ensures i2 holds across the whole positive range plus the
        // boundary at Integer.MAX_VALUE.
        return Arbitraries.integers().between(1, Integer.MAX_VALUE);
    }

    @Provide
    Arbitrary<Throwable> throwables() {
        // Representative failure modes AuditRepository might produce.
        return Arbitraries.of(
                new RuntimeException("Redis down"),
                new IllegalStateException("jedis pool exhausted"),
                new OutOfMemoryError("heap pressure"),
                new NullPointerException("unexpected null"),
                new Error("JVM-level failure"));
    }

    // ================================================================
    // Helpers
    // ================================================================

    private MockHttpServletRequest buildRequest(String tenantId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("GET");
        req.setRequestURI("/v1/admin/tenants");
        if (!AuditLogEntry.UNAUTH_TENANT.equals(tenantId)) {
            req.setAttribute("authenticated_tenant_id", tenantId);
        }
        return req;
    }

    private double outcomeCount(String outcome) {
        var c = meters.find("cycles_admin_audit_writes_total")
                .tag("path_class", "failure")
                .tag("outcome", outcome)
                .counter();
        return c == null ? 0.0 : c.count();
    }

    private RuntimeException wrapAsRuntime(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error err) {
            // Errors propagate by contract — our non-throwing wrapper must
            // catch Throwable or we lose I5. Wrapping as RuntimeException
            // here lets us `doThrow()` through Mockito uniformly.
            return new RuntimeException("simulated error escalation", err);
        }
        return new RuntimeException(t);
    }
}
