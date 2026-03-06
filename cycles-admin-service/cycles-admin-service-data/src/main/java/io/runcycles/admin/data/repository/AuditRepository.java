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
        } catch (Exception e) {
            LOG.error("Failed to write audit log", e);
        }
    }
    public List<AuditLogEntry> list(String tenantId, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrevrange("audit:logs:" + tenantId, 0, limit - 1);
            List<AuditLogEntry> logs = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("audit:log:" + id);
                    if (data == null) {
                        LOG.warn("Audit log data missing for id: {}", id);
                        continue;
                    }
                    logs.add(objectMapper.readValue(data, AuditLogEntry.class));
                } catch (Exception e) {
                    LOG.warn("Failed to parse log: {}", id, e);
                }
            }
            return logs;
        }
    }
}
