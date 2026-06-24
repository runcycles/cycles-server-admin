package io.runcycles.admin.data.repository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortSpec;
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
     * Retention for entries NOT attributed to the {@code __unauth__}
     * sentinel — every real-tenant entry, every {@code __admin__}
     * platform-plane entry, and every authenticated failure. Default
     * 400 days = SOC2 Type II 12-month lookback + 1-month buffer for
     * post-period auditor lag. Set to {@code 0} for indefinite retention
     * (legal hold, HIPAA-adjacent deployments, or environments that
     * offload to an archive store).
     *
     * @since 0.1.25.20
     */
    @Value("${audit.retention.authenticated.days:400}")
    private int authenticatedRetentionDays;

    /**
     * Retention for entries attributed to the {@code __unauth__} sentinel.
     * Applies to pre-auth failures (missing / invalid credentials, path
     * traversal rejections, etc.). Default 30 days — enough for brute-
     * force / credential-stuffing post-mortem; aggregate attempt volume
     * stays visible via the {@code cycles_admin_audit_writes_total}
     * Prometheus counter regardless of this setting. Set to {@code 0}
     * for indefinite.
     *
     * <p>Historical entries with {@code tenant_id == "<unauthenticated>"}
     * (legacy sentinel, written by v0.1.25.20..v0.1.25.27) route to this
     * same tier so they age out on the same schedule as fresh entries
     * with the new sentinel.
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
            LOG.error("Failed to write audit log; business operation continues: tenant_id={} operation={} resource_type={} resource_id={} status={} error_code={} request_id={} trace_id={}",
                entry != null ? entry.getTenantId() : null,
                entry != null ? entry.getOperation() : null,
                entry != null ? entry.getResourceType() : null,
                entry != null ? entry.getResourceId() : null,
                entry != null ? entry.getStatus() : null,
                entry != null ? entry.getErrorCode() : null,
                entry != null ? entry.getRequestId() : null,
                entry != null ? entry.getTraceId() : null,
                e);
        }
    }

    /**
     * Pick the per-entry TTL from the tenant_id sentinel.
     *
     * <p>Short bucket (unauthenticatedRetentionDays): entries attributed
     * to {@link AuditLogEntry#UNAUTH_TENANT} OR the legacy
     * {@link AuditLogEntry#LEGACY_UNAUTHENTICATED_TENANT} value — both
     * are pre-auth failures, just written by different server revisions.
     *
     * <p>Long bucket (authenticatedRetentionDays): everything else,
     * including real tenants AND the {@link AuditLogEntry#ADMIN_TENANT}
     * platform-plane sentinel (admin actions are high-signal security
     * events, not DDoS-amplifiable noise).
     *
     * <p>Returns seconds; 0 means "no expiry" (Lua branch skips the
     * {@code EX} argument).
     *
     * <p>Public to permit unit + property-based tests (v0.1.25.21) in
     * sibling modules to verify the tier-selection contract directly.
     * Not intended for external callers — prefer {@link #log(AuditLogEntry)}.
     */
    public long resolveTtlSeconds(AuditLogEntry entry) {
        String tenantId = entry.getTenantId();
        boolean unauth = AuditLogEntry.UNAUTH_TENANT.equals(tenantId)
                || AuditLogEntry.LEGACY_UNAUTHENTICATED_TENANT.equals(tenantId);
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
            LOG.info("Audit index sweep completed: removed_global={} removed_per_tenant={} cutoff_ms={} authenticated_retention_days={}",
                    removedGlobal, removedTenants, cutoffMillis, authenticatedRetentionDays);
        } catch (Exception e) {
            // Sweep is best-effort — a failed tick does not impact business
            // operations. The next scheduled run retries.
            LOG.error("Audit index sweep failed; next tick will retry: authenticated_retention_days={} unauthenticated_retention_days={}",
                authenticatedRetentionDays, unauthenticatedRetentionDays, e);
        }
    }

    public List<AuditLogEntry> list(String tenantId, String keyId, List<String> operation, Integer status,
                                     List<String> resourceType, String resourceId,
                                     Instant from, Instant to, String cursor, int limit) {
        return list(tenantId, keyId, operation, status, resourceType, resourceId,
            from, to, cursor, limit, null, null, null, null, null, null, null, null);
    }

    public List<AuditLogEntry> list(String tenantId, String keyId, List<String> operation, Integer status,
                                     List<String> resourceType, String resourceId,
                                     Instant from, Instant to, String cursor, int limit,
                                     SortSpec sortSpec) {
        return list(tenantId, keyId, operation, status, resourceType, resourceId,
            from, to, cursor, limit, sortSpec, null, null, null, null, null, null, null);
    }

    public List<AuditLogEntry> list(String tenantId, String keyId, List<String> operation, Integer status,
                                     List<String> resourceType, String resourceId,
                                     Instant from, Instant to, String cursor, int limit,
                                     SortSpec sortSpec, String search) {
        return list(tenantId, keyId, operation, status, resourceType, resourceId,
            from, to, cursor, limit, sortSpec, search, null, null, null, null, null, null);
    }

    public List<AuditLogEntry> list(String tenantId, String keyId, List<String> operation, Integer status,
                                     List<String> resourceType, String resourceId,
                                     Instant from, Instant to, String cursor, int limit,
                                     SortSpec sortSpec, String search,
                                     List<String> errorCodes, List<String> errorCodeExcludes,
                                     Integer statusMin, Integer statusMax) {
        return list(tenantId, keyId, operation, status, resourceType, resourceId,
            from, to, cursor, limit, sortSpec, search,
            errorCodes, errorCodeExcludes, statusMin, statusMax, null, null);
    }

    /**
     * List audit logs with optional sort (spec v0.1.25.20 §V4), optional
     * search (spec v0.1.25.21), and — as of spec v0.1.25.24 — optional
     * IN-list filters on {@code operation} / {@code resource_type} /
     * {@code error_code}, an exclusion list on {@code error_code}, and a
     * numeric range on {@code status}. All predicates AND-compose; within
     * one IN-list predicate the match is OR.
     *
     * <p>NULL-semantics on {@code error_code}:
     * <ul>
     *   <li>IN-list ({@code errorCodes}) — entries with null {@code error_code}
     *       never match (success entries are excluded when the auditor asks
     *       "show me failures of type X").
     *   <li>NOT-IN-list ({@code errorCodeExcludes}) — entries with null
     *       {@code error_code} always pass (hiding noisy codes shouldn't
     *       hide successes).
     * </ul>
     *
     * <p>{@code search} is case-insensitive substring match on
     * {@code resource_id} OR {@code log_id} OR {@code error_code} OR
     * {@code operation} (spec v0.1.25.24 extends the match set to cover
     * error-code/operation free-text lookups).
     *
     * <p>All filter and search predicates are applied BEFORE cursor
     * commitment — the v0.1.25.25 cursor-stability invariant is preserved
     * end-to-end through the new predicates too.
     *
     * <p>Three paths mirror {@link EventRepository#list}:
     * <ul>
     *   <li>Null SortSpec OR {@code field=timestamp, dir=DESC} → legacy
     *       {@link Jedis#zrevrangeByScore} walk (unchanged behaviour).
     *   <li>{@code field=timestamp, dir=ASC} → {@link Jedis#zrangeByScore}
     *       walk with the cursor's score as the new minScore floor.
     *   <li>Non-timestamp fields → hydrate up to {@link #SORTED_HYDRATE_CAP}
     *       IDs from the ZSET window, apply all filters, sort in-memory
     *       via {@link #auditLogComparator}, then walk log_id cursor.
     * </ul>
     * Callers requesting non-timestamp sort on very large windows should
     * narrow {@code from}/{@code to} to keep hydration bounded.
     */
    public List<AuditLogEntry> list(String tenantId, String keyId, List<String> operation, Integer status,
                                     List<String> resourceType, String resourceId,
                                     Instant from, Instant to, String cursor, int limit,
                                     SortSpec sortSpec, String search,
                                     List<String> errorCodes, List<String> errorCodeExcludes,
                                     Integer statusMin, Integer statusMax,
                                     String traceId, String requestId) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            if (sortSpec == null || isTimestampSort(sortSpec)) {
                return listByTimestamp(jedis, tenantId, keyId, operation, status,
                    resourceType, resourceId, from, to, cursor, limit, sortSpec, search,
                    errorCodes, errorCodeExcludes, statusMin, statusMax, traceId, requestId);
            }
            return listSortedNonTimestamp(jedis, tenantId, keyId, operation, status,
                resourceType, resourceId, from, to, cursor, limit, sortSpec, search,
                errorCodes, errorCodeExcludes, statusMin, statusMax, traceId, requestId);
        }
    }

    private static boolean isTimestampSort(SortSpec sortSpec) {
        return "timestamp".equals(sortSpec.field());
    }

    /**
     * Upper bound on IDs hydrated for a non-timestamp sort pass. Sorted
     * walks need to see the full filter-matching population before the
     * cursor walk; without a ceiling a broad time window would OOM. At
     * scale, operators should narrow the time window.
     */
    private static final int SORTED_HYDRATE_CAP = 2000;

    private List<AuditLogEntry> listByTimestamp(Jedis jedis, String tenantId, String keyId,
                                                 List<String> operations, Integer status,
                                                 List<String> resourceTypes, String resourceId,
                                                 Instant from, Instant to, String cursor, int limit,
                                                 SortSpec sortSpec, String search,
                                                 List<String> errorCodes, List<String> errorCodeExcludes,
                                                 Integer statusMin, Integer statusMax,
                                                 String traceId, String requestId) {
        double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
        double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
        String indexKey = (tenantId != null) ? "audit:logs:" + tenantId : "audit:logs:_all";
        boolean ascending = sortSpec != null && sortSpec.isAscending();

        if (cursor != null && !cursor.isBlank()) {
            Double cursorScore = jedis.zscore(indexKey, cursor);
            if (cursorScore != null) {
                if (ascending) {
                    minScore = Math.max(minScore, cursorScore + 1);
                } else {
                    maxScore = Math.min(maxScore, cursorScore - 1);
                }
            }
        }

        List<String> ids = ascending
            ? jedis.zrangeByScore(indexKey, minScore, maxScore, 0, limit * 3)
            : jedis.zrevrangeByScore(indexKey, maxScore, minScore, 0, limit * 3);
        List<AuditLogEntry> logs = new ArrayList<>();
        for (String id : ids) {
            try {
                String data = jedis.get("audit:log:" + id);
                if (data == null) {
                    LOG.debug("Audit log data missing for id: {} (likely TTL-expired)", id);
                    continue;
                }
                AuditLogEntry entry = objectMapper.readValue(data, AuditLogEntry.class);
                if (!matchesFilters(entry, keyId, operations, status, resourceTypes, resourceId,
                        errorCodes, errorCodeExcludes, statusMin, statusMax, traceId, requestId)) continue;
                if (!matchesSearch(entry, search)) continue;
                logs.add(entry);
                if (logs.size() >= limit) break;
            } catch (Exception e) {
                LOG.warn("Failed to parse audit log entry: log_id={} index_key={} tenant_id={} request_id_filter={} trace_id_filter={}",
                    id, indexKey, tenantId, requestId, traceId, e);
            }
        }
        return logs;
    }

    private List<AuditLogEntry> listSortedNonTimestamp(Jedis jedis, String tenantId, String keyId,
                                                        List<String> operations, Integer status,
                                                        List<String> resourceTypes, String resourceId,
                                                        Instant from, Instant to, String cursor, int limit,
                                                        SortSpec sortSpec, String search,
                                                        List<String> errorCodes, List<String> errorCodeExcludes,
                                                        Integer statusMin, Integer statusMax,
                                                        String traceId, String requestId) {
        double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
        double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
        String indexKey = (tenantId != null) ? "audit:logs:" + tenantId : "audit:logs:_all";
        // Hydrate a bounded window — cursor is applied after sort on log_id.
        List<String> ids = jedis.zrevrangeByScore(indexKey, maxScore, minScore, 0, SORTED_HYDRATE_CAP);
        List<AuditLogEntry> all = new ArrayList<>();
        for (String id : ids) {
            try {
                String data = jedis.get("audit:log:" + id);
                if (data == null) continue;
                AuditLogEntry entry = objectMapper.readValue(data, AuditLogEntry.class);
                if (!matchesFilters(entry, keyId, operations, status, resourceTypes, resourceId,
                        errorCodes, errorCodeExcludes, statusMin, statusMax, traceId, requestId)) continue;
                if (!matchesSearch(entry, search)) continue;
                all.add(entry);
            } catch (Exception e) {
                LOG.warn("Failed to parse audit log entry: log_id={} index_key={} tenant_id={} request_id_filter={} trace_id_filter={}",
                    id, indexKey, tenantId, requestId, traceId, e);
            }
        }
        all.sort(auditLogComparator(sortSpec));
        List<AuditLogEntry> results = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        for (AuditLogEntry e : all) {
            if (!pastCursor) {
                if (cursor.equals(e.getLogId())) pastCursor = true;
                continue;
            }
            results.add(e);
            if (results.size() >= limit) break;
        }
        return results;
    }

    private boolean matchesFilters(AuditLogEntry entry, String keyId, List<String> operations,
                                    Integer status, List<String> resourceTypes, String resourceId,
                                    List<String> errorCodes, List<String> errorCodeExcludes,
                                    Integer statusMin, Integer statusMax,
                                    String traceId, String requestId) {
        if (keyId != null && !keyId.equals(entry.getKeyId())) return false;
        if (operations != null && !operations.isEmpty() && !operations.contains(entry.getOperation())) return false;
        if (status != null && !status.equals(entry.getStatus())) return false;
        // Numeric range on status. A null entry.status cannot satisfy a bound —
        // treat it as out-of-range so the predicate rejects rather than silently
        // passing bounds-holding entries as matches.
        if (statusMin != null && (entry.getStatus() == null || entry.getStatus() < statusMin)) return false;
        if (statusMax != null && (entry.getStatus() == null || entry.getStatus() > statusMax)) return false;
        if (resourceTypes != null && !resourceTypes.isEmpty()
                && !resourceTypes.contains(entry.getResourceType())) return false;
        if (resourceId != null && !resourceId.equals(entry.getResourceId())) return false;
        // IN-list on error_code: null entry.errorCode (success entries) never
        // matches — auditor asking "show me error X" never wants success rows.
        if (errorCodes != null && !errorCodes.isEmpty()) {
            if (entry.getErrorCode() == null) return false;
            if (!errorCodes.contains(entry.getErrorCode())) return false;
        }
        // NOT-IN-list on error_code: null entry.errorCode always passes —
        // hiding noisy codes shouldn't also hide successes.
        if (errorCodeExcludes != null && !errorCodeExcludes.isEmpty()
                && entry.getErrorCode() != null
                && errorCodeExcludes.contains(entry.getErrorCode())) {
            return false;
        }
        // v0.1.25.31: exact-match on trace_id / request_id for cross-surface
        // correlation JOINs. Null-field entries (historical writes before the
        // v0.1.25.31 upgrade, or internal sweeper-emitted entries) cannot
        // match a supplied filter value — they're excluded rather than
        // silently returned.
        if (traceId != null && !traceId.equals(entry.getTraceId())) return false;
        if (requestId != null && !requestId.equals(entry.getRequestId())) return false;
        return true;
    }

    /**
     * listAuditLogs search matches {@code resource_id} OR {@code log_id}
     * OR {@code error_code} OR {@code operation} as a case-insensitive
     * substring. Null search = no filter. The error_code/operation match
     * fields were added in spec v0.1.25.24 so free-text "quota" finds
     * BUDGET_EXCEEDED without the auditor knowing the exact enum name.
     */
    private static boolean matchesSearch(AuditLogEntry entry, String search) {
        if (search == null) return true;
        return SearchSpec.matches(entry.getResourceId(), search)
            || SearchSpec.matches(entry.getLogId(), search)
            || SearchSpec.matches(entry.getErrorCode(), search)
            || SearchSpec.matches(entry.getOperation(), search);
    }

    /**
     * Null-safe comparator on the whitelisted non-timestamp sort fields.
     * log_id tie-breaker keeps the order total so cursor resume is
     * deterministic. Unknown fields fall through to the tie-breaker —
     * the controller whitelist already rejects those at the edge.
     */
    static Comparator<AuditLogEntry> auditLogComparator(SortSpec sortSpec) {
        String field = sortSpec.field();
        Comparator<AuditLogEntry> primary;
        switch (field) {
            case "operation":
                primary = Comparator.comparing(AuditLogEntry::getOperation,
                    Comparator.nullsLast(String::compareTo));
                break;
            case "resource_type":
                primary = Comparator.comparing(AuditLogEntry::getResourceType,
                    Comparator.nullsLast(String::compareTo));
                break;
            case "tenant_id":
                primary = Comparator.comparing(AuditLogEntry::getTenantId,
                    Comparator.nullsLast(String::compareTo));
                break;
            case "key_id":
                primary = Comparator.comparing(AuditLogEntry::getKeyId,
                    Comparator.nullsLast(String::compareTo));
                break;
            case "status":
                primary = Comparator.comparing(AuditLogEntry::getStatus,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "timestamp":
                primary = Comparator.comparing(AuditLogEntry::getTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            default:
                primary = Comparator.comparing(AuditLogEntry::getLogId,
                    Comparator.nullsLast(String::compareTo));
                break;
        }
        Comparator<AuditLogEntry> withTieBreak = primary.thenComparing(
            AuditLogEntry::getLogId, Comparator.nullsLast(String::compareTo));
        return sortSpec.isAscending() ? withTieBreak : withTieBreak.reversed();
    }

    public List<AuditLogEntry> list(String tenantId, int limit) {
        return list(tenantId, null, (List<String>) null, null, (List<String>) null, null,
            null, null, null, limit);
    }
}
