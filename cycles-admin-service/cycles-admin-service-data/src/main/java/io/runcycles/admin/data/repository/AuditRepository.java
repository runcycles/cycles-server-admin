package io.runcycles.admin.data.repository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class AuditRepository {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    // Lua script for atomic audit log creation: SET + 2x ZADD in one call
    // Prevents orphaned log entries if process crashes between operations
    private static final String LOG_AUDIT_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
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
            // Atomic write: SET log + ZADD tenant index + ZADD global index
            jedis.eval(LOG_AUDIT_LUA,
                List.of("audit:log:" + logId,
                        "audit:logs:" + entry.getTenantId(),
                        "audit:logs:_all"),
                List.of(json, score, logId));
        } catch (Exception e) {
            // Audit log failure should not break the business operation that triggered it
            LOG.error("Failed to write audit log (non-fatal)", e);
        }
    }
    public List<AuditLogEntry> list(String tenantId, String keyId, String operation, Integer status,
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
                        LOG.warn("Audit log data missing for id: {}", id);
                        continue;
                    }
                    AuditLogEntry entry = objectMapper.readValue(data, AuditLogEntry.class);
                    if (keyId != null && !keyId.equals(entry.getKeyId())) continue;
                    if (operation != null && !operation.equals(entry.getOperation())) continue;
                    if (status != null && !status.equals(entry.getStatus())) continue;
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
        return list(tenantId, null, null, null, null, null, null, limit);
    }
}
