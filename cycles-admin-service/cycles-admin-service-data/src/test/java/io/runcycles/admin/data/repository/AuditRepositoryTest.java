package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @InjectMocks private AuditRepository repository;

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

    @Test
    void log_setsLogIdAndTimestamp() {
        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId("tenant-1")
                .operation("createTenant")
                .status(201)
                .build();

        repository.log(entry);

        assertThat(entry.getLogId()).startsWith("log_");
        assertThat(entry.getTimestamp()).isNotNull();
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void log_persistsAllFields() throws Exception {
        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId("tenant-1")
                .keyId("key_1")
                .operation("fundBudget")
                .status(200)
                .userAgent("test-agent")
                .sourceIp("127.0.0.1")
                .build();

        repository.log(entry);

        verify(jedis).eval(anyString(), anyList(), argThat(args -> {
            try {
                // ARGV[1] is the JSON payload
                String json = args.get(0);
                AuditLogEntry stored = objectMapper.readValue(json, AuditLogEntry.class);
                return "key_1".equals(stored.getKeyId()) && "test-agent".equals(stored.getUserAgent());
            } catch (Exception e) {
                return false;
            }
        }));
    }

    @Test
    void list_returnsByTenantId() throws Exception {
        List<String> logIds = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").operation("create").status(200).timestamp(Instant.now()).build();
        AuditLogEntry e2 = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1").operation("update").status(200).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);
        when(jedis.get("audit:log:log_2")).thenReturn(e2Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_filtersByKeyId() throws Exception {
        List<String> logIds = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").keyId("key_A").operation("create").status(200).timestamp(Instant.now()).build();
        AuditLogEntry e2 = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1").keyId("key_B").operation("update").status(200).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);
        when(jedis.get("audit:log:log_2")).thenReturn(e2Json);

        List<AuditLogEntry> result = repository.list("tenant-1", "key_A", null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getKeyId()).isEqualTo("key_A");
    }

    @Test
    void list_filtersByOperation() throws Exception {
        List<String> logIds = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").operation("create").status(200).timestamp(Instant.now()).build();
        AuditLogEntry e2 = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1").operation("update").status(200).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);
        when(jedis.get("audit:log:log_2")).thenReturn(e2Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, List.of("create"), null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOperation()).isEqualTo("create");
    }

    @Test
    void list_filtersByStatus() throws Exception {
        List<String> logIds = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").operation("create").status(201).timestamp(Instant.now()).build();
        AuditLogEntry e2 = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1").operation("create").status(400).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);
        when(jedis.get("audit:log:log_2")).thenReturn(e2Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, 201, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(201);
    }

    @Test
    void list_usesGlobalIndexWhenNoTenantId() throws Exception {
        List<String> logIds = List.of("log_1");
        when(jedis.zrevrangeByScore(eq("audit:logs:_all"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").operation("create").status(200).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);

        List<AuditLogEntry> result = repository.list(null, null, null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("audit:logs:_all"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_respectsLimit() throws Exception {
        List<String> logIds = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").operation("create").status(200).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_withZeroLimit_returnsEmpty() {
        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 0);
        assertThat(result).isEmpty();
    }

    @Test
    void list_respectsCursor() throws Exception {
        // The cursor's score is looked up via zscore, then used as maxScore ceiling
        double cursorScore = 1000.0;
        when(jedis.zscore("audit:logs:tenant-1", "log_1")).thenReturn(cursorScore);

        List<String> logIds = List.of("log_2", "log_3");
        // maxScore = cursorScore - 1 = 999.0
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), eq(999.0), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt())).thenReturn(logIds);

        AuditLogEntry e2 = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1").operation("op").status(200).timestamp(Instant.now()).build();
        AuditLogEntry e3 = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1").operation("op").status(200).timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        String e3Json = objectMapper.writeValueAsString(e3);
        when(jedis.get("audit:log:log_2")).thenReturn(e2Json);
        when(jedis.get("audit:log:log_3")).thenReturn(e3Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, "log_1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getLogId()).isEqualTo("log_2");
    }

    @Test
    void list_convenienceOverload_delegatesToFullMethod() {
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
                .thenReturn(List.of());

        List<AuditLogEntry> result = repository.list("tenant-1", 10);

        assertThat(result).isEmpty();
        verify(jedis).zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), eq(30));
    }

    @Test
    void list_missingLogData_skipsGracefully() throws Exception {
        List<String> logIds = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        when(jedis.get("audit:log:log_1")).thenReturn(null);
        AuditLogEntry e2 = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1").operation("op").status(200).timestamp(Instant.now()).build();
        String e2Json = objectMapper.writeValueAsString(e2);
        when(jedis.get("audit:log:log_2")).thenReturn(e2Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLogId()).isEqualTo("log_2");
    }

    @Test
    void log_exceptionIsSwallowed_doesNotThrow() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));

        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId("tenant-1").operation("op").status(200).build();

        // Should not throw - audit failures are non-fatal
        repository.log(entry);
    }

    @Test
    void list_cursorNotFoundInIndex_usesDefaultMaxScore() throws Exception {
        when(jedis.zscore("audit:logs:tenant-1", "nonexistent")).thenReturn(null);

        List<String> logIds = List.of("log_1");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), eq(Double.POSITIVE_INFINITY), eq(Double.NEGATIVE_INFINITY), eq(0), anyInt()))
                .thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").operation("op").status(200).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, "nonexistent", 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_withFromAndTo_usesTimeRangeAsScores() throws Exception {
        Instant from = Instant.ofEpochMilli(1000);
        Instant to = Instant.ofEpochMilli(2000);

        List<String> logIds = List.of("log_1");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), eq(2000.0), eq(1000.0), eq(0), anyInt()))
                .thenReturn(logIds);

        AuditLogEntry e1 = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1").operation("op").status(200).timestamp(Instant.now()).build();
        String e1Json = objectMapper.writeValueAsString(e1);
        when(jedis.get("audit:log:log_1")).thenReturn(e1Json);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, from, to, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("audit:logs:tenant-1"), eq(2000.0), eq(1000.0), eq(0), anyInt());
    }

    @Test
    void list_negativeLimit_returnsEmptyList() {
        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, -1);
        assertThat(result).isEmpty();
    }

    @Test
    void list_deserializationFailure_skipsGracefully() throws Exception {
        List<String> logIds = List.of("log_bad", "log_good");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt())).thenReturn(logIds);

        when(jedis.get("audit:log:log_bad")).thenReturn("{invalid json}");
        AuditLogEntry good = AuditLogEntry.builder().logId("log_good").tenantId("tenant-1").operation("op").status(200).timestamp(Instant.now()).build();
        String goodJson = objectMapper.writeValueAsString(good);
        when(jedis.get("audit:log:log_good")).thenReturn(goodJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLogId()).isEqualTo("log_good");
    }

    // --- v0.1.25.20: tiered TTL + index sweep ---

    @Test
    void resolveTtlSeconds_authenticatedTenant_usesAuthenticatedRetention() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 30);

        AuditLogEntry entry = AuditLogEntry.builder().tenantId("tenant-1").build();

        // 400 days × 86400s
        assertThat(repository.resolveTtlSeconds(entry)).isEqualTo(400L * 86400L);
    }

    @Test
    void resolveTtlSeconds_unauthenticatedSentinel_usesShortRetention() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 30);

        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId(AuditLogEntry.UNAUTH_TENANT).build();

        // 30 days × 86400s
        assertThat(repository.resolveTtlSeconds(entry)).isEqualTo(30L * 86400L);
    }

    @Test
    void resolveTtlSeconds_adminSentinel_usesAuthenticatedRetention() {
        // v0.1.25.28: __admin__ is platform-admin auth, not pre-auth
        // failure — must route to the authenticated tier so admin-plane
        // compliance signals persist at full fidelity.
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 30);

        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId(AuditLogEntry.ADMIN_TENANT).build();

        assertThat(repository.resolveTtlSeconds(entry)).isEqualTo(400L * 86400L);
    }

    @Test
    void resolveTtlSeconds_legacyUnauthenticatedSentinel_usesShortRetention() {
        // v0.1.25.28: historical Redis rows written by v0.1.25.20..v0.1.25.27
        // carry the old "<unauthenticated>" literal. They must still age out
        // on the unauthenticated-tier TTL schedule — no row should silently
        // flip to long retention just because the sentinel label changed.
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 30);

        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId(AuditLogEntry.LEGACY_UNAUTHENTICATED_TENANT).build();

        assertThat(repository.resolveTtlSeconds(entry)).isEqualTo(30L * 86400L);
    }

    @Test
    void resolveTtlSeconds_zeroDays_meansIndefinite() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 0);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 0);

        AuditLogEntry authed = AuditLogEntry.builder().tenantId("tenant-1").build();
        AuditLogEntry unauthed = AuditLogEntry.builder()
                .tenantId(AuditLogEntry.UNAUTH_TENANT).build();

        assertThat(repository.resolveTtlSeconds(authed)).isEqualTo(0L);
        assertThat(repository.resolveTtlSeconds(unauthed)).isEqualTo(0L);
    }

    @Test
    void log_authenticatedEntry_evalReceivesAuthenticatedTtlInArgv() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 30);

        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId("tenant-1").operation("createTenant").status(201).build();

        repository.log(entry);

        // ARGV[4] (index 3 in 0-based args list) must be the authenticated
        // retention in seconds.
        verify(jedis).eval(anyString(), anyList(), argThat(args ->
                String.valueOf(400L * 86400L).equals(args.get(3))));
    }

    @Test
    void log_unauthenticatedSentinelEntry_evalReceivesUnauthenticatedTtlInArgv() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 30);

        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId(AuditLogEntry.UNAUTH_TENANT)
                .operation("POST:/v1/admin/budgets")
                .status(401).errorCode("UNAUTHORIZED").build();

        repository.log(entry);

        verify(jedis).eval(anyString(), anyList(), argThat(args ->
                String.valueOf(30L * 86400L).equals(args.get(3))));
    }

    @Test
    void log_retentionZero_evalReceivesZeroTtl_luaSkipsExpire() {
        // Retention 0 days → 0 seconds → Lua's `if ttl > 0` check short-
        // circuits → plain SET without EX. Contract test locks the
        // "0 == indefinite" semantic.
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 0);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 0);

        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId("tenant-indefinite").operation("op").status(200).build();

        repository.log(entry);

        verify(jedis).eval(anyString(), anyList(), argThat(args ->
                "0".equals(args.get(3))));
    }

    @Test
    void sweepStaleIndexEntries_retentionZero_skipsSweep() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 0);

        repository.sweepStaleIndexEntries();

        // No Redis ops when retention is indefinite — we don't want to
        // accidentally delete index pointers whose target keys are still
        // valid (indefinite TTL means targets never expire).
        verify(jedis, never()).zremrangeByScore(anyString(), anyDouble(), anyDouble());
        verify(jedis, never()).scan(anyString(), any(ScanParams.class));
    }

    @Test
    void sweepStaleIndexEntries_removesGlobalAndPerTenantPointers() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);

        // Global index removes some stale entries.
        when(jedis.zremrangeByScore(eq("audit:logs:_all"), eq(Double.NEGATIVE_INFINITY), anyDouble()))
                .thenReturn(5L);

        // SCAN returns two per-tenant indexes and the global one (which
        // should be skipped since it's already swept).
        @SuppressWarnings("unchecked")
        ScanResult<String> scan1 = mock(ScanResult.class);
        when(scan1.getResult()).thenReturn(List.of(
                "audit:logs:_all", "audit:logs:tenant-a", "audit:logs:tenant-b"));
        when(scan1.getCursor()).thenReturn(ScanParams.SCAN_POINTER_START);
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class))).thenReturn(scan1);

        when(jedis.zremrangeByScore(eq("audit:logs:tenant-a"), anyDouble(), anyDouble()))
                .thenReturn(3L);
        when(jedis.zremrangeByScore(eq("audit:logs:tenant-b"), anyDouble(), anyDouble()))
                .thenReturn(2L);

        repository.sweepStaleIndexEntries();

        verify(jedis).zremrangeByScore(eq("audit:logs:_all"), eq(Double.NEGATIVE_INFINITY), anyDouble());
        verify(jedis).zremrangeByScore(eq("audit:logs:tenant-a"), anyDouble(), anyDouble());
        verify(jedis).zremrangeByScore(eq("audit:logs:tenant-b"), anyDouble(), anyDouble());
        // Must NOT double-sweep the global index via the SCAN loop.
        verify(jedis, times(1)).zremrangeByScore(eq("audit:logs:_all"), anyDouble(), anyDouble());
    }

    @Test
    void sweepStaleIndexEntries_redisFailure_swallowsException() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 400);
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis unavailable"));

        // Must not throw — sweep is best-effort.
        repository.sweepStaleIndexEntries();
    }

    // ---- sort (spec v0.1.25.20 §V4) ----

    private AuditLogEntry log(String id, String tenantId, String keyId, String operation,
                              String resourceType, Integer status, Instant ts) {
        return AuditLogEntry.builder()
            .logId(id).tenantId(tenantId).keyId(keyId).operation(operation)
            .resourceType(resourceType).status(status).timestamp(ts)
            .build();
    }

    private void stubZSet(String indexKey, boolean ascending, List<AuditLogEntry> entries) throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> jsons = new ArrayList<>();
        for (AuditLogEntry e : entries) {
            ids.add(e.getLogId());
            jsons.add(objectMapper.writeValueAsString(e));
        }
        if (ascending) {
            when(jedis.zrangeByScore(eq(indexKey), anyDouble(), anyDouble(), eq(0), anyInt()))
                .thenReturn(ids);
        } else {
            when(jedis.zrevrangeByScore(eq(indexKey), anyDouble(), anyDouble(), eq(0), anyInt()))
                .thenReturn(ids);
        }
        for (int i = 0; i < entries.size(); i++) {
            when(jedis.get("audit:log:" + entries.get(i).getLogId())).thenReturn(jsons.get(i));
        }
    }

    @Test
    void list_sortByTimestampDesc_usesZrevrangeByScore() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "createTenant", "tenant", 201, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "updateTenant", "tenant", 200, t.plusSeconds(60));
        stubZSet("audit:logs:tenant-1", false, List.of(b, a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("timestamp", SortDirection.DESC));

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_b", "log_a");
        verify(jedis).zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_sortByTimestampAsc_usesZrangeByScore() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "createTenant", "tenant", 201, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "updateTenant", "tenant", 200, t.plusSeconds(60));
        stubZSet("audit:logs:tenant-1", true, List.of(a, b));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("timestamp", SortDirection.ASC));

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_a", "log_b");
        verify(jedis).zrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_sortByOperationAsc_hydratesAndReorders() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "createTenant", "tenant", 201, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "fundBudget", "budget", 200, t.plusSeconds(60));
        AuditLogEntry c = log("log_c", "tenant-1", "key_1", "archiveTenant", "tenant", 200, t.plusSeconds(120));
        stubZSet("audit:logs:tenant-1", false, List.of(c, b, a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("operation", SortDirection.ASC));

        // Lex order: archiveTenant < createTenant < fundBudget
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_c", "log_a", "log_b");
    }

    @Test
    void list_sortByTenantIdDesc_withGlobalIndex() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-alpha", "key_1", "op", "tenant", 200, t);
        AuditLogEntry b = log("log_b", "tenant-zulu", "key_1", "op", "tenant", 200, t);
        stubZSet("audit:logs:_all", false, List.of(b, a));

        List<AuditLogEntry> result = repository.list(null, null, null, null, null, null, null, null, null, 50,
            SortSpec.of("tenant_id", SortDirection.DESC));

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_b", "log_a");
    }

    @Test
    void list_sortByStatusAsc_numericOrder() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "op", "tenant", 500, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "op", "tenant", 201, t);
        AuditLogEntry c = log("log_c", "tenant-1", "key_1", "op", "tenant", 404, t);
        stubZSet("audit:logs:tenant-1", false, List.of(a, b, c));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("status", SortDirection.ASC));

        // Numeric order, not lexical: 201 < 404 < 500
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_b", "log_c", "log_a");
    }

    @Test
    void list_sortByKeyIdAsc_nullKeyIdSortsLast() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", null, "op", "tenant", 200, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "op", "tenant", 200, t);
        stubZSet("audit:logs:tenant-1", false, List.of(b, a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("key_id", SortDirection.ASC));

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_b", "log_a");
    }

    @Test
    void list_sortByResourceTypeDesc_tieBreakerByLogId() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "op", "tenant", 200, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "op", "tenant", 200, t);
        AuditLogEntry c = log("log_c", "tenant-1", "key_1", "op", "budget", 200, t);
        stubZSet("audit:logs:tenant-1", false, List.of(c, b, a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("resource_type", SortDirection.DESC));

        // tenant (log_a, log_b) > budget (log_c); DESC tie-break reverses log_id: log_b, log_a, log_c
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_b", "log_a", "log_c");
    }

    @Test
    void list_sortedCursorResumesInSortedOrder() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "archiveTenant", "tenant", 200, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "createTenant", "tenant", 200, t);
        AuditLogEntry c = log("log_c", "tenant-1", "key_1", "updateTenant", "tenant", 200, t);
        stubZSet("audit:logs:tenant-1", false, List.of(c, b, a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, "log_a", 50,
            SortSpec.of("operation", SortDirection.ASC));

        // After log_a (archiveTenant) cursor in ASC sort → log_b (createTenant), log_c (updateTenant)
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_b", "log_c");
    }

    @Test
    void list_sortedRespectsLimit() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "archiveTenant", "tenant", 200, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "createTenant", "tenant", 200, t);
        AuditLogEntry c = log("log_c", "tenant-1", "key_1", "updateTenant", "tenant", 200, t);
        stubZSet("audit:logs:tenant-1", false, List.of(c, b, a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 2,
            SortSpec.of("operation", SortDirection.ASC));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_a", "log_b");
    }

    @Test
    void list_nullSortSpec_preservesLegacyZrevBehaviour() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "op", "tenant", 200, t);
        stubZSet("audit:logs:tenant-1", false, List.of(a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50, null);

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_timestampAscCursorAdvancesMinScore() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "op", "tenant", 200, t);
        stubZSet("audit:logs:tenant-1", true, List.of(a));
        when(jedis.zscore("audit:logs:tenant-1", "log_cursor")).thenReturn(1000.0);

        repository.list("tenant-1", null, null, null, null, null, null, null, "log_cursor", 50,
            SortSpec.of("timestamp", SortDirection.ASC));

        verify(jedis).zrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt());
    }

    @Test
    void list_sortedNonTimestamp_parseFailure_skipsGracefully() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry good = log("log_good", "tenant-1", "key_1", "createTenant", "tenant", 200, t);
        String goodJson = objectMapper.writeValueAsString(good);
        List<String> ids = List.of("log_bad", "log_good");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);
        when(jedis.get("audit:log:log_bad")).thenReturn("{invalid json}");
        when(jedis.get("audit:log:log_good")).thenReturn(goodJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("operation", SortDirection.ASC));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLogId()).isEqualTo("log_good");
    }

    @Test
    void list_sortedNonTimestamp_fromTo_narrowsScoreWindow() throws Exception {
        Instant from = Instant.ofEpochMilli(1000);
        Instant to = Instant.ofEpochMilli(2000);
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "createTenant", "tenant", 200, from);
        String aJson = objectMapper.writeValueAsString(a);
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), eq(2000.0), eq(1000.0), eq(0), anyInt()))
            .thenReturn(List.of("log_a"));
        when(jedis.get("audit:log:log_a")).thenReturn(aJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, from, to, null, 50,
            SortSpec.of("operation", SortDirection.ASC));

        assertThat(result).hasSize(1);
        verify(jedis).zrevrangeByScore(eq("audit:logs:tenant-1"), eq(2000.0), eq(1000.0), eq(0), anyInt());
    }

    @Test
    void list_sortedNonTimestamp_missingData_skipped() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "createTenant", "tenant", 200, t);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(List.of("log_gone", "log_b"));
        when(jedis.get("audit:log:log_gone")).thenReturn(null);
        when(jedis.get("audit:log:log_b")).thenReturn(bJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            SortSpec.of("operation", SortDirection.ASC));

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_b");
    }

    @Test
    void list_sortedNonTimestamp_cursorNotFoundInHydrated_yieldsEmpty() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "op", "tenant", 200, t);
        stubZSet("audit:logs:tenant-1", false, List.of(a));

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null,
            "log_nonexistent", 50, SortSpec.of("operation", SortDirection.ASC));

        // Cursor never encountered → pastCursor stays false → nothing emitted.
        assertThat(result).isEmpty();
    }

    @Test
    void list_filtersByResourceTypeAndResourceId() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_1", "op", "tenant", 200, t);
        a.setResourceId("tenant_A");
        AuditLogEntry b = log("log_b", "tenant-1", "key_1", "op", "budget", 200, t);
        b.setResourceId("budget_1");
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(List.of("log_a", "log_b"));
        when(jedis.get("audit:log:log_a")).thenReturn(aJson);
        when(jedis.get("audit:log:log_b")).thenReturn(bJson);

        List<AuditLogEntry> byType = repository.list("tenant-1", null, null, null, List.of("tenant"), null, null, null, null, 50);
        assertThat(byType).extracting(AuditLogEntry::getLogId).containsExactly("log_a");

        List<AuditLogEntry> byId = repository.list("tenant-1", null, null, null, null, "budget_1", null, null, null, 50);
        assertThat(byId).extracting(AuditLogEntry::getLogId).containsExactly("log_b");
    }

    @Test
    void auditLogComparator_timestampField_directInvocation() {
        // Exercises the timestamp switch case in auditLogComparator. Normal
        // code path routes timestamp through listByTimestamp, so this case
        // is defensive only — unit-test it directly.
        Instant t1 = Instant.parse("2026-04-15T12:00:00Z");
        Instant t2 = Instant.parse("2026-04-15T13:00:00Z");
        AuditLogEntry a = AuditLogEntry.builder().logId("log_a").timestamp(t1).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_b").timestamp(t2).build();

        Comparator<AuditLogEntry> asc = AuditRepository.auditLogComparator(
            SortSpec.of("timestamp", SortDirection.ASC));
        assertThat(asc.compare(a, b)).isNegative();

        Comparator<AuditLogEntry> desc = AuditRepository.auditLogComparator(
            SortSpec.of("timestamp", SortDirection.DESC));
        assertThat(desc.compare(a, b)).isPositive();
    }

    @Test
    void auditLogComparator_unknownField_fallsThroughToLogIdTieBreaker() {
        // Defensive default branch — controller whitelist blocks unknown
        // fields, but comparator must still be total.
        AuditLogEntry a = AuditLogEntry.builder().logId("log_a").build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_b").build();

        Comparator<AuditLogEntry> cmp = AuditRepository.auditLogComparator(
            SortSpec.of("nonexistent_field", SortDirection.ASC));
        assertThat(cmp.compare(a, b)).isNegative();
    }

    @Test
    void list_sortComposedWithFilter() throws Exception {
        Instant t = Instant.parse("2026-04-15T12:00:00Z");
        AuditLogEntry a = log("log_a", "tenant-1", "key_A", "op", "tenant", 200, t);
        AuditLogEntry b = log("log_b", "tenant-1", "key_B", "op", "tenant", 200, t);
        AuditLogEntry c = log("log_c", "tenant-1", "key_A", "op", "budget", 200, t);
        stubZSet("audit:logs:tenant-1", false, List.of(c, b, a));

        // Filter by keyId=key_A → (a, c); sort by resource_type ASC → (budget, tenant)
        List<AuditLogEntry> result = repository.list("tenant-1", "key_A", null, null, null, null, null, null, null, 50,
            SortSpec.of("resource_type", SortDirection.ASC));

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_c", "log_a");
    }

    // ---- search (spec v0.1.25.21) ----
    /*
     * Watch-item #1 from review-admin-0-1-25-spec-indexed-dewdrop.md:
     * cursor stability under `search` on the time-indexed hydrate path.
     * Spec says search matches resource_id OR log_id case-insensitively.
     */

    @Test
    void list_searchOnResourceId_returnsOnlyMatchingEntries() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).resourceId("tenant_ABC").timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(200).resourceId("budget_1").timestamp(Instant.now()).build();
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(200).resourceId("tenant_XYZ").timestamp(Instant.now()).build();
        // Hoist spy invocations out of when(...).thenReturn(...) to avoid
        // Mockito UnfinishedStubbing on @Spy ObjectMapper.
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        // Case-insensitive substring on resource_id — "tenant_" matches
        // "tenant_ABC" and "tenant_XYZ" but not "budget_1".
        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, "tenant_");

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1", "log_3");
    }

    @Test
    void list_searchOnLogId_caseInsensitive_andOredWithResourceId() throws Exception {
        List<String> ids = List.of("LOG_XYZ1", "log_other");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("LOG_XYZ1").tenantId("tenant-1")
            .operation("op").status(200).resourceId("unrelated_A").timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_other").tenantId("tenant-1")
            .operation("op").status(200).resourceId("unrelated_B").timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.get("audit:log:LOG_XYZ1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_other")).thenReturn(bJson);

        // "xyz" case-insensitively matches "LOG_XYZ1" log_id, not resource_id.
        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, "xyz");

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("LOG_XYZ1");
    }

    @Test
    void list_searchAndOtherFilters_combineAsAnd() throws Exception {
        List<String> ids = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        // Both share resource_id prefix; only log_1 passes operation=create.
        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .keyId("key_1").operation("create").status(201)
            .resourceId("match_res").timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .keyId("key_1").operation("update").status(200)
            .resourceId("match_res_other").timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, List.of("create"), null, null, null, null, null, null, 50,
            null, "match");

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1");
    }

    @Test
    void list_searchCursorStable_secondPageSkipsFirstPageIds() throws Exception {
        // Watch-item #1: cursor stability under search on the
        // zrevrangeByScore walk. First page returns log_1 + log_2;
        // second page cursor = log_2 score must resume strictly after.

        List<String> page1Ids = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"),
            eq(Double.POSITIVE_INFINITY), eq(Double.NEGATIVE_INFINITY),
            eq(0), anyInt())).thenReturn(page1Ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).resourceId("match_res_1")
            .timestamp(Instant.ofEpochMilli(3000)).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(200).resourceId("match_res_2")
            .timestamp(Instant.ofEpochMilli(2000)).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);

        List<AuditLogEntry> page1 = repository.list("tenant-1", null, null, null, null, null, null, null, null, 2,
            null, "match");
        assertThat(page1).extracting(AuditLogEntry::getLogId).containsExactly("log_1", "log_2");

        // Page 2: cursor = log_2 (score 2000). Next page upper bound is
        // score - 1 = 1999 so log_1 (3000) and log_2 (2000) are excluded
        // regardless of search value.
        when(jedis.zscore("audit:logs:tenant-1", "log_2")).thenReturn(2000.0);
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(200).resourceId("match_res_3")
            .timestamp(Instant.ofEpochMilli(1500)).build();
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"),
            eq(1999.0), eq(Double.NEGATIVE_INFINITY),
            eq(0), anyInt())).thenReturn(List.of("log_3"));
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        List<AuditLogEntry> page2 = repository.list("tenant-1", null, null, null, null, null, null, null, "log_2", 2,
            null, "match");
        assertThat(page2).extracting(AuditLogEntry::getLogId)
            .containsExactly("log_3")
            .doesNotContainAnyElementsOf(page1.stream().map(AuditLogEntry::getLogId).toList());
    }

    // ---- v0.1.25.27 audit filter DSL upgrade (spec v0.1.25.24) ----

    @Test
    void list_operationInList_matchesAnyValue() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("createBudget").status(201).timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("updateBudget").status(200).timestamp(Instant.now()).build();
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("archiveTenant").status(200).timestamp(Instant.now()).build();
        // @Spy ObjectMapper: hoist writeValueAsString out of when(...).thenReturn(...).
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null,
            List.of("createBudget", "updateBudget"), null, null, null, null, null, null, 50,
            null, null, null, null, null, null);

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1", "log_2");
    }

    @Test
    void list_resourceTypeInList_matchesAnyValue() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).resourceType("budget").timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(200).resourceType("tenant").timestamp(Instant.now()).build();
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(200).resourceType("webhook").timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null,
            List.of("budget", "tenant"), null, null, null, null, 50,
            null, null, null, null, null, null);

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1", "log_2");
    }

    @Test
    void list_errorCodeInList_nullErrorCodeNeverMatches() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry success = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).timestamp(Instant.now()).build();
        AuditLogEntry quotaFail = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(429).errorCode("BUDGET_EXCEEDED").timestamp(Instant.now()).build();
        AuditLogEntry authFail = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(401).errorCode("UNAUTHORIZED").timestamp(Instant.now()).build();
        String successJson = objectMapper.writeValueAsString(success);
        String quotaFailJson = objectMapper.writeValueAsString(quotaFail);
        String authFailJson = objectMapper.writeValueAsString(authFail);
        when(jedis.get("audit:log:log_1")).thenReturn(successJson);
        when(jedis.get("audit:log:log_2")).thenReturn(quotaFailJson);
        when(jedis.get("audit:log:log_3")).thenReturn(authFailJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null, List.of("BUDGET_EXCEEDED", "TENANT_SUSPENDED"), null, null, null);

        // Success (null error_code) excluded; only the BUDGET_EXCEEDED entry matches.
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_2");
    }

    @Test
    void list_errorCodeExclude_nullErrorCodeAlwaysPasses() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry success = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).timestamp(Instant.now()).build();
        AuditLogEntry noisy = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(500).errorCode("INTERNAL_ERROR").timestamp(Instant.now()).build();
        AuditLogEntry interesting = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(429).errorCode("BUDGET_EXCEEDED").timestamp(Instant.now()).build();
        String successJson = objectMapper.writeValueAsString(success);
        String noisyJson = objectMapper.writeValueAsString(noisy);
        String interestingJson = objectMapper.writeValueAsString(interesting);
        when(jedis.get("audit:log:log_1")).thenReturn(successJson);
        when(jedis.get("audit:log:log_2")).thenReturn(noisyJson);
        when(jedis.get("audit:log:log_3")).thenReturn(interestingJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null, null, List.of("INTERNAL_ERROR"), null, null);

        // Noisy INTERNAL_ERROR hidden; success (null) and BUDGET_EXCEEDED pass.
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1", "log_3");
    }

    @Test
    void list_errorCodeAndExcludeCombined_andComposed() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(429).errorCode("BUDGET_EXCEEDED").timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(403).errorCode("TENANT_SUSPENDED").timestamp(Instant.now()).build();
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(401).errorCode("UNAUTHORIZED").timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        // Narrow to [BUDGET_EXCEEDED, TENANT_SUSPENDED], then exclude TENANT_SUSPENDED.
        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null,
            List.of("BUDGET_EXCEEDED", "TENANT_SUSPENDED"),
            List.of("TENANT_SUSPENDED"),
            null, null);

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1");
    }

    @Test
    void list_statusRange_inclusiveBoundaries() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3", "log_4", "log_5");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(399).timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(400).timestamp(Instant.now()).build();
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(450).timestamp(Instant.now()).build();
        AuditLogEntry d = AuditLogEntry.builder().logId("log_4").tenantId("tenant-1")
            .operation("op").status(499).timestamp(Instant.now()).build();
        AuditLogEntry e = AuditLogEntry.builder().logId("log_5").tenantId("tenant-1")
            .operation("op").status(500).timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        String cJson = objectMapper.writeValueAsString(c);
        String dJson = objectMapper.writeValueAsString(d);
        String eJson = objectMapper.writeValueAsString(e);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);
        when(jedis.get("audit:log:log_4")).thenReturn(dJson);
        when(jedis.get("audit:log:log_5")).thenReturn(eJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null, null, null, 400, 499);

        // Inclusive on both ends: 400, 450, 499; 399 and 500 excluded.
        assertThat(result).extracting(AuditLogEntry::getLogId)
            .containsExactly("log_2", "log_3", "log_4");
    }

    @Test
    void list_statusMinOnly_treatsAsLowerBound() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(500).timestamp(Instant.now()).build();
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(599).timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null, null, null, 500, null);

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_2", "log_3");
    }

    @Test
    void list_statusMaxOnly_treatsAsUpperBound() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(299).timestamp(Instant.now()).build();
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("op").status(300).timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null, null, null, null, 299);

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1", "log_2");
    }

    @Test
    void list_statusRange_nullEntryStatus_isExcluded() throws Exception {
        // Entries with null status cannot satisfy either bound — must not
        // silently pass the range predicate.
        List<String> ids = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry nullStatus = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").timestamp(Instant.now()).build();
        AuditLogEntry inRange = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(450).timestamp(Instant.now()).build();
        String nullStatusJson = objectMapper.writeValueAsString(nullStatus);
        String inRangeJson = objectMapper.writeValueAsString(inRange);
        when(jedis.get("audit:log:log_1")).thenReturn(nullStatusJson);
        when(jedis.get("audit:log:log_2")).thenReturn(inRangeJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null, null, null, 400, 499);

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_2");
    }

    @Test
    void list_searchMatchesErrorCodeSubstring() throws Exception {
        // Spec v0.1.25.24: search extended to include error_code. "quota"
        // case-insensitively would miss BUDGET_EXCEEDED but "budget" matches.
        List<String> ids = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(429).errorCode("BUDGET_EXCEEDED")
            .resourceId("unrelated_A").timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("op").status(401).errorCode("UNAUTHORIZED")
            .resourceId("unrelated_B").timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, "budget");

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1");
    }

    @Test
    void list_searchMatchesOperationSubstring() throws Exception {
        // Spec v0.1.25.24: search extended to include operation.
        List<String> ids = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("createBudget").status(201)
            .resourceId("unrelated_A").timestamp(Instant.now()).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("archiveTenant").status(200)
            .resourceId("unrelated_B").timestamp(Instant.now()).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, "BUDGET");

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1");
    }

    @Test
    void list_allNewFiltersCombined_andComposed() throws Exception {
        List<String> ids = List.of("log_1", "log_2", "log_3", "log_4");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        // Matches all predicates: operation=createBudget, resource_type=budget,
        // error_code=BUDGET_EXCEEDED (not in exclude list), status in [400, 499].
        AuditLogEntry match = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("createBudget").status(429).resourceType("budget")
            .errorCode("BUDGET_EXCEEDED").timestamp(Instant.now()).build();
        // Fails operation filter.
        AuditLogEntry wrongOp = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("archiveTenant").status(429).resourceType("budget")
            .errorCode("BUDGET_EXCEEDED").timestamp(Instant.now()).build();
        // Fails status range.
        AuditLogEntry wrongStatus = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("createBudget").status(500).resourceType("budget")
            .errorCode("BUDGET_EXCEEDED").timestamp(Instant.now()).build();
        // Fails error_code exclude.
        AuditLogEntry excluded = AuditLogEntry.builder().logId("log_4").tenantId("tenant-1")
            .operation("createBudget").status(429).resourceType("budget")
            .errorCode("INTERNAL_ERROR").timestamp(Instant.now()).build();
        String matchJson = objectMapper.writeValueAsString(match);
        String wrongOpJson = objectMapper.writeValueAsString(wrongOp);
        String wrongStatusJson = objectMapper.writeValueAsString(wrongStatus);
        String excludedJson = objectMapper.writeValueAsString(excluded);
        when(jedis.get("audit:log:log_1")).thenReturn(matchJson);
        when(jedis.get("audit:log:log_2")).thenReturn(wrongOpJson);
        when(jedis.get("audit:log:log_3")).thenReturn(wrongStatusJson);
        when(jedis.get("audit:log:log_4")).thenReturn(excludedJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null,
            List.of("createBudget", "updateBudget"), null,
            List.of("budget"), null, null, null, null, 50,
            null, null,
            List.of("BUDGET_EXCEEDED", "INTERNAL_ERROR"),
            List.of("INTERNAL_ERROR"),
            400, 499);

        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1");
    }

    @Test
    void list_newFilterCursorStable_secondPageSkipsFirstPage() throws Exception {
        // Cursor-stability invariant (v0.1.25.25): predicates applied BEFORE
        // cursor commit. Paging through a filtered result set must not
        // repeat page-1 ids on page-2.
        List<String> page1Ids = List.of("log_1", "log_2");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"),
            eq(Double.POSITIVE_INFINITY), eq(Double.NEGATIVE_INFINITY),
            eq(0), anyInt())).thenReturn(page1Ids);

        AuditLogEntry a = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("createBudget").status(429).errorCode("BUDGET_EXCEEDED")
            .timestamp(Instant.ofEpochMilli(3000)).build();
        AuditLogEntry b = AuditLogEntry.builder().logId("log_2").tenantId("tenant-1")
            .operation("createBudget").status(429).errorCode("BUDGET_EXCEEDED")
            .timestamp(Instant.ofEpochMilli(2000)).build();
        String aJson = objectMapper.writeValueAsString(a);
        String bJson = objectMapper.writeValueAsString(b);
        when(jedis.get("audit:log:log_1")).thenReturn(aJson);
        when(jedis.get("audit:log:log_2")).thenReturn(bJson);

        List<AuditLogEntry> page1 = repository.list("tenant-1", null,
            List.of("createBudget"), null, null, null, null, null, null, 2,
            null, null, List.of("BUDGET_EXCEEDED"), null, 400, 499);
        assertThat(page1).extracting(AuditLogEntry::getLogId).containsExactly("log_1", "log_2");

        // Page 2: cursor score = 2000 → upper bound = 1999, log_3 alone.
        when(jedis.zscore("audit:logs:tenant-1", "log_2")).thenReturn(2000.0);
        AuditLogEntry c = AuditLogEntry.builder().logId("log_3").tenantId("tenant-1")
            .operation("createBudget").status(429).errorCode("BUDGET_EXCEEDED")
            .timestamp(Instant.ofEpochMilli(1500)).build();
        String cJson = objectMapper.writeValueAsString(c);
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"),
            eq(1999.0), eq(Double.NEGATIVE_INFINITY),
            eq(0), anyInt())).thenReturn(List.of("log_3"));
        when(jedis.get("audit:log:log_3")).thenReturn(cJson);

        List<AuditLogEntry> page2 = repository.list("tenant-1", null,
            List.of("createBudget"), null, null, null, null, null, "log_2", 2,
            null, null, List.of("BUDGET_EXCEEDED"), null, 400, 499);

        assertThat(page2).extracting(AuditLogEntry::getLogId)
            .containsExactly("log_3")
            .doesNotContainAnyElementsOf(page1.stream().map(AuditLogEntry::getLogId).toList());
    }

    @Test
    void list_emptyErrorCodeList_treatedAsNoFilter() throws Exception {
        // Empty list should behave identically to null — `!isEmpty()` guard
        // is exactly this branch. Controller sends `null` for empty input,
        // but the repository must still behave correctly if an empty list
        // slips through.
        List<String> ids = List.of("log_1");
        when(jedis.zrevrangeByScore(eq("audit:logs:tenant-1"), anyDouble(), anyDouble(), eq(0), anyInt()))
            .thenReturn(ids);

        AuditLogEntry success = AuditLogEntry.builder().logId("log_1").tenantId("tenant-1")
            .operation("op").status(200).timestamp(Instant.now()).build();
        String successJson = objectMapper.writeValueAsString(success);
        when(jedis.get("audit:log:log_1")).thenReturn(successJson);

        List<AuditLogEntry> result = repository.list("tenant-1", null, null, null, null, null, null, null, null, 50,
            null, null, List.of(), List.of(), null, null);

        // Empty IN-list must not exclude the success entry — otherwise the
        // controller's empty-normalisation would be load-bearing.
        assertThat(result).extracting(AuditLogEntry::getLogId).containsExactly("log_1");
    }
}
