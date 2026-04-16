package io.runcycles.admin.data.repository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import java.time.Instant;
import java.util.*;
@Repository
public class AuditRepository {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    /**
     * Retention for entries whose {@code tenant_id != "<unauthenticated>"} —
     * every success entry and every authenticated failure. Default 400 days
     * = SOC2 Type II 12-month lookback + 1-month buffer for post-period
     * auditor lag. Set to {@code 0} for indefinite retention (legal hold,
     * HIPAA-adjacent deployments, or environments that offload to an
     * archive store).
     *
     * @since 0.1.25.20
     */
    @Value("${audit.retention.authenticated.days:400}")
    private int authenticatedRetentionDays;

    /**
     * Retention for entries whose {@code tenant_id == "<unauthenticated>"}.
     * Applies to pre-auth failures (missing / invalid credentials, path
     * traversal rejections, admin-key-only requests failing before the
     * controller runs). Default 30 days — enough for brute-force /
     * credential-stuffing post-mortem; aggregate attempt volume stays
     * visible via the {@code cycles_admin_audit_writes_total} Prometheus
     * counter regardless of this setting. Set to {@code 0} for indefinite.
     *
     * @since 0.1.25.20
     */
    @Value("${audit.retention.unauthenticated.days:30}")
    private int unauthenticatedRetentionDays;

    /**
     * Lua script for atomic audit-log creation. Now supports optional TTL
     * via ARGV[4] (seconds; 0 or negative = no expiry). The Lua runs:
     * <pre>
     *   SET audit:log:&#123;logId&#125; &lt;json&gt; [EX ttlSeconds]
     *   ZADD audit:logs:&#123;tenantId&#125; &lt;score&gt; &lt;logId&gt;
     *   ZADD audit:logs:_all         &lt;score&gt; &lt;logId&gt;
     * </pre>
     * Atomicity prevents orphaned index pointers if the process crashes
     * mid-write. Index sorted-set entries don't need per-entry TTL —
     * they're cleaned up by {@link #sweepStaleIndexEntries()} once per day.
     * Under steady state, index lookups in {@link #list} tolerate stale
     * pointers (null-body check at the read site), so the sweep is
     * eventual-consistency cleanup, not read-path critical.
     *
     * @since 0.1.25.20 (TTL added; script formerly had no expiry support)
     */
    private static final String LOG_AUDIT_LUA =
        "local ttl = tonumber(ARGV[4])\n" +
        "if ttl and ttl > 0 then\n" +
        "    redis.call('SET', KEYS[1], ARGV[1], 'EX', ttl)\n" +
        "else\n" +
        "    redis.call('SET', KEYS[1], ARGV[1])\n" +
        "end\n" +
        "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n" +
        "redis.call('ZADD', KEYS[3], ARGV[2], ARGV[3])\n" +
        "return 1\n";

    public void log(AuditLogEntry entry) {
        try (Jedis jedis = jedisPool.getResource()) {
            String logId = "log_" + UUID.randomUUID().toString().substring(0, 16);
            entry.setLogId(logId);
            entry.setTimestamp(Instant.now());
            String json = objectMapper.writeValueAsString(entry);
            String score = String.valueOf(entry.getTimestamp().toEpochMilli());
            long ttlSeconds = resolveTtlSeconds(entry);
            // Atomic write: SET log (with optional EX) + ZADD tenant index + ZADD global index
            jedis.eval(LOG_AUDIT_LUA,
                List.of("audit:log:" + logId,
                        "audit:logs:" + entry.getTenantId(),
                        "audit:logs:_all"),
                List.of(json, score, logId, String.valueOf(ttlSeconds)));
        } catch (Exception e) {
            // Audit log failure should not break the business operation that triggered it
            LOG.error("Failed to write audit log (non-fatal)", e);
        }
    }

    /**
     * Pick the per-entry TTL based on whether the entry is attributed to a
     * real tenant or the unauthenticated sentinel. Returns seconds; 0 means
     * "no expiry" (Lua branch skips the {@code EX} argument).
     */
    long resolveTtlSeconds(AuditLogEntry entry) {
        boolean unauth = AuditLogEntry.UNAUTHENTICATED_TENANT.equals(entry.getTenantId());
        int days = unauth ? unauthenticatedRetentionDays : authenticatedRetentionDays;
        return days > 0 ? (long) days * 86400L : 0L;
    }

