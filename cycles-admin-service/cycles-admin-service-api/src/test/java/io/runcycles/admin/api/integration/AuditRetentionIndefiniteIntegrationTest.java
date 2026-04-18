package io.runcycles.admin.api.integration;

import io.runcycles.admin.api.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification that {@code audit.retention.*.days=0} produces
 * Redis entries <b>without</b> a TTL (Lua's {@code if ttl > 0} branch
 * skipping the {@code EX} arg). {@code jedis.ttl()} returns {@code -1}
 * for keys with no expiry set.
 *
 * <p>Separate from {@link AuditRetentionIntegrationTest} because overriding
 * the retention properties requires a separate Spring context per
 * {@code @TestPropertySource}. Two test classes = two contexts = clean
 * isolation between "default retention" and "indefinite retention"
 * assertions.
 *
 * <p>Covers the operator-facing contract: setting a retention property
 * to {@code 0} means "never expire" (for legal hold, HIPAA, forever-
 * retain deployments). Verified end-to-end through the real Redis
 * container — not just via Lua-mock unit tests.
 *
 * <p>Also asserts {@code AuditRepository.sweepStaleIndexEntries()}
 * short-circuits when retention is indefinite — the sweep must NOT
 * remove pointers to never-expiring records.
 *
 * @since 0.1.25.20
 */
@TestPropertySource(properties = {
        "audit.retention.authenticated.days=0",
        "audit.retention.unauthenticated.days=0"
})
class AuditRetentionIndefiniteIntegrationTest extends BaseIntegrationTest {

    @Test
    void unauthenticatedFailure_retentionZero_auditKeyHasNoTtl() {
        // Fire a 401 → audit entry with sentinel tenant_id → resolveTtl
        // sees unauthenticatedRetentionDays=0 → Lua skips EX → TTL is -1.
        ResponseEntity<Map> unauth = restTemplate.exchange(
                baseUrl() + "/v1/admin/tenants", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String logId = pullMostRecentLogId("audit:logs:__unauth__");
        assertThat(logId).isNotNull();

        try (Jedis jedis = jedisPool.getResource()) {
            long ttl = jedis.ttl("audit:log:" + logId);
            // -1 = key exists, no expire set. -2 = key does not exist.
            assertThat(ttl)
                    .as("unauthenticated retention=0 must produce a key with NO expiry")
                    .isEqualTo(-1L);
        }
    }

    @Test
    void authenticatedFailure_retentionZero_auditKeyHasNoTtl() {
        adminPost("/v1/admin/tenants", Map.of(
                "tenant_id", "tenant-indef", "name", "Indefinite Retention Test"));
        ResponseEntity<Map> keyResp = adminPost("/v1/admin/api-keys", Map.of(
                "tenant_id", "tenant-indef", "name", "minimal-perms-key",
                "permissions", List.of("balances:read")));
        String keySecret = (String) keyResp.getBody().get("key_secret");

        // Tenant key with balances:read hitting POST /v1/admin/budgets →
        // 403 INSUFFICIENT_PERMISSIONS → audit entry with real tenant_id
        // → authenticatedRetentionDays=0 → Lua skips EX → TTL -1.
        ResponseEntity<Map> denied = restTemplate.exchange(
                baseUrl() + "/v1/admin/budgets", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "scope", "tenant:tenant-indef",
                        "unit", "USD_MICROCENTS",
                        "allocated", Map.of("unit", "USD_MICROCENTS", "amount", 100)),
                        tenantHeaders(keySecret)),
                Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String logId = pullMostRecentLogId("audit:logs:tenant-indef");
        assertThat(logId).isNotNull();

        try (Jedis jedis = jedisPool.getResource()) {
            long ttl = jedis.ttl("audit:log:" + logId);
            assertThat(ttl)
                    .as("authenticated retention=0 must produce a key with NO expiry")
                    .isEqualTo(-1L);
        }
    }

    @Test
    void sweepStaleIndexEntries_retentionZero_doesNotTouchLivePointers() {
        // Write one indefinite-retention entry, run the sweep,
        // assert the index pointer is untouched. Regression guard:
        // a future refactor that accidentally enables sweep under
        // retention=0 would delete pointers to forever-retain records.
        restTemplate.exchange(baseUrl() + "/v1/admin/tenants", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        String logId = pullMostRecentLogId("audit:logs:__unauth__");
        assertThat(logId).isNotNull();

        try (Jedis jedis = jedisPool.getResource()) {
            long sizeBefore = jedis.zcard("audit:logs:__unauth__");
            long sizeBeforeGlobal = jedis.zcard("audit:logs:_all");
            assertThat(sizeBefore).isGreaterThan(0);
            assertThat(sizeBeforeGlobal).isGreaterThan(0);

            // Run the sweep in-process (not waiting for cron). Under
            // retention=0 it should early-return and touch nothing.
            // We need access to the repo bean — pull it via Spring.
            io.runcycles.admin.data.repository.AuditRepository repo =
                    applicationContext().getBean(io.runcycles.admin.data.repository.AuditRepository.class);
            repo.sweepStaleIndexEntries();

            long sizeAfter = jedis.zcard("audit:logs:__unauth__");
            long sizeAfterGlobal = jedis.zcard("audit:logs:_all");
            assertThat(sizeAfter)
                    .as("sweep under retention=0 must not remove any tenant-index pointers")
                    .isEqualTo(sizeBefore);
            assertThat(sizeAfterGlobal)
                    .as("sweep under retention=0 must not remove any global-index pointers")
                    .isEqualTo(sizeBeforeGlobal);
        }
    }

    // --- helpers ---

    private String pullMostRecentLogId(String indexKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrevrange(indexKey, 0, 0);
            return ids.isEmpty() ? null : ids.get(0);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext springContext;

    private org.springframework.context.ApplicationContext applicationContext() {
        return springContext;
    }
}
