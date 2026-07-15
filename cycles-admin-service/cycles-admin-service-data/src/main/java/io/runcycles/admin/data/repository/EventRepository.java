package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.logging.LogSanitizer;
import io.runcycles.admin.data.repository.support.ZSetAdaptivePager;
import io.runcycles.admin.data.repository.support.SortedQueryGuard;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class EventRepository {
    private static final Logger LOG = LoggerFactory.getLogger(EventRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired @Qualifier("redisObjectMapper") private ObjectMapper objectMapper;

    @Value("${events.retention.event-ttl-days:90}")
    private int eventTtlDays;

    // Lua script for atomic event creation with TTL
    private static final String SAVE_EVENT_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[4])\n" +
        "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])\n" +
        "redis.call('ZADD', KEYS[3], ARGV[2], ARGV[3])\n" +
        "if KEYS[4] then\n" +
        "    redis.call('SADD', KEYS[4], ARGV[3])\n" +
        "    redis.call('EXPIRE', KEYS[4], ARGV[4])\n" +
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

            String ttlSeconds = String.valueOf(eventTtlDays * 86400L);
            jedis.eval(SAVE_EVENT_LUA, keys, List.of(json, score, id, ttlSeconds));
        } catch (Exception e) {
            LOG.error("Failed to save admin event: event_id={} event_type={} tenant_id={} scope={} correlation_id={} request_id={} trace_id={}",
                event != null ? event.getEventId() : null,
                event != null && event.getEventType() != null ? event.getEventType().getValue() : null,
                event != null ? LogSanitizer.safe(event.getTenantId()) : null,
                event != null ? LogSanitizer.safe(event.getScope()) : null,
                event != null ? event.getCorrelationId() : null,
                event != null ? event.getRequestId() : null,
                event != null ? event.getTraceId() : null,
                e);
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
            LOG.error("Failed to find admin event: event_id={}", LogSanitizer.safe(eventId), e);
            throw new RuntimeException("Failed to find event", e);
        }
    }

    public List<Event> list(String tenantId, String eventType, String category, String scope,
                            String correlationId, Instant from, Instant to, String cursor, int limit) {
        return list(tenantId, eventType, category, scope, correlationId, from, to, cursor, limit,
            null, null, null, null);
    }

    public List<Event> list(String tenantId, String eventType, String category, String scope,
                            String correlationId, Instant from, Instant to, String cursor, int limit,
                            SortSpec sortSpec) {
        return list(tenantId, eventType, category, scope, correlationId, from, to, cursor, limit,
            sortSpec, null, null, null);
    }

    public List<Event> list(String tenantId, String eventType, String category, String scope,
                            String correlationId, Instant from, Instant to, String cursor, int limit,
                            SortSpec sortSpec, String search) {
        return list(tenantId, eventType, category, scope, correlationId, from, to, cursor, limit,
            sortSpec, search, null, null);
    }

    /**
     * List events with optional sort (spec v0.1.25.20 §V4) and optional
     * search (spec v0.1.25.21). Search is case-insensitive substring match
     * on {@code correlation_id} OR {@code scope}, AND-combined with other
     * filters, applied before cursor traversal.
     *
     * <p>Three paths:
     * <ul>
     *   <li>Null SortSpec OR {@code field=timestamp, dir=DESC} → legacy
     *       {@link Jedis#zrevrangeByScore} walk (unchanged behaviour).
     *   <li>{@code field=timestamp, dir=ASC} → {@link Jedis#zrangeByScore}
     *       walk with the cursor's score as the new minScore floor.
     *   <li>Non-timestamp fields → hydrate the complete ZSET window, apply
     *       all filters, sort in-memory by
     *       {@link #eventComparator}, then walk event_id cursor.
     * </ul>
     * Callers should pass narrower {@code from}/{@code to} windows where
     * possible; completeness is never traded for a silent hydration cap.
     */
    public List<Event> list(String tenantId, String eventType, String category, String scope,
                            String correlationId, Instant from, Instant to, String cursor, int limit,
                            SortSpec sortSpec, String search,
                            String traceId, String requestId) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            if (correlationId != null && !correlationId.isBlank()) {
                List<Event> byCorrelation = listByCorrelation(
                    jedis, correlationId, tenantId, eventType, category, scope, limit,
                    sortSpec, search, traceId, requestId);
                return byCorrelation;
            }
            if (sortSpec == null || isTimestampSort(sortSpec)) {
                return listByTimestamp(jedis, tenantId, eventType, category, scope,
                    from, to, cursor, limit, sortSpec, search, traceId, requestId);
            }
            return listSortedNonTimestamp(jedis, tenantId, eventType, category, scope,
                from, to, cursor, limit, sortSpec, search, traceId, requestId);
        }
    }

    private static boolean isTimestampSort(SortSpec sortSpec) {
        return "timestamp".equals(sortSpec.field());
    }

    /**
     * Ordered event IDs in the {@code [from,to]} timestamp window for one tenant
     * ({@code events:<tenant>}) or all tenants ({@code events:_all}), as a SINGLE
     * bounded {@code ZRANGEBYSCORE} — the exact, de-duplicated member set in
     * ascending (score, member) order.
     *
     * <p>Provided for webhook replay (governance #209, approach B): replay
     * hydrates fixed BATCHES over this whole id list instead of walking
     * {@link #list}'s request cursor. The fixed selection is important for
     * replay's all-or-narrow completeness check: hydration-thinned batches and
     * concurrent expiry cannot be mistaken for selection exhaustion. The ZSET
     * range is inherently ordered and de-duplicated. Capped at {@code maxIds}
     * (the replay scan ceiling).
     *
     * @return chronologically ordered IDs (ascending score; ties by member),
     *         at most {@code maxIds}; empty when the window has no events
     */
    public List<String> listEventIdsInRange(String tenantId, Instant from, Instant to, int maxIds) {
        if (maxIds <= 0) {
            return new ArrayList<>();
        }
        double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
        double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
        String indexKey = (tenantId != null) ? "events:" + tenantId : "events:_all";
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zrangeByScore(indexKey, minScore, maxScore, 0, maxIds);
        }
    }

    /**
     * Hydrate + parse the given event IDs, PRESERVING their order, dropping any
     * row that is missing/expired (a {@code null} {@code event:<id>} value) or
     * unparseable. Unparseable includes strict {@code @JsonCreator} failures on
     * unknown enum values — an unhydratable record is skipped (fail-closed:
     * never delivered), it does not abort the batch. Used by replay to hydrate
     * batches over the fixed id list from {@link #listEventIdsInRange}; a dropped
     * row here is NOT treated as range exhaustion by the caller (the id list is
     * already the full ordered member set).
     */
    public List<Event> hydrateByIds(List<String> ids) {
        List<Event> events = new ArrayList<>(ids.size());
        if (ids.isEmpty()) {
            return events;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            for (String id : ids) {
                String data = jedis.get("event:" + id);
                if (data == null) {
                    continue; // expired / evicted between the ZRANGE and hydration
                }
                try {
                    events.add(objectMapper.readValue(data, Event.class));
                } catch (Exception e) {
                    LOG.warn("Replay hydration skipped an unparseable event row (fail-closed): event_id={} error={}",
                        LogSanitizer.safe(id), LogSanitizer.safe(e.getMessage()));
                }
            }
        }
        return events;
    }

    private List<Event> listByTimestamp(Jedis jedis, String tenantId, String eventType,
                                        String category, String scope, Instant from, Instant to,
                                        String cursor, int limit, SortSpec sortSpec, String search,
                                        String traceId, String requestId) {
        double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
        double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
        String indexKey = (tenantId != null) ? "events:" + tenantId : "events:_all";
        boolean ascending = sortSpec != null && sortSpec.isAscending();

        return ZSetAdaptivePager.collect(jedis, indexKey, minScore, maxScore,
            cursor, limit, ascending, id -> {
            try {
                String data = jedis.get("event:" + id);
                if (data == null) {
                    LOG.warn("Admin event index points to missing row: event_id={} index_key={} tenant_id={} event_type_filter={} category_filter={} scope_filter={} request_id_filter={} trace_id_filter={}",
                        LogSanitizer.safe(id), LogSanitizer.safe(indexKey), LogSanitizer.safe(tenantId), eventType, category, LogSanitizer.safe(scope), requestId, traceId);
                    return null;
                }
                Event event = objectMapper.readValue(data, Event.class);
                if (!matchesFilters(event, eventType, category, scope, traceId, requestId)) return null;
                if (!matchesSearch(event, search)) return null;
                return event;
            } catch (Exception e) {
                LOG.warn("Failed to parse admin event row: event_id={} index_key={} tenant_id={} event_type_filter={} category_filter={} scope_filter={} request_id_filter={} trace_id_filter={}",
                    LogSanitizer.safe(id), LogSanitizer.safe(indexKey), LogSanitizer.safe(tenantId), eventType, category, LogSanitizer.safe(scope), requestId, traceId, e);
                return null;
            }
        });
    }

    private List<Event> listSortedNonTimestamp(Jedis jedis, String tenantId, String eventType,
                                                String category, String scope, Instant from, Instant to,
                                                String cursor, int limit, SortSpec sortSpec, String search,
                                                String traceId, String requestId) {
        double minScore = (from != null) ? from.toEpochMilli() : Double.NEGATIVE_INFINITY;
        double maxScore = (to != null) ? to.toEpochMilli() : Double.POSITIVE_INFINITY;
        String indexKey = (tenantId != null) ? "events:" + tenantId : "events:_all";
        SortedQueryGuard.requireBounded(jedis.zcount(indexKey, minScore, maxScore), "event");
        // A correct non-primary sort must see the complete filtered window.
        List<String> ids = jedis.zrevrangeByScore(indexKey, maxScore, minScore);
        List<Event> all = new ArrayList<>();
        for (String id : ids) {
            try {
                String data = jedis.get("event:" + id);
                if (data == null) continue;
                Event event = objectMapper.readValue(data, Event.class);
                if (!matchesFilters(event, eventType, category, scope, traceId, requestId)) continue;
                if (!matchesSearch(event, search)) continue;
                all.add(event);
            } catch (Exception e) {
                LOG.warn("Failed to parse admin event row: event_id={} index_key={} tenant_id={} event_type_filter={} category_filter={} scope_filter={} request_id_filter={} trace_id_filter={}",
                    LogSanitizer.safe(id), LogSanitizer.safe(indexKey), LogSanitizer.safe(tenantId), eventType, category, LogSanitizer.safe(scope), requestId, traceId, e);
            }
        }
        all.sort(eventComparator(sortSpec));
        List<Event> results = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        for (Event e : all) {
            if (!pastCursor) {
                if (cursor.equals(e.getEventId())) pastCursor = true;
                continue;
            }
            results.add(e);
            if (results.size() >= limit) break;
        }
        return results;
    }

    private boolean matchesFilters(Event event, String eventType, String category, String scope,
                                   String traceId, String requestId) {
        if (eventType != null && !eventType.equals(event.getEventType().getValue())) return false;
        if (category != null && !category.equals(event.getCategory().getValue())) return false;
        if (scope != null && (event.getScope() == null || !event.getScope().startsWith(scope))) return false;
        // v0.1.25.31: exact-match on trace_id / request_id for cross-surface
        // correlation JOINs. Null-field entries (historical writes before the
        // v0.1.25.31 upgrade, or off-request emissions) cannot match a
        // supplied filter value — they're excluded rather than returned.
        if (traceId != null && !traceId.equals(event.getTraceId())) return false;
        if (requestId != null && !requestId.equals(event.getRequestId())) return false;
        return true;
    }

    /**
     * Spec v0.1.25.21: listEvents search matches {@code correlation_id}
     * OR {@code scope} as a case-insensitive substring. Null search =
     * no filter.
     */
    private static boolean matchesSearch(Event event, String search) {
        if (search == null) return true;
        return SearchSpec.matches(event.getCorrelationId(), search)
            || SearchSpec.matches(event.getScope(), search);
    }

    /**
     * Null-safe comparator on the whitelisted non-timestamp sort fields.
     * event_id tie-breaker keeps the order total so cursor resume is
     * deterministic. Unknown fields fall through to the tie-breaker —
     * the controller whitelist already rejects those at the edge.
     */
    static Comparator<Event> eventComparator(SortSpec sortSpec) {
        String field = sortSpec.field();
        Comparator<Event> primary;
        switch (field) {
            case "event_type":
                primary = Comparator.comparing(
                    e -> e.getEventType() == null ? null : e.getEventType().getValue(),
                    Comparator.nullsLast(String::compareTo));
                break;
            case "category":
                primary = Comparator.comparing(
                    e -> e.getCategory() == null ? null : e.getCategory().getValue(),
                    Comparator.nullsLast(String::compareTo));
                break;
            case "scope":
                primary = Comparator.comparing(Event::getScope,
                    Comparator.nullsLast(String::compareTo));
                break;
            case "tenant_id":
                primary = Comparator.comparing(Event::getTenantId,
                    Comparator.nullsLast(String::compareTo));
                break;
            case "timestamp":
                primary = Comparator.comparing(Event::getTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            default:
                primary = Comparator.comparing(Event::getEventId,
                    Comparator.nullsLast(String::compareTo));
                break;
        }
        Comparator<Event> withTieBreak = primary.thenComparing(
            Event::getEventId, Comparator.nullsLast(String::compareTo));
        return sortSpec.isAscending() ? withTieBreak : withTieBreak.reversed();
    }

    private List<Event> listByCorrelation(Jedis jedis, String correlationId, String tenantId,
                                          String eventType, String category, String scope, int limit,
                                          SortSpec sortSpec, String search,
                                          String traceId, String requestId) {
        Set<String> ids = jedis.smembers("events:correlation:" + correlationId);
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        List<Event> events = new ArrayList<>();
        for (String id : ids) {
            try {
                String data = jedis.get("event:" + id);
                if (data == null) continue;
                Event event = objectMapper.readValue(data, Event.class);
                if (tenantId != null && !tenantId.equals(event.getTenantId())) continue;
                if (!matchesFilters(event, eventType, category, scope, traceId, requestId)) continue;
                if (!matchesSearch(event, search)) continue;
                events.add(event);
            } catch (Exception e) {
                LOG.warn("Failed to parse admin event row: event_id={} correlation_id={} tenant_id={} event_type_filter={} category_filter={} scope_filter={} request_id_filter={} trace_id_filter={}",
                    LogSanitizer.safe(id), correlationId, LogSanitizer.safe(tenantId), eventType, category, LogSanitizer.safe(scope), requestId, traceId, e);
            }
        }
        if (sortSpec != null) {
            events.sort(eventComparator(sortSpec));
        }
        return events.size() > limit ? events.subList(0, limit) : events;
    }
}
