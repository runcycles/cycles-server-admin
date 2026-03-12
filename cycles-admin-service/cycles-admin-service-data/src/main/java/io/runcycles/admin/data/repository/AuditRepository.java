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
    public void log(AuditLogEntry entry) {
        try (Jedis jedis = jedisPool.getResource()) {
            String logId = "log_" + UUID.randomUUID().toString().substring(0, 16);
            entry.setLogId(logId);
            entry.setTimestamp(Instant.now());
            jedis.set("audit:log:" + logId, objectMapper.writeValueAsString(entry));
            jedis.zadd("audit:logs:" + entry.getTenantId(), entry.getTimestamp().toEpochMilli(), logId);
            // Also add to global index for cross-tenant queries
            jedis.zadd("audit:logs:_all", entry.getTimestamp().toEpochMilli(), logId);
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
            List<String> ids = jedis.zrevrangeByScore(indexKey, maxScore, minScore, 0, limit * 3);
            List<AuditLogEntry> logs = new ArrayList<>();
            boolean pastCursor = (cursor == null || cursor.isBlank());
            for (String id : ids) {
                if (!pastCursor) {
                    if (id.equals(cursor)) pastCursor = true;
                    continue;
                }
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
