package io.runcycles.admin.api.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.api.service.WebhookDispatchService;
import io.runcycles.admin.api.service.WebhookService;
import io.runcycles.admin.data.repository.EventRepository;
import io.runcycles.admin.data.repository.WebhookDeliveryRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.data.service.CryptoService;
import io.runcycles.admin.model.event.Event;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.ReplayRequest;
import io.runcycles.admin.model.webhook.ReplayResponse;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #209 round-3 P2 — REAL-Redis / real {@link EventRepository} replay pagination
 * tests. These exercise the ZRANGEBYSCORE contract that the round-3 cursor
 * rewrite's three bugs lived in (equal-timestamp skip, hydration-thinned page
 * misread as exhaustion, vanished-cursor duplicate) — mocked pages cannot.
 * Approach B: {@link WebhookService#replay} pulls one bounded ordered id list
 * ({@link EventRepository#listEventIdsInRange}) then hydrates+filters batches.
 * {@link WebhookDispatchService} is mocked (delivery is not where the paging
 * bugs are); its {@code dispatchToSubscription} returns true so
 * {@code events_queued} counts deliverable events and we capture delivery order.
 */
class WebhookReplayPaginationIntegrationTest {

    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        redis.start();
    }

    private static JedisPool jedisPool;
    private static ObjectMapper objectMapper;
    private static EventRepository eventRepository;
    private static WebhookRepository webhookRepository;

    private WebhookDispatchService dispatchService;
    private WebhookService webhookService;

    @BeforeAll
    static void setupAll() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        eventRepository = new EventRepository();
        ReflectionTestUtils.setField(eventRepository, "jedisPool", jedisPool);
        ReflectionTestUtils.setField(eventRepository, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(eventRepository, "eventTtlDays", 90);

        webhookRepository = new WebhookRepository();
        ReflectionTestUtils.setField(webhookRepository, "jedisPool", jedisPool);
        ReflectionTestUtils.setField(webhookRepository, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(webhookRepository, "cryptoService", new CryptoService(""));
    }

    @AfterAll
    static void tearDownAll() {
        if (jedisPool != null) jedisPool.close();
    }

    @BeforeEach
    void setup() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
        }
        dispatchService = mock(WebhookDispatchService.class);
        when(dispatchService.dispatchToSubscription(any(), any())).thenReturn(true);
        // boundary default: not blocked (false) — Mockito default for boolean.
        webhookService = new WebhookService(
                webhookRepository, mock(WebhookDeliveryRepository.class),
                mock(WebhookSecurityConfigRepository.class), mock(io.runcycles.admin.api.service.WebhookUrlValidator.class),
                dispatchService, eventRepository, objectMapper);
        ReflectionTestUtils.setField(webhookService, "replayMaxScan", 20_000);
    }

    // ---- helpers ----

    private void seedEvent(String id, EventType type, EventCategory cat, String scope, Instant ts) {
        Event e = Event.builder().eventId(id).eventType(type).category(cat)
                .tenantId("tenant-1").source("admin").scope(scope).timestamp(ts).build();
        eventRepository.save(e);
    }

    private WebhookSubscription seedBudgetSub() {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1")
                .url("https://example.com/webhook")
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .status(WebhookStatus.ACTIVE).consecutiveFailures(0)
                .createdAt(Instant.now()).build();
        webhookRepository.save(sub);
        return sub;
    }

    private ReplayResponse replay(int maxEvents, Instant from, Instant to) {
        return webhookService.replay("whsub_1",
                ReplayRequest.builder().from(from).to(to).maxEvents(maxEvents).build());
    }

    private List<Event> capturedDeliveries(int count) {
        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(dispatchService, times(count)).dispatchToSubscription(cap.capture(), any());
        return cap.getAllValues();
    }

    // (1) >500 events sharing the SAME millisecond, matches only in the tail →
    //     ALL delivered (the old cursor advanced by score+1 and skipped them).
    @Test
    void replay_equalTimestampWindow_tailMatches_allDelivered_noneSkipped() {
        Instant ts = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 550; i++) { // non-matching, sort BEFORE matches
            seedEvent(String.format("evt_a_%04d", i), EventType.TENANT_CREATED, EventCategory.TENANT, null, ts);
        }
        for (int i = 0; i < 60; i++) {  // matching, sort AFTER (tail past position 500)
            seedEvent(String.format("evt_z_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null, ts);
        }
        seedBudgetSub();

        ReplayResponse r = replay(1000, ts.minusSeconds(1), ts.plusSeconds(1));

        assertThat(r.getEventsQueued()).isEqualTo(60); // all tail matches delivered
        assertThat(capturedDeliveries(60)).allMatch(e -> e.getEventType() == EventType.BUDGET_CREATED);
    }

    // (2) hydration drops interior rows (expired/evicted) but later ZSET members
    //     match → NOT treated as exhaustion; later matches still delivered.
    @Test
    void replay_interiorHydrationDrops_laterMatchesStillDelivered() {
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            seedEvent(String.format("evt_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null,
                    base.plusMillis(i));
        }
        // Evict three INTERIOR event rows (keep their ZSET members).
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("event:evt_0003", "event:evt_0004", "event:evt_0005");
        }
        seedBudgetSub();

        ReplayResponse r = replay(1000, base.minusSeconds(1), base.plusSeconds(1));

        assertThat(r.getEventsQueued()).isEqualTo(7); // 10 members - 3 evicted
        assertThat(capturedDeliveries(7)).extracting(Event::getEventId)
                .containsExactly("evt_0000", "evt_0001", "evt_0002",
                        "evt_0006", "evt_0007", "evt_0008", "evt_0009"); // later matches present
    }

    // (3) a vanished member (its row evicted between ZRANGE and hydration) does
    //     NOT cause a duplicate — each event is delivered at most once.
    @Test
    void replay_vanishedMember_noDuplicateDelivery() {
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            seedEvent("evt_" + i, EventType.BUDGET_CREATED, EventCategory.BUDGET, null, base.plusMillis(i));
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("event:evt_2"); // middle member's row gone
        }
        seedBudgetSub();

        ReplayResponse r = replay(1000, base.minusSeconds(1), base.plusSeconds(1));

        assertThat(r.getEventsQueued()).isEqualTo(4);
        List<Event> delivered = capturedDeliveries(4);
        assertThat(delivered).extracting(Event::getEventId)
                .containsExactly("evt_0", "evt_1", "evt_3", "evt_4") // no evt_2, no repeats
                .doesNotHaveDuplicates();
    }

    // (4) scan ceiling hit → WARN logged (no silent truncation).
    @Test
    void replay_scanCeiling_logsWarning_noSilentTruncation() {
        ReflectionTestUtils.setField(webhookService, "replayMaxScan", 50);
        Instant ts = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 60; i++) { // 60 matching, but the id list is capped at 50
            seedEvent(String.format("evt_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null,
                    ts.plusMillis(i));
        }
        seedBudgetSub();

        Logger logger = (Logger) LoggerFactory.getLogger(WebhookService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReplayResponse r = replay(1000, ts.minusSeconds(1), ts.plusSeconds(1));
            assertThat(r.getEventsQueued()).isEqualTo(50); // capped by the scan ceiling
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("scan ceiling"));
    }

    // (5) chronological delivery order preserved ACROSS page boundaries (>500).
    @Test
    void replay_chronologicalOrder_preservedAcrossPageBoundaries() {
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 600; i++) { // strictly increasing timestamps
            seedEvent(String.format("evt_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null,
                    base.plusMillis(i));
        }
        seedBudgetSub();

        ReplayResponse r = replay(1000, base.minusSeconds(1), base.plusSeconds(10));

        assertThat(r.getEventsQueued()).isEqualTo(600);
        List<Event> delivered = capturedDeliveries(600);
        for (int i = 1; i < delivered.size(); i++) {
            assertThat(delivered.get(i).getTimestamp())
                    .isAfterOrEqualTo(delivered.get(i - 1).getTimestamp()); // ascending, no reorder
        }
        assertThat(delivered.get(0).getEventId()).isEqualTo("evt_0000");
        assertThat(delivered.get(599).getEventId()).isEqualTo("evt_0599");
    }

    // (6) max_events counts DELIVERABLE events (after all filters), and they are
    //     the earliest matching ones in chronological order.
    @Test
    void replay_maxEvents_countsDeliverableAfterFilters() {
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 20; i++) {
            // interleave one matching + one non-matching at increasing timestamps
            seedEvent(String.format("evt_ok_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null,
                    base.plusMillis(2 * i));
            seedEvent(String.format("evt_no_%04d", i), EventType.TENANT_CREATED, EventCategory.TENANT, null,
                    base.plusMillis(2 * i + 1));
        }
        seedBudgetSub();

        ReplayResponse r = replay(5, base.minusSeconds(1), base.plusSeconds(1));

        assertThat(r.getEventsQueued()).isEqualTo(5); // 5 DELIVERABLE, not 5 scanned
        List<Event> delivered = capturedDeliveries(5);
        assertThat(delivered).allMatch(e -> e.getEventType() == EventType.BUDGET_CREATED);
        assertThat(delivered).extracting(Event::getEventId)
                .containsExactly("evt_ok_0000", "evt_ok_0001", "evt_ok_0002",
                        "evt_ok_0003", "evt_ok_0004"); // earliest 5 matches, chronological
    }
}
