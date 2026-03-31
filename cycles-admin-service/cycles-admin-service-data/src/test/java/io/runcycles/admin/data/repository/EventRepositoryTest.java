package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @InjectMocks private EventRepository repository;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
    }

    // ---- save() ----

    @Test
    void save_setsEventIdAndTimestamp() {
        Event event = Event.builder()
                .tenantId("t1")
                .eventType(EventType.TENANT_CREATED)
                .category(EventCategory.TENANT)
                .source("cycles-admin")
                .build();

        repository.save(event);

        assertThat(event.getEventId()).startsWith("evt_");
        assertThat(event.getTimestamp()).isNotNull();
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void save_callsLuaWithCorrectKeysAndArgs() throws Exception {
        Event event = Event.builder()
                .tenantId("t1")
                .eventType(EventType.BUDGET_CREATED)
                .category(EventCategory.BUDGET)
                .source("cycles-admin")
                .build();

        repository.save(event);

        verify(jedis).eval(anyString(), argThat((List<String> keys) -> {
            return keys.size() == 3
                && keys.get(0).startsWith("event:evt_")
                && keys.get(1).equals("events:t1")
                && keys.get(2).equals("events:_all");
        }), argThat((List<String> args) -> {
            try {
                // ARGV[0] is JSON, ARGV[1] is score, ARGV[2] is eventId
                Event stored = objectMapper.readValue(args.get(0), Event.class);
                return "t1".equals(stored.getTenantId())
                    && stored.getEventType() == EventType.BUDGET_CREATED;
            } catch (Exception e) {
                return false;
            }
        }));
    }

    @Test
    void save_withCorrelationId_addsCorrelationKey() {
        Event event = Event.builder()
                .tenantId("t1")
                .eventType(EventType.BUDGET_FUNDED)
                .category(EventCategory.BUDGET)
                .source("cycles-admin")
                .correlationId("corr_123")
                .build();

        repository.save(event);

        verify(jedis).eval(anyString(), argThat((List<String> keys) ->
            keys.size() == 4 && keys.get(3).equals("events:correlation:corr_123")
        ), anyList());
    }

    @Test
    void save_preservesExistingTimestamp() {
        Instant fixedTime = Instant.parse("2025-06-15T10:00:00Z");
        Event event = Event.builder()
                .tenantId("t1")
                .eventType(EventType.TENANT_CREATED)
                .category(EventCategory.TENANT)
                .source("cycles-admin")
                .timestamp(fixedTime)
                .build();

        repository.save(event);

        assertThat(event.getTimestamp()).isEqualTo(fixedTime);
    }

    // ---- findById() ----

    @Test
    void findById_success_returnsEvent() throws Exception {
        Event event = Event.builder()
                .eventId("evt_abc123")
                .eventType(EventType.TENANT_CREATED)
                .category(EventCategory.TENANT)
                .tenantId("t1")
                .source("cycles-admin")
                .timestamp(Instant.now())
                .build();
        String json = objectMapper.writeValueAsString(event);
        when(jedis.get("event:evt_abc123")).thenReturn(json);

        Event result = repository.findById("evt_abc123");

        assertThat(result.getEventId()).isEqualTo("evt_abc123");
        assertThat(result.getEventType()).isEqualTo(EventType.TENANT_CREATED);
        assertThat(result.getTenantId()).isEqualTo("t1");
    }

    @Test
    void findById_notFound_throwsGovernanceException() {
        when(jedis.get("event:evt_missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.findById("evt_missing"))
                .isInstanceOf(GovernanceException.class)
                .satisfies(ex -> {
                    GovernanceException ge = (GovernanceException) ex;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                    assertThat(ge.getMessage()).contains("evt_missing");
                });
    }

    // ---- list() ----

    @Test
    void list_withTenantFilter_usesCorrectIndex() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(2);
        verify(jedis).zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_withoutTenant_usesGlobalIndex() throws Exception {
        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:_all"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list(null, null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("events:_all"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_withCursorPagination_adjustsMaxScore() throws Exception {
        when(jedis.zscore("events:t1", "evt_cursor")).thenReturn(5000.0);

        List<String> ids = List.of("evt_2");
        when(jedis.zrevrangeByScore(eq("events:t1"), eq(4999.0), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.TENANT_UPDATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", null, null, null, null, null, null, "evt_cursor", 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt_2");
    }

    @Test
    void list_filtersByEventType() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", "tenant.created", null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EventType.TENANT_CREATED);
    }

    @Test
    void list_filtersByCategory() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", null, "BUDGET", null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo(EventCategory.BUDGET);
    }

    @Test
    void list_filtersByScopePrefix() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/eng/team1").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/sales").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", null, null, "org/eng", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).startsWith("org/eng");
    }

    @Test
    void list_emptyResults_returnsEmptyList() {
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(List.of());

        List<Event> result = repository.list("t1", null, null, null, null, null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_zeroLimit_returnsEmpty() {
        List<Event> result = repository.list("t1", null, null, null, null, null, null, null, 0);
        assertThat(result).isEmpty();
    }

    @Test
    void list_withTimeRange_usesScoreBounds() throws Exception {
        Instant from = Instant.ofEpochMilli(1000);
        Instant to = Instant.ofEpochMilli(2000);

        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:t1"), eq(2000.0), eq(1000.0), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list("t1", null, null, null, null, from, to, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("events:t1"), eq(2000.0), eq(1000.0), eq(0), anyInt());
    }

    @Test
    void list_withCursorNotInIndex_ignoresCursor() throws Exception {
        when(jedis.zscore("events:t1", "evt_unknown")).thenReturn(null);

        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:t1"), eq(Double.POSITIVE_INFINITY), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list("t1", null, null, null, null, null, null, "evt_unknown", 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_cursorWithTimeRange_usesMinOfCursorAndTo() throws Exception {
        Instant to = Instant.ofEpochMilli(8000);
        when(jedis.zscore("events:t1", "evt_cursor")).thenReturn(5000.0);

        List<String> ids = List.of("evt_2");
        // cursorScore - 1 = 4999, to = 8000, min(4999, 8000) = 4999
        when(jedis.zrevrangeByScore(eq("events:t1"), eq(4999.0), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", null, null, null, null, null, to, "evt_cursor", 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_byCorrelation_returnsFilteredEvents() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").scope("org/eng").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t2").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/sales").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", null, null, null, "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("t1");
    }

    @Test
    void list_byCorrelation_filtersByEventType() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list(null, "budget.created", null, null, "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EventType.BUDGET_CREATED);
    }

    @Test
    void list_byCorrelation_filtersByCategoryAndScope() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/eng").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").scope("org/sales").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list(null, null, "BUDGET", "org/eng", "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo(EventCategory.BUDGET);
    }

    @Test
    void list_byCorrelation_missingData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        when(jedis.get("event:evt_1")).thenReturn(null);
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list(null, null, null, null, "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_byCorrelation_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        lenient().when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list(null, null, null, null, "corr_abc", null, null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_byCorrelation_scopeFilterWithNullScope() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        // scope is null
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list(null, null, null, "org/eng", "corr_abc", null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_scopeFilterWithNullScopeOnEvent_filtersOut() throws Exception {
        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        // scope is null
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list("t1", null, null, "org/eng", null, null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_respectsLimitDuringIteration() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2", "evt_3");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.TENANT_UPDATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e3 = Event.builder().eventId("evt_3").tenantId("t1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        when(jedis.get("event:evt_1")).thenReturn(objectMapper.writeValueAsString(e1));
        when(jedis.get("event:evt_2")).thenReturn(objectMapper.writeValueAsString(e2));
        lenient().when(jedis.get("event:evt_3")).thenReturn(objectMapper.writeValueAsString(e3));

        List<Event> result = repository.list("t1", null, null, null, null, null, null, null, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_parseFailure_skipsGracefully() throws Exception {
        List<String> ids = List.of("evt_bad", "evt_good");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        when(jedis.get("event:evt_bad")).thenReturn("{invalid json}");
        Event good = Event.builder().eventId("evt_good").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        when(jedis.get("event:evt_good")).thenReturn(objectMapper.writeValueAsString(good));

        List<Event> result = repository.list("t1", null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt_good");
    }

    @Test
    void save_withBlankCorrelationId_noCorrelationKey() {
        Event event = Event.builder()
                .tenantId("t1")
                .eventType(EventType.BUDGET_FUNDED)
                .category(EventCategory.BUDGET)
                .source("cycles-admin")
                .correlationId("   ")
                .build();

        repository.save(event);

        verify(jedis).eval(anyString(), argThat((List<String> keys) ->
            keys.size() == 3
        ), anyList());
    }

    @Test
    void save_redisException_throwsRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        Event event = Event.builder()
                .tenantId("t1")
                .eventType(EventType.TENANT_CREATED)
                .category(EventCategory.TENANT)
                .source("cycles-admin")
                .build();

        assertThatThrownBy(() -> repository.save(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to save event");
    }

    @Test
    void findById_redisException_throwsRuntimeException() {
        when(jedis.get("event:evt_err")).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.findById("evt_err"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to find event");
    }

    @Test
    void list_missingEventData_skipsGracefully() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:t1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        when(jedis.get("event:evt_1")).thenReturn(null);
        Event e2 = Event.builder().eventId("evt_2").tenantId("t1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("t1", null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt_2");
    }
}
