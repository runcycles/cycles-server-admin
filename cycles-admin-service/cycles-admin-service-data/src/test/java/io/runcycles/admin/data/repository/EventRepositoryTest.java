package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
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
                .tenantId("tenant-1")
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
                .tenantId("tenant-1")
                .eventType(EventType.BUDGET_CREATED)
                .category(EventCategory.BUDGET)
                .source("cycles-admin")
                .build();

        repository.save(event);

        verify(jedis).eval(anyString(), argThat((List<String> keys) -> {
            return keys.size() == 3
                && keys.get(0).startsWith("event:evt_")
                && keys.get(1).equals("events:tenant-1")
                && keys.get(2).equals("events:_all");
        }), argThat((List<String> args) -> {
            try {
                // ARGV[0] is JSON, ARGV[1] is score, ARGV[2] is eventId
                Event stored = objectMapper.readValue(args.get(0), Event.class);
                return "tenant-1".equals(stored.getTenantId())
                    && stored.getEventType() == EventType.BUDGET_CREATED;
            } catch (Exception e) {
                return false;
            }
        }));
    }

    @Test
    void save_withCorrelationId_addsCorrelationKey() {
        Event event = Event.builder()
                .tenantId("tenant-1")
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
                .tenantId("tenant-1")
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
                .tenantId("tenant-1")
                .source("cycles-admin")
                .timestamp(Instant.now())
                .build();
        String json = objectMapper.writeValueAsString(event);
        when(jedis.get("event:evt_abc123")).thenReturn(json);

        Event result = repository.findById("evt_abc123");

        assertThat(result.getEventId()).isEqualTo("evt_abc123");
        assertThat(result.getEventType()).isEqualTo(EventType.TENANT_CREATED);
        assertThat(result.getTenantId()).isEqualTo("tenant-1");
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

    // ---- listEventIdsInRange() / hydrateByIds() (replay approach B) ----

    @Test
    void listEventIdsInRange_tenant_usesZrangeByScoreAscending() {
        when(jedis.zrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), eq(500)))
                .thenReturn(List.of("evt_a", "evt_b", "evt_c"));

        List<String> ids = repository.listEventIdsInRange("tenant-1",
                Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000), 500);

        assertThat(ids).containsExactly("evt_a", "evt_b", "evt_c"); // order preserved
        verify(jedis).zrangeByScore(eq("events:tenant-1"), eq(1000.0), eq(2000.0), eq(0), eq(500));
    }

    @Test
    void listEventIdsInRange_nullTenant_usesGlobalIndex_andInfinities() {
        when(jedis.zrangeByScore(eq("events:_all"), eq(Double.NEGATIVE_INFINITY),
                eq(Double.POSITIVE_INFINITY), eq(0), eq(100))).thenReturn(List.of("evt_x"));

        List<String> ids = repository.listEventIdsInRange(null, null, null, 100);

        assertThat(ids).containsExactly("evt_x");
    }

    @Test
    void listEventIdsInRange_nonPositiveMax_returnsEmpty_noRedis() {
        assertThat(repository.listEventIdsInRange("tenant-1", null, null, 0)).isEmpty();
        verify(jedis, never()).zrangeByScore(anyString(), anyDouble(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void hydrateByIds_dropsMissingAndCorrupt_preservesOrderOfRest() throws Exception {
        Event e1 = Event.builder().eventId("evt_1").tenantId("t").eventType(EventType.BUDGET_CREATED)
                .category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        Event e3 = Event.builder().eventId("evt_3").tenantId("t").eventType(EventType.BUDGET_CREATED)
                .category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e3Json = objectMapper.writeValueAsString(e3);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(null);        // expired/missing → dropped
        when(jedis.get("event:evt_3")).thenReturn(e3Json);
        when(jedis.get("event:evt_4")).thenReturn("{not-json"); // corrupt → dropped, not thrown

        List<Event> events = repository.hydrateByIds(List.of("evt_1", "evt_2", "evt_3", "evt_4"));

        assertThat(events).extracting(Event::getEventId).containsExactly("evt_1", "evt_3");
    }

    @Test
    void hydrateByIds_empty_returnsEmpty_noRedis() {
        assertThat(repository.hydrateByIds(List.of())).isEmpty();
        verify(jedis, never()).get(anyString());
    }

    // ---- list() ----

    @Test
    void list_withTenantFilter_usesCorrectIndex() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(2);
        verify(jedis).zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_withoutTenant_usesGlobalIndex() throws Exception {
        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:_all"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list(null, null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("events:_all"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_withCursorPagination_adjustsMaxScore() throws Exception {
        when(jedis.zscore("events:tenant-1", "evt_cursor")).thenReturn(5000.0);

        List<String> ids = List.of("evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), eq(4999.0), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.TENANT_UPDATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, "evt_cursor", 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt_2");
    }

    @Test
    void list_filtersByEventType() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", "tenant.created", null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EventType.TENANT_CREATED);
    }

    @Test
    void list_filtersByCategory() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, "budget", null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo(EventCategory.BUDGET);
    }

    @Test
    void list_filtersByScopePrefix() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/eng/team1").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/sales").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, null, "org/eng", null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScope()).startsWith("org/eng");
    }

    @Test
    void list_emptyResults_returnsEmptyList() {
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(List.of());

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_zeroLimit_returnsEmpty() {
        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 0);
        assertThat(result).isEmpty();
    }

    @Test
    void list_withTimeRange_usesScoreBounds() throws Exception {
        Instant from = Instant.ofEpochMilli(1000);
        Instant to = Instant.ofEpochMilli(2000);

        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), eq(2000.0), eq(1000.0), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, from, to, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("events:tenant-1"), eq(2000.0), eq(1000.0), eq(0), anyInt());
    }

    @Test
    void list_withCursorNotInIndex_ignoresCursor() throws Exception {
        when(jedis.zscore("events:tenant-1", "evt_unknown")).thenReturn(null);

        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), eq(Double.POSITIVE_INFINITY), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, "evt_unknown", 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_cursorWithTimeRange_usesMinOfCursorAndTo() throws Exception {
        Instant to = Instant.ofEpochMilli(8000);
        when(jedis.zscore("events:tenant-1", "evt_cursor")).thenReturn(5000.0);

        List<String> ids = List.of("evt_2");
        // cursorScore - 1 = 4999, to = 8000, min(4999, 8000) = 4999
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), eq(4999.0), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(ids);

        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, to, "evt_cursor", 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_byCorrelation_returnsFilteredEvents() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").scope("org/eng").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-2").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/sales").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, null, null, "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    void list_byCorrelation_filtersByEventType() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
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

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").scope("org/eng").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").scope("org/sales").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list(null, null, "budget", "org/eng", "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo(EventCategory.BUDGET);
    }

    @Test
    void list_byCorrelation_missingData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        when(jedis.get("event:evt_1")).thenReturn(null);
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list(null, null, null, null, "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_byCorrelation_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("evt_1", "evt_2"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
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

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        // scope is null
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list(null, null, null, "org/eng", "corr_abc", null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_scopeFilterWithNullScopeOnEvent_filtersOut() throws Exception {
        List<String> ids = List.of("evt_1");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        // scope is null
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);

        List<Event> result = repository.list("tenant-1", null, null, "org/eng", null, null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_respectsLimitDuringIteration() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2", "evt_3");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.TENANT_UPDATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        Event e3 = Event.builder().eventId("evt_3").tenantId("tenant-1").eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET).source("s").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        String e3Json = objectMapper.writeValueAsString(e3);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);
        lenient().when(jedis.get("event:evt_3")).thenReturn(e3Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_parseFailure_skipsGracefully() throws Exception {
        List<String> ids = List.of("evt_bad", "evt_good");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        Event good = Event.builder().eventId("evt_good").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String goodJson = objectMapper.writeValueAsString(good);
        when(jedis.get("event:evt_bad")).thenReturn("{invalid json}");
        when(jedis.get("event:evt_good")).thenReturn(goodJson);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt_good");
    }

    @Test
    void save_withBlankCorrelationId_noCorrelationKey() {
        Event event = Event.builder()
                .tenantId("tenant-1")
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
                .tenantId("tenant-1")
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
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(ids);

        when(jedis.get("event:evt_1")).thenReturn(null);
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1").eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT).source("s").timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt_2");
    }

    // ---- sort (spec v0.1.25.20 §V4) ----

    private Event ev(String id, String tenantId, EventType type, String scope, Instant ts) {
        return Event.builder()
            .eventId(id).tenantId(tenantId).eventType(type)
            .category(type.getCategory()).source("admin").scope(scope).timestamp(ts)
            .build();
    }

    private void stubZSet(String indexKey, boolean ascending, List<Event> events) throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> jsons = new ArrayList<>();
        for (Event e : events) {
            ids.add(e.getEventId());
            jsons.add(objectMapper.writeValueAsString(e));
        }
        if (ascending) {
            when(jedis.zrangeByScore(eq(indexKey), anyDouble(), anyDouble(), eq(0), anyInt()))
                .thenReturn(ids);
        } else {
            when(jedis.zrevrangeByScore(eq(indexKey), anyDouble(), anyDouble(), eq(0), anyInt()))
                .thenReturn(ids);
        }
        for (int i = 0; i < events.size(); i++) {
            when(jedis.get("event:" + events.get(i).getEventId())).thenReturn(jsons.get(i));
        }
    }

    @Test
    void list_sortByTimestampDesc_usesZrevrangeByScore() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.TENANT_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-1", EventType.TENANT_CREATED, "org", t.plusSeconds(60));
        // Repo returns newest-first when zrevrangeByScore is mocked to descending list.
        stubZSet("events:tenant-1", false, List.of(b, a));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            SortSpec.of("timestamp", SortDirection.DESC));

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_b", "evt_a");
        verify(jedis).zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_sortByTimestampAsc_usesZrangeByScore() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.TENANT_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-1", EventType.TENANT_CREATED, "org", t.plusSeconds(60));
        stubZSet("events:tenant-1", true, List.of(a, b));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            SortSpec.of("timestamp", SortDirection.ASC));

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_a", "evt_b");
        verify(jedis).zrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_sortByEventTypeAsc_hydratesAndReorders() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.TENANT_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-1", EventType.BUDGET_CREATED, "org", t.plusSeconds(60));
        Event c = ev("evt_c", "tenant-1", EventType.API_KEY_CREATED, "org", t.plusSeconds(120));
        // Passed newest-first via zrev; sort re-orders by event_type ascending.
        stubZSet("events:tenant-1", false, List.of(c, b, a));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            SortSpec.of("event_type", SortDirection.ASC));

        // Lex order of wire values: api_key.created < budget.created < tenant.created
        assertThat(result).extracting(Event::getEventId).containsExactly("evt_c", "evt_b", "evt_a");
    }

    @Test
    void list_sortByTenantIdDesc_withGlobalIndex() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-alpha", EventType.TENANT_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-zulu", EventType.TENANT_CREATED, "org", t);
        stubZSet("events:_all", false, List.of(b, a));

        List<Event> result = repository.list(null, null, null, null, null, null, null, null, 50,
            SortSpec.of("tenant_id", SortDirection.DESC));

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_b", "evt_a");
    }

    @Test
    void list_sortedCursorResumesInSortedOrder() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.API_KEY_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-1", EventType.BUDGET_CREATED, "org", t);
        Event c = ev("evt_c", "tenant-1", EventType.TENANT_CREATED, "org", t);
        stubZSet("events:tenant-1", false, List.of(c, b, a));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, "evt_a", 50,
            SortSpec.of("event_type", SortDirection.ASC));

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_b", "evt_c");
    }

    @Test
    void list_sortedRespectsLimit() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.API_KEY_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-1", EventType.BUDGET_CREATED, "org", t);
        Event c = ev("evt_c", "tenant-1", EventType.TENANT_CREATED, "org", t);
        stubZSet("events:tenant-1", false, List.of(c, b, a));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 2,
            SortSpec.of("event_type", SortDirection.ASC));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Event::getEventId).containsExactly("evt_a", "evt_b");
    }

    @Test
    void list_sortByScope_nullScopeSortsLast() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.TENANT_CREATED, null, t);
        Event b = ev("evt_b", "tenant-1", EventType.TENANT_CREATED, "org/eng", t);
        stubZSet("events:tenant-1", false, List.of(b, a));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            SortSpec.of("scope", SortDirection.ASC));

        // Non-null first; nullsLast puts null at tail.
        assertThat(result).extracting(Event::getEventId).containsExactly("evt_b", "evt_a");
    }

    @Test
    void list_sortByCategory_appliesBeforeCursor() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.API_KEY_CREATED, "org", t); // api_key
        Event b = ev("evt_b", "tenant-1", EventType.BUDGET_CREATED, "org", t); // budget
        Event c = ev("evt_c", "tenant-1", EventType.TENANT_CREATED, "org", t); // tenant
        stubZSet("events:tenant-1", false, List.of(c, b, a));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            SortSpec.of("category", SortDirection.DESC));

        // category DESC: tenant > budget > api_key
        assertThat(result).extracting(Event::getEventId).containsExactly("evt_c", "evt_b", "evt_a");
    }

    @Test
    void list_nullSortSpec_preservesLegacyZrevBehaviour() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.TENANT_CREATED, "org", t);
        stubZSet("events:tenant-1", false, List.of(a));

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50, null);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_timestampAscCursorAdvancesMinScore() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.TENANT_CREATED, "org", t);
        stubZSet("events:tenant-1", true, List.of(a));
        when(jedis.zscore("events:tenant-1", "evt_cursor")).thenReturn(1000.0);

        repository.list("tenant-1", null, null, null, null, null, null, "evt_cursor", 50,
            SortSpec.of("timestamp", SortDirection.ASC));

        verify(jedis).zrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_correlationFilter_appliesSortAfterHydration() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.API_KEY_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-1", EventType.TENANT_CREATED, "org", t);
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        LinkedHashSet<String> ids = new LinkedHashSet<>(List.of("evt_a", "evt_b"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);
        when(jedis.get("event:evt_a")).thenReturn(aJson);
        when(jedis.get("event:evt_b")).thenReturn(bJson);

        List<Event> result = repository.list(null, null, null, null, "corr_abc", null, null, null, 50,
            SortSpec.of("event_type", SortDirection.DESC));

        // tenant.created > api_key.created under DESC
        assertThat(result).extracting(Event::getEventId).containsExactly("evt_b", "evt_a");
    }

    // ---- eventComparator direct invocation (coverage of timestamp + default branches) ----

    @Test
    void eventComparator_timestampField_directInvocation() {
        Instant t0 = Instant.parse("2026-04-15T12:00:00Z");
        Event older = ev("evt_older", "tenant-1", EventType.TENANT_CREATED, "org", t0);
        Event newer = ev("evt_newer", "tenant-1", EventType.TENANT_CREATED, "org", t0.plusSeconds(60));

        Comparator<Event> asc = EventRepository.eventComparator(
            SortSpec.of("timestamp", SortDirection.ASC));
        List<Event> ascSorted = new ArrayList<>(List.of(newer, older));
        ascSorted.sort(asc);
        assertThat(ascSorted).extracting(Event::getEventId).containsExactly("evt_older", "evt_newer");

        Comparator<Event> desc = EventRepository.eventComparator(
            SortSpec.of("timestamp", SortDirection.DESC));
        List<Event> descSorted = new ArrayList<>(List.of(older, newer));
        descSorted.sort(desc);
        assertThat(descSorted).extracting(Event::getEventId).containsExactly("evt_newer", "evt_older");
    }

    @Test
    void eventComparator_unknownField_fallsThroughToEventIdTieBreaker() {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event a = ev("evt_a", "tenant-1", EventType.TENANT_CREATED, "org", t);
        Event b = ev("evt_b", "tenant-1", EventType.TENANT_CREATED, "org", t);

        Comparator<Event> asc = EventRepository.eventComparator(
            SortSpec.of("nonexistent_field", SortDirection.ASC));
        List<Event> sorted = new ArrayList<>(List.of(b, a));
        sorted.sort(asc);
        // Default case sorts by event_id ascending (tie-breaker becomes primary).
        assertThat(sorted).extracting(Event::getEventId).containsExactly("evt_a", "evt_b");
    }

    // ---- parseFailure catch-block coverage (listSortedNonTimestamp + listByCorrelation) ----

    @Test
    void list_sortedNonTimestamp_parseFailure_skipsGracefully() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        Event good = ev("evt_good", "tenant-1", EventType.BUDGET_CREATED, "org", t);
        String goodJson = objectMapper.writeValueAsString(good);
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(List.of("evt_bad", "evt_good"));
        when(jedis.get("event:evt_bad")).thenReturn("{invalid json");
        when(jedis.get("event:evt_good")).thenReturn(goodJson);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            SortSpec.of("event_type", SortDirection.ASC));

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_good");
    }

    @Test
    void list_byCorrelation_parseFailure_skipsGracefully() throws Exception {
        Event good = Event.builder().eventId("evt_good").tenantId("tenant-1")
            .eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT)
            .source("s").timestamp(Instant.now()).build();
        String goodJson = objectMapper.writeValueAsString(good);
        LinkedHashSet<String> ids = new LinkedHashSet<>(List.of("evt_bad", "evt_good"));
        when(jedis.smembers("events:correlation:corr_abc")).thenReturn(ids);
        when(jedis.get("event:evt_bad")).thenReturn("{invalid json");
        when(jedis.get("event:evt_good")).thenReturn(goodJson);

        List<Event> result = repository.list(null, null, null, null, "corr_abc", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt_good");
    }

    // ---- spec v0.1.25.21 search filter ----

    /*
     * Watch-item #1 from review-admin-0-1-25-spec-indexed-dewdrop.md:
     * cursor stability under `search` on the time-indexed hydrate path.
     * The two tests below cover the default (no sortSpec) timestamp-DESC
     * path; the non-primary-sort hydrate path shares the same matchesSearch
     * predicate, so coverage there is asserted via the per-controller
     * cursor stability tests.
     */

    @Test
    void list_searchOnCorrelationId_returnsOnlyMatchingEvents() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2", "evt_3");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1")
            .eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT)
            .source("s").correlationId("CorrABC").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1")
            .eventType(EventType.TENANT_UPDATED).category(EventCategory.TENANT)
            .source("s").correlationId("other").timestamp(Instant.now()).build();
        Event e3 = Event.builder().eventId("evt_3").tenantId("tenant-1")
            .eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET)
            .source("s").correlationId("corrXYZ").timestamp(Instant.now()).build();
        // Hoist spy invocations out of the when(...).thenReturn(...)
        // expression — otherwise Mockito's last-invocation tracker sees
        // the spy call mid-stubbing and raises UnfinishedStubbing.
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        String e3Json = objectMapper.writeValueAsString(e3);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);
        when(jedis.get("event:evt_3")).thenReturn(e3Json);

        // Case-insensitive substring on correlation_id — "corr" matches
        // "CorrABC" and "corrXYZ" but not "other".
        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            null, "corr");

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_1", "evt_3");
    }

    @Test
    void list_searchOnScope_caseInsensitive_andOredWithCorrelationId() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1")
            .eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET)
            .source("s").scope("org/ENG").correlationId("unrelated")
            .timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1")
            .eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET)
            .source("s").scope("org/sales").correlationId("unrelated")
            .timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, null, null, null, null, null, null, 50,
            null, "eng");

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_1");
    }

    @Test
    void list_searchAndOtherFilters_combineAsAnd() throws Exception {
        List<String> ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        // Both have matching correlationId; only evt_1 passes category=budget.
        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1")
            .eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET)
            .source("s").correlationId("corr_match").timestamp(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1")
            .eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT)
            .source("s").correlationId("corr_match").timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> result = repository.list("tenant-1", null, "budget", null, null, null, null, null, 50,
            null, "corr");

        assertThat(result).extracting(Event::getEventId).containsExactly("evt_1");
    }

    @Test
    void list_searchCursorStable_secondPageSkipsFirstPageIds() throws Exception {
        // Watch-item #1: cursor stability under search on the
        // zrevrangeByScore walk. First page returns evt_1 + evt_2;
        // second page cursor = evt_2 score must resume strictly after.

        // Page 1: search matches evt_1, evt_2 (limit 2).
        List<String> page1Ids = List.of("evt_1", "evt_2");
        when(jedis.zrevrangeByScore(eq("events:tenant-1"),
            eq(Double.POSITIVE_INFINITY), eq(Double.NEGATIVE_INFINITY),
            eq(0), anyInt())).thenReturn(page1Ids);

        Event e1 = Event.builder().eventId("evt_1").tenantId("tenant-1")
            .eventType(EventType.TENANT_CREATED).category(EventCategory.TENANT)
            .source("s").correlationId("corr_match_1")
            .timestamp(Instant.ofEpochMilli(3000)).build();
        Event e2 = Event.builder().eventId("evt_2").tenantId("tenant-1")
            .eventType(EventType.TENANT_UPDATED).category(EventCategory.TENANT)
            .source("s").correlationId("corr_match_2")
            .timestamp(Instant.ofEpochMilli(2000)).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("event:evt_1")).thenReturn(e1Json);
        when(jedis.get("event:evt_2")).thenReturn(e2Json);

        List<Event> page1 = repository.list("tenant-1", null, null, null, null, null, null, null, 2,
            null, "corr_match");
        assertThat(page1).extracting(Event::getEventId).containsExactly("evt_1", "evt_2");

        // Page 2: cursor = evt_2 (score 2000). Next page upper bound is
        // score - 1 = 1999 so evt_1 (3000) and evt_2 (2000) are both
        // excluded regardless of the search value.
        when(jedis.zscore("events:tenant-1", "evt_2")).thenReturn(2000.0);
        Event e3 = Event.builder().eventId("evt_3").tenantId("tenant-1")
            .eventType(EventType.BUDGET_CREATED).category(EventCategory.BUDGET)
            .source("s").correlationId("corr_match_3")
            .timestamp(Instant.ofEpochMilli(1500)).build();
        String e3Json = objectMapper.writeValueAsString(e3);
        when(jedis.zrevrangeByScore(eq("events:tenant-1"),
            eq(1999.0), eq(Double.NEGATIVE_INFINITY),
            eq(0), anyInt())).thenReturn(List.of("evt_3"));
        when(jedis.get("event:evt_3")).thenReturn(e3Json);

        List<Event> page2 = repository.list("tenant-1", null, null, null, null, null, null, "evt_2", 2,
            null, "corr_match");
        assertThat(page2).extracting(Event::getEventId)
            .containsExactly("evt_3")
            .doesNotContainAnyElementsOf(page1.stream().map(Event::getEventId).toList());
    }
}
