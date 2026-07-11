package io.runcycles.admin.api.integration;

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

    // (2) DELIVERABLE matches exist BEYOND the old code's first repository
    //     candidate window (round-4 list() fetched limit*3 = 500*3 = 1500 ZSET
    //     ids per page), and interior hydration drops make that first window
    //     hydrate to FEWER than the 500 page size. The round-4 collector
    //     ("page.size() < 500 → stop") would treat the short first page as
    //     exhaustion and MISS every tail match (deliver 0). Approach B reads the
    //     full ordered id list up front, so hydration drops never look like
    //     exhaustion → all tail matches ship. This is what distinguishes the two.
    @Test
    void replay_matchesBeyondFirstCandidateWindow_withHydrationDrops_stillDelivered() {
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        // 1500 non-matching filler at positions 0..1499 (the old limit*3 window).
        for (int i = 0; i < 1500; i++) {
            seedEvent(String.format("evt_fill_%05d", i), EventType.TENANT_CREATED, EventCategory.TENANT,
                    null, base.plusMillis(i));
        }
        // Evict 1100 interior rows (ZSET members REMAIN) so the old first
        // candidate window hydrates to 400 events (< 500) → round-4 stops here.
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < 1100; i++) {
                jedis.del("event:evt_fill_" + String.format("%05d", i));
            }
        }
        // 30 DELIVERABLE matches AFTER the first candidate window (positions 1500+).
        for (int i = 0; i < 30; i++) {
            seedEvent(String.format("evt_match_%05d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET,
                    null, base.plusMillis(1500 + i));
        }
        seedBudgetSub();

        ReplayResponse r = replay(1000, base.minusSeconds(1), base.plusSeconds(10));

        // Round-4 would deliver 0 (stopped at the short first page); approach B
        // delivers every tail match.
        assertThat(r.getEventsQueued()).isEqualTo(30);
        assertThat(capturedDeliveries(30)).extracting(Event::getEventId)
                .containsExactly(java.util.stream.IntStream.range(0, 30)
                        .mapToObj(i -> "evt_match_" + String.format("%05d", i))
                        .toArray(String[]::new));
    }

    // (3) The old zscore(cursor)==null DUPLICATE class is STRUCTURALLY ELIMINATED
    //     by approach B: the full ordered id list is fetched ONCE up front
    //     (ZRANGEBYSCORE) and hydrated in batches — there is no per-page cursor
    //     re-query, so the "cursor member vanished → re-emit the same page" path
    //     no longer exists. This pins the actual new guarantee: a member whose
    //     event row vanished between the id-fetch and hydration is cleanly
    //     SKIPPED (no duplicate, no miss of later members, no crash), across a
    //     MULTI-BATCH id list (>1 hydration batch of 500).
    @Test
    void replay_vanishedMembers_skippedCleanly_noDuplicate_acrossBatches() {
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 600; i++) { // 600 members → 2 hydration batches (500 + 100)
            seedEvent(String.format("evt_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null,
                    base.plusMillis(i));
        }
        // Rows vanish (ZSET members REMAIN in the fetched id list) in BOTH
        // batches, including at the 500-item batch boundary.
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("event:evt_0003", "event:evt_0499", "event:evt_0500", "event:evt_0550");
        }
        seedBudgetSub();

        ReplayResponse r = replay(1000, base.minusSeconds(1), base.plusSeconds(10));

        assertThat(r.getEventsQueued()).isEqualTo(596); // 600 - 4 vanished, each once
        List<Event> delivered = capturedDeliveries(596);
        assertThat(delivered).extracting(Event::getEventId)
                .doesNotHaveDuplicates()
                .doesNotContain("evt_0003", "evt_0499", "evt_0500", "evt_0550")
                // members AFTER a vanished one (incl. in the 2nd batch) still ship:
                .contains("evt_0551", "evt_0599");
    }

    // (4) scan ceiling hit → WARN logged (no silent truncation).
    // (4a) Window candidate count EXCEEDS the scan ceiling and fewer than
    //      max_events deliverable events were found within the scanned set → the
    //      search is genuinely incomplete → 400 returned BEFORE any enqueue (the
    //      truncation must be caller-visible, not a silent partial + WARN).
    @Test
    void replay_windowExceedsScanCeiling_incomplete_returns400_nothingEnqueued() {
        ReflectionTestUtils.setField(webhookService, "replayMaxScan", 50);
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 60; i++) { // 60 deliverable matches; candidate count 60 > ceiling 50
            seedEvent(String.format("evt_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null,
                    base.plusMillis(i));
        }
        seedBudgetSub();

        // max_events (1000) not filled within the 50 scanned → incomplete → 400.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> replay(1000, base.minusSeconds(1), base.plusSeconds(1)))
                .isInstanceOf(io.runcycles.admin.data.exception.GovernanceException.class)
                .satisfies(ex -> {
                    var ge = (io.runcycles.admin.data.exception.GovernanceException) ex;
                    assertThat(ge.getHttpStatus()).isEqualTo(400);
                    assertThat(ge.getErrorCode())
                            .isEqualTo(io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getMessage()).contains("replay window too large").contains("narrow");
                });

        // NOTHING enqueued — fail-fast before the dispatch loop, no partial side effects.
        verify(dispatchService, org.mockito.Mockito.never()).dispatchToSubscription(any(), any());
        // The replay lock was released, so a subsequent replay is not blocked.
        assertThat(webhookRepository.acquireReplayLock("whsub_1", "probe")).isTrue();
    }

    // (4b) Ceiling reached, but max_events WAS filled within the scanned set →
    //      that is the caller's explicit pagination cap, NOT truncation → success.
    @Test
    void replay_windowExceedsCeiling_butMaxEventsFilledWithin_succeeds() {
        ReflectionTestUtils.setField(webhookService, "replayMaxScan", 50);
        Instant base = Instant.parse("2026-07-11T00:00:00Z");
        for (int i = 0; i < 60; i++) { // candidate count 60 > ceiling 50
            seedEvent(String.format("evt_%04d", i), EventType.BUDGET_CREATED, EventCategory.BUDGET, null,
                    base.plusMillis(i));
        }
        seedBudgetSub();

        ReplayResponse r = replay(10, base.minusSeconds(1), base.plusSeconds(1)); // cap 10 < 50 scanned

        assertThat(r.getEventsQueued()).isEqualTo(10); // filled the cap within the ceiling
        verify(dispatchService, times(10)).dispatchToSubscription(any(), any());
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