    /**
     * Daily sweep of the audit sorted-set indexes to remove pointers whose
     * target {@code audit:log:{id}} key has already expired. Without this
     * sweep the {@code audit:logs:_all} and per-tenant sorted sets would
     * grow unbounded even though the underlying log records are gone —
     * stale pointers still cost memory (~32 bytes per entry) and lengthen
     * read-side scans in {@link #list}.
     *
     * <p>Runs at 03:00 server time by default (configurable via
     * {@code audit.sweep.cron}). Uses the {@code authenticatedRetentionDays}
     * as the global upper-bound — since unauthenticated entries expire
     * sooner, they're safely swept too. Deployments with {@code
     * authenticated.days=0} (indefinite) skip the sweep to avoid accidental
     * data loss.
     *
     * <p>The sweep is best-effort and non-fatal: any exception is logged
     * at ERROR but never propagates. Skipped on the current tick if Redis
     * is unavailable — the next tick retries.
     *
     * @since 0.1.25.20
     */
    @Scheduled(cron = "${audit.sweep.cron:0 0 3 * * *}")
    public void sweepStaleIndexEntries() {
        if (authenticatedRetentionDays <= 0) {
            // Indefinite retention — nothing to sweep. Skip explicitly so
            // ops can see "sweep disabled" in the absence of log lines
            // rather than guess at behavior.
            LOG.debug("Audit index sweep skipped — authenticated retention is indefinite");
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            // Upper bound: entries older than the longest retention window
            // have definitely expired, so their index pointers can be
            // removed without risk of deleting a live entry.
            long cutoffMillis = Instant.now().toEpochMilli()
                    - ((long) authenticatedRetentionDays * 86400L * 1000L);
            long removedGlobal = jedis.zremrangeByScore("audit:logs:_all",
                    Double.NEGATIVE_INFINITY, cutoffMillis);
            long removedTenants = 0;
            // Per-tenant indexes: SCAN for audit:logs:* keys (excludes :_all).
            // Audit write rate per tenant is low enough that even a large
            // fleet produces a bounded number of per-tenant keys. SCAN is
            // non-blocking and preferred over KEYS for any production
            // operation.
            ScanParams params = new ScanParams().match("audit:logs:*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                for (String indexKey : scan.getResult()) {
                    if ("audit:logs:_all".equals(indexKey)) {
                        continue; // already handled above
                    }
                    removedTenants += jedis.zremrangeByScore(indexKey,
                            Double.NEGATIVE_INFINITY, cutoffMillis);
                }
                cursor = scan.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            LOG.info("Audit index sweep completed — removed {} global + {} per-tenant stale pointers "
                            + "older than {} ms",
                    removedGlobal, removedTenants, cutoffMillis);
        } catch (Exception e) {
            // Sweep is best-effort — a failed tick does not impact business
            // operations. The next scheduled run retries.
            LOG.error("Audit index sweep failed (non-fatal — next tick will retry)", e);
        }
    }

    public List<AuditLogEntry> list(String tenantId, String keyId, String operation, Integer status,
                                     String resourceType, String resourceId,
                                     Instant from, Instant to, String cursor, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            // Determine the time range for the sorted set query
            double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
            double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
            // Use tenant-specific or global index
            String indexKey = (tenantId != null) ? "audit:logs:" + tenantId : "audit:logs:_all";

            // If cursor is provided, use its score as the maxScore ceiling to avoid
            // skipping entries that were filtered out. This is more reliable than
            // scanning for the cursor ID in the result set.
            if (cursor != null && !cursor.isBlank()) {
                Double cursorScore = jedis.zscore(indexKey, cursor);
                if (cursorScore != null) {
                    // Use score just below cursor to exclude the cursor entry itself
                    maxScore = Math.min(maxScore, cursorScore - 1);
                }
            }

            // Fetch more than needed to account for in-memory filtering
            List<String> ids = jedis.zrevrangeByScore(indexKey, maxScore, minScore, 0, limit * 3);
            List<AuditLogEntry> logs = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("audit:log:" + id);
                    if (data == null) {
                        // Expired entry — pointer still in the index but the
                        // underlying log key has been evicted by its TTL. The
                        // daily sweep will remove stale pointers eventually;
                        // read side just skips them transparently.
                        LOG.debug("Audit log data missing for id: {} (likely TTL-expired)", id);
                        continue;
                    }
                    AuditLogEntry entry = objectMapper.readValue(data, AuditLogEntry.class);
                    if (keyId != null && !keyId.equals(entry.getKeyId())) continue;
                    if (operation != null && !operation.equals(entry.getOperation())) continue;
                    if (status != null && !status.equals(entry.getStatus())) continue;
                    if (resourceType != null && !resourceType.equals(entry.getResourceType())) continue;
                    if (resourceId != null && !resourceId.equals(entry.getResourceId())) continue;
                    logs.add(entry);
                    if (logs.size() >= limit) break;
                } catch (Exception e) {
                    LOG.warn("Failed to parse log: {}", id, e);
                }
            }
            return logs;
        }
    }
    public List<AuditLogEntry> list(String tenantId, int limit) {
        return list(tenantId, null, null, null, null, null, null, null, null, limit);
    }
}
