package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class EventRepository {
    private static final Logger LOG = LoggerFactory.getLogger(EventRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    // Lua script for atomic event creation: SET event JSON + ZADD tenant index + ZADD global index
    // + optional SADD correlation index. Prevents orphaned entries on partial failure.
    private static final String SAVE_EVENT_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n" +
        "redis.call('ZADD', KEYS[3], ARGV[2], ARGV[3])\n" +
        "if KEYS[4] then\n" +
        "    redis.call('SADD', KEYS[4], ARGV[3])\n" +
        "end\n" +
        "return 1\n";

    public void save(Event event) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (event.getEventId() == null) {
                event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }
            if (event.getTimestamp() == null) {
                event.setTimestamp(Instant.now());
            }
            String json = objectMapper.writeValueAsString(event);
            String score = String.valueOf(event.getTimestamp().toEpochMilli());

            String id = event.getEventId();
            List<String> keys = new ArrayList<>();
            keys.add("event:" + id);
            keys.add("events:" + event.getTenantId());
            keys.add("events:_all");
            if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
                keys.add("events:correlation:" + event.getCorrelationId());
            }

            jedis.eval(SAVE_EVENT_LUA, keys, List.of(json, score, id));
        } catch (Exception e) {
            LOG.error("Failed to save event", e);
            throw new RuntimeException("Failed to save event", e);
        }
    }

    public Event findById(String eventId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("event:" + eventId);
            if (data == null) {
                throw GovernanceException.eventNotFound(eventId);
            }
            return objectMapper.readValue(data, Event.class);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to find event: {}", eventId, e);
            throw new RuntimeException("Failed to find event", e);
        }
    }

    public List<Event> list(String tenantId, String eventType, String category, String scope,
                            String correlationId, Instant from, Instant to, String cursor, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            // If correlationId filter is provided, use the correlation SET instead of ZSET pagination
            if (correlationId != null && !correlationId.isBlank()) {
                return listByCorrelation(jedis, correlationId, tenantId, eventType, category, scope, limit);
            }

            double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
            double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
            String indexKey = (tenantId != null) ? "events:" + tenantId : "events:_all";

            if (cursor != null && !cursor.isBlank()) {
                Double cursorScore = jedis.zscore(indexKey, cursor);
                if (cursorScore != null) {
                    maxScore = Math.min(maxScore, cursorScore - 1);
                }
            }

            // Fetch more than needed to account for in-memory filtering
            List<String> ids = jedis.zrevrangeByScore(indexKey, maxScore, minScore, 0, limit * 3);
            List<Event> events = new ArrayList<>();
            for (String id : ids) {
                try {
                    String data = jedis.get("event:" + id);
                    if (data == null) {
                        LOG.warn("Event data missing for id: {}", id);
                        continue;
                    }
                    Event event = objectMapper.readValue(data, Event.class);
                    if (eventType != null && !eventType.equals(event.getEventType().getValue())) continue;
                    if (category != null && !category.equals(event.getCategory().getValue())) continue;
                    if (scope != null && (event.getScope() == null || !event.getScope().startsWith(scope))) continue;
                    events.add(event);
                    if (events.size() >= limit) break;
                } catch (Exception e) {
                    LOG.warn("Failed to parse event: {}", id, e);
                }
            }
            return events;
        }
    }

    private List<Event> listByCorrelation(Jedis jedis, String correlationId, String tenantId,
                                          String eventType, String category, String scope, int limit) {
        Set<String> ids = jedis.smembers("events:correlation:" + correlationId);
        List<Event> events = new ArrayList<>();
        for (String id : ids) {
            try {
                String data = jedis.get("event:" + id);
                if (data == null) continue;
                Event event = objectMapper.readValue(data, Event.class);
                if (tenantId != null && !tenantId.equals(event.getTenantId())) continue;
                if (eventType != null && !eventType.equals(event.getEventType().getValue())) continue;
                if (category != null && !category.equals(event.getCategory().getValue())) continue;
                if (scope != null && (event.getScope() == null || !event.getScope().startsWith(scope))) continue;
                events.add(event);
                if (events.size() >= limit) break;
            } catch (Exception e) {
                LOG.warn("Failed to parse event: {}", id, e);
            }
        }
        return events;
    }
}
