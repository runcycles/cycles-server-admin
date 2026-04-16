package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.audit.AuditLogEntry;
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

        List<AuditLogEntry> result = repository.list("tenant-1", null, "create", null, null, null, null, null, null, 50);

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
                .tenantId(AuditLogEntry.UNAUTHENTICATED_TENANT).build();

        // 30 days × 86400s
        assertThat(repository.resolveTtlSeconds(entry)).isEqualTo(30L * 86400L);
    }

    @Test
    void resolveTtlSeconds_zeroDays_meansIndefinite() {
        ReflectionTestUtils.setField(repository, "authenticatedRetentionDays", 0);
        ReflectionTestUtils.setField(repository, "unauthenticatedRetentionDays", 0);

        AuditLogEntry authed = AuditLogEntry.builder().tenantId("tenant-1").build();
        AuditLogEntry unauthed = AuditLogEntry.builder()
                .tenantId(AuditLogEntry.UNAUTHENTICATED_TENANT).build();

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
                .tenantId(AuditLogEntry.UNAUTHENTICATED_TENANT)
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
}
