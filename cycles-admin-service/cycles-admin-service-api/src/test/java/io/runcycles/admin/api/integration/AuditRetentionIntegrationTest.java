package io.runcycles.admin.api.integration;

import io.runcycles.admin.api.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification that tiered TTL (v0.1.25.20) is actually applied
 * on the {@code audit:log:&#123;logId&#125;} keys in Redis, not just that
 * the Lua script <i>receives</i> the right TTL argument.
 *
 * <p>Unit-level {@code AuditRepositoryTest} already asserts that
 * {@code resolveTtlSeconds()} picks the right tier and that the correct
 * value lands in {@code ARGV[4]} of the eval call. This test closes the
 * trust-chain by driving real requests through the admin server, then
 * querying Redis directly via {@code JedisPool} to confirm the TTL was
 * actually applied. Fast (no real-time waits) — the test verifies the
 * expected TTL value, not the expiry event itself.
 *
 * <p>Covers both tiers at default retention:
 * <ul>
 *   <li>Authenticated tier → default 400 days = 34,560,000 s</li>
 *   <li>Unauthenticated tier → default 30 days = 2,592,000 s</li>
 * </ul>
 *
 * <p>Indefinite-retention case ({@code days=0} → no EX on SET, TTL = -1)
 * lives in {@link AuditRetentionIndefiniteIntegrationTest} because the
 * @{@code TestPropertySource} override requires a separate Spring context.
 *
 * @since 0.1.25.20
 */
class AuditRetentionIntegrationTest extends BaseIntegrationTest {

    private static final long DAYS = 86_400L;
    // Allow a few seconds of slack between Lua EX and jedis.ttl() — the
    // Redis clock ticks between the SET and the TTL query, and depending on
    // CI load there can be 1-3s latency. Keep slack tight (not minutes)
    // to catch genuine drift.
    private static final long TTL_TOLERANCE_SECONDS = 30L;

    @Test
    void authenticatedFailure_auditKeyHasAuthenticatedTierTtl() {
        // Trigger a 404 under valid admin auth — lands in
        // GlobalExceptionHandler.handleGovernanceException → audit written
        // with the caller's admin context (no tenant_id attr stamped,
        // so the entry uses the unauthenticated sentinel). Wait — that
        // means this case doesn't actually exercise the authenticated
        // tier TTL path.
        //
        // To exercise the authenticated tier we need a request that stamps
        // authenticated_tenant_id on the request. Admin-key auth does NOT
        // stamp it (per AuthInterceptor.validateAdminKey contract). Only
        // ApiKeyAuth stamps tenant_id. So we need a tenant API key that
        // fails authorization on a path — e.g. a valid tenant key calling
        // a path that requires a permission the key doesn't have.

        // --- Setup: create a tenant + API key with limited permissions ---
        adminPost("/v1/admin/tenants", Map.of(
                "tenant_id", "tenant-audit-ttl",
                "name", "Audit TTL Test Tenant"));

        ResponseEntity<Map> keyResp = adminPost("/v1/admin/api-keys", Map.of(
                "tenant_id", "tenant-audit-ttl",
                "name", "minimal-perms-key",
                "permissions", List.of("balances:read")));
        assertThat(keyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String keySecret = (String) keyResp.getBody().get("key_secret");

        // --- Trigger: tenant key calls an endpoint requiring a permission
        // it lacks. Hits AuthInterceptor.validateApiKey successfully
        // (stamps authenticated_tenant_id), then fails the permission
        // check → 403 INSUFFICIENT_PERMISSIONS → writeError →
        // AuditFailureService.logFailure with real tenant_id.
        HttpHeaders tenantHeaders = tenantHeaders(keySecret);
        ResponseEntity<Map> denied = restTemplate.exchange(
                baseUrl() + "/v1/admin/budgets", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "scope", "tenant:tenant-audit-ttl",
                        "unit", "USD_MICROCENTS",
                        "allocated", Map.of("unit", "USD_MICROCENTS", "amount", 1_000_000)),
                        tenantHeaders),
                Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // --- Verify: the audit entry was written with the authenticated
        // tier's TTL. Pull the most recent entry from the tenant index
        // and ask Redis for its TTL directly.
        String logId = pullMostRecentLogId("audit:logs:tenant-audit-ttl");
        assertThat(logId)
                .as("audit entry for tenant-audit-ttl's 403 must be indexed")
                .isNotNull();

        long ttlSeconds = readTtlSeconds("audit:log:" + logId);
        long expected = 400L * DAYS;  // default audit.retention.authenticated.days=400
        assertThat(ttlSeconds)
                .as("authenticated-tier audit entry TTL should be within ±%ds of %ds "
                        + "(default 400 days)", TTL_TOLERANCE_SECONDS, expected)
                .isBetween(expected - TTL_TOLERANCE_SECONDS, expected + TTL_TOLERANCE_SECONDS);
    }

    @Test
    void unauthenticatedFailure_auditKeyHasUnauthenticatedTierTtl() {
        // Trigger a 401 with no credentials at all → AuthInterceptor.
        // writeError → AuditFailureService.logFailure with sentinel
        // tenant_id → AuditRepository.resolveTtlSeconds picks the
        // unauthenticated tier.
        HttpHeaders noAuth = new HttpHeaders();
        ResponseEntity<Map> unauth = restTemplate.exchange(
                baseUrl() + "/v1/admin/tenants", HttpMethod.GET,
                new HttpEntity<>(noAuth), Map.class);
        assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String logId = pullMostRecentLogId("audit:logs:__unauth__");
        assertThat(logId)
                .as("audit entry for unauth 401 must be indexed under the sentinel")
                .isNotNull();

        long ttlSeconds = readTtlSeconds("audit:log:" + logId);
        long expected = 30L * DAYS;  // default audit.retention.unauthenticated.days=30
        assertThat(ttlSeconds)
                .as("unauthenticated-tier audit entry TTL should be within ±%ds of %ds "
                        + "(default 30 days)", TTL_TOLERANCE_SECONDS, expected)
                .isBetween(expected - TTL_TOLERANCE_SECONDS, expected + TTL_TOLERANCE_SECONDS);
    }

    @Test
    void unauthenticatedAndAuthenticatedTiers_distinctTtls() {
        // Belt-and-suspenders: same Redis, two entries from two tiers,
        // assert the TTLs are materially different — guards against a
        // regression where both tiers end up sharing one TTL knob.
        // Fire both in-order, pull each, diff > 1 year → tiers split.

        // Fire unauth failure.
        restTemplate.exchange(baseUrl() + "/v1/admin/tenants", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        String unauthLogId = pullMostRecentLogId("audit:logs:__unauth__");
        long unauthTtl = readTtlSeconds("audit:log:" + unauthLogId);

        // Fire authenticated failure (403 via insufficient permissions).
        adminPost("/v1/admin/tenants", Map.of("tenant_id", "t-tier-diff", "name", "t"));
        ResponseEntity<Map> keyResp = adminPost("/v1/admin/api-keys", Map.of(
                "tenant_id", "t-tier-diff", "name", "k",
                "permissions", List.of("balances:read")));
        String keySecret = (String) keyResp.getBody().get("key_secret");
        restTemplate.exchange(
                baseUrl() + "/v1/admin/budgets", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "scope", "tenant:t-tier-diff",
                        "unit", "USD_MICROCENTS",
                        "allocated", Map.of("unit", "USD_MICROCENTS", "amount", 100)),
                        tenantHeaders(keySecret)),
                Map.class);
        String authLogId = pullMostRecentLogId("audit:logs:t-tier-diff");
        long authTtl = readTtlSeconds("audit:log:" + authLogId);

        // Default 400 days vs 30 days → 370 days difference minimum. Use
        // a large floor (300 days) so any slack in TTL rounding or clock
        // drift doesn't flake; the absolute difference will always be
        // measured in hundreds of days under default config.
        assertThat(authTtl - unauthTtl)
                .as("authenticated TTL (400d default) minus unauthenticated TTL "
                        + "(30d default) must clearly separate — regression floor 300 days")
                .isGreaterThan(300L * DAYS);
    }

    // --- helpers ---

    /**
     * Pull the newest log id from a sorted-set index and return it. Returns
     * null if the index is empty. Uses {@code ZREVRANGE ... 0 0} to grab
     * the top-scoring entry without paging.
     */
    private String pullMostRecentLogId(String indexKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrevrange(indexKey, 0, 0);
            return ids.isEmpty() ? null : ids.get(0);
        }
    }

    private long readTtlSeconds(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            // -1 = no expire, -2 = key does not exist, >= 0 = remaining seconds
            return jedis.ttl(key);
        }
    }
}
