package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.repository.support.TenantCloseOutboxItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantCloseWorkRepositoryTest {
    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    private ObjectMapper objectMapper;
    private TenantCloseWorkRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        repository = new TenantCloseWorkRepository(jedisPool, objectMapper);
    }

    @Test
    void prepare_atomicallyPreservesIntentAndSchedulesWork() {
        TenantCloseWorkRepository.Intent intent = intent();

        repository.prepare(intent);

        verify(jedis).eval(anyString(),
            eq(List.of("tenant-close:intent:t1", TenantCloseWorkRepository.PENDING_KEY)),
            argThat(args -> args.size() == 3
                && args.get(0).contains("\"requestId\":\"req-1\"")
                && "t1".equals(args.get(2))));
    }

    @Test
    void findIntent_handlesPresentAndMissingRows() throws Exception {
        when(jedis.get("tenant-close:intent:t1"))
            .thenReturn(objectMapper.writeValueAsString(intent()))
            .thenReturn((String) null);

        assertThat(repository.findIntent("t1")).contains(intent());
        assertThat(repository.findIntent("t1")).isEmpty();
    }

    @Test
    void dueTenantIds_clampsNegativeLimit() {
        when(jedis.zrangeByScore(eq(TenantCloseWorkRepository.PENDING_KEY),
                eq(Double.NEGATIVE_INFINITY), anyDouble(), eq(0), eq(0)))
            .thenReturn(List.of());

        assertThat(repository.dueTenantIds(-4)).isEmpty();
    }

    @Test
    void lease_isExclusiveAndOwnerReleasedByCompareDelete() {
        when(jedis.set(eq("tenant-close:lease:t1"), anyString(), any(SetParams.class)))
            .thenReturn("OK")
            .thenReturn((String) null);

        String token = repository.tryAcquireLease("t1");
        assertThat(token).isNotBlank();
        assertThat(repository.tryAcquireLease("t1")).isNull();

        repository.releaseLease("t1", token);
        verify(jedis).eval(anyString(), eq(List.of("tenant-close:lease:t1")),
            eq(List.of(token)));
    }

    @Test
    void releaseLease_nullTokenDoesNothing() {
        repository.releaseLease("t1", null);
        verifyNoInteractions(jedis);
    }

    @Test
    void listOutbox_hydratesEveryIndexedItem() throws Exception {
        TenantCloseOutboxItem item = new TenantCloseOutboxItem(
            "budget:led-1", "t1", "budget", "led-1", null,
            "tenant:t1", "TOKENS", "ACTIVE", 3L);
        when(jedis.smembers("tenant-close:outbox:t1"))
            .thenReturn(Set.of("budget:led-1"));
        when(jedis.get("tenant-close:outbox:item:t1:budget:led-1"))
            .thenReturn(objectMapper.writeValueAsString(item));

        assertThat(repository.listOutbox("t1")).containsExactly(item);
    }

    @Test
    void listOutbox_missingBodyReturnsPoisonMarkerForDeadLetterPolicy() {
        when(jedis.smembers("tenant-close:outbox:t1"))
            .thenReturn(Set.of("api_key:key-1"));

        assertThat(repository.listOutbox("t1")).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo("api_key:key-1");
            assertThat(item.resourceType()).isEqualTo("corrupt");
        });
    }

    @Test
    void acknowledgeAndCompleteUseAtomicScripts() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        repository.acknowledge("t1", "webhook:w1");
        assertThat(repository.completeIfDrained("t1")).isTrue();

        verify(jedis).eval(anyString(),
            eq(List.of("tenant-close:outbox:item:t1:webhook:w1",
                "tenant-close:outbox:t1", "tenant-close:outbox:attempts:t1")),
            eq(List.of("webhook:w1")));
        verify(jedis).eval(anyString(),
            eq(List.of(TenantCloseWorkRepository.PENDING_KEY,
                "tenant-close:intent:t1", "tenant-close:outbox:t1",
                "tenant-close:outbox:dead-letter:t1")),
            eq(List.of("t1")));
    }

    @Test
    void rescheduleParkAndDiscardMaintainDurableQueue() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        repository.reschedule("t1", 0L);
        repository.parkDeadLettered("t1");
        assertThat(repository.discardIfUncommitted("t1")).isTrue();

        verify(jedis).zadd(eq(TenantCloseWorkRepository.PENDING_KEY), anyDouble(), eq("t1"));
        verify(jedis).zrem(TenantCloseWorkRepository.PENDING_KEY, "t1");
        verify(jedis).eval(anyString(), eq(List.of("tenant:t1",
            TenantCloseWorkRepository.PENDING_KEY, "tenant-close:intent:t1",
            "tenant-close:committed:t1")), eq(List.of("t1")));
    }

    @Test
    void leaseRenewalRequiresTheOwnerToken() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L, 0L);

        assertThat(repository.renewLease("t1", "owner")).isTrue();
        assertThat(repository.renewLease("t1", "other")).isFalse();
        assertThat(repository.renewLease("t1", null)).isFalse();

        verify(jedis, times(2)).eval(anyString(), eq(List.of("tenant-close:lease:t1")),
            argThat(args -> args.size() == 2
                && String.valueOf(TenantCloseWorkRepository.LEASE_MILLIS)
                    .equals(args.get(1))));
    }

    @Test
    void outboxFailuresDeadLetterAndCanBeRequeued() {
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of(8L, 1L), 1L);
        when(jedis.scard("tenant-close:outbox:dead-letter:t1")).thenReturn(1L);

        TenantCloseWorkRepository.OutboxFailure failure =
            repository.recordOutboxFailure("t1", "budget:b1");
        assertThat(failure.attempts()).isEqualTo(8);
        assertThat(failure.deadLettered()).isTrue();
        assertThat(repository.deadLetterCount("t1")).isEqualTo(1L);
        assertThat(repository.requeueDeadLetter("t1", "budget:b1")).isTrue();

        verify(jedis).eval(anyString(),
            eq(List.of("tenant-close:outbox:dead-letter:t1",
                "tenant-close:outbox:t1", "tenant-close:outbox:attempts:t1",
                TenantCloseWorkRepository.PENDING_KEY)),
            argThat(args -> args.size() == 3
                && "budget:b1".equals(args.get(0))
                && "t1".equals(args.get(1))));
        verify(jedis, never()).zadd(anyString(), anyDouble(), anyString());
    }

    @Test
    void outboxFailureBelowLimitAndMissingDeadLetterRemainRetryable() {
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of(2L, 0L), 0L);

        TenantCloseWorkRepository.OutboxFailure failure =
            repository.recordOutboxFailure("t1", "budget:b1");

        assertThat(failure.attempts()).isEqualTo(2);
        assertThat(failure.deadLettered()).isFalse();
        assertThat(repository.requeueDeadLetter("t1", "missing")).isFalse();
        verify(jedis, never()).zadd(anyString(), anyDouble(), anyString());
    }

    private static TenantCloseWorkRepository.Intent intent() {
        return new TenantCloseWorkRepository.Intent(
            "t1", "req-1", "0123456789abcdef0123456789abcdef",
            "tenant_close_cascade:t1:req-1", "127.0.0.1", "test",
            Instant.parse("2026-07-15T12:00:00Z"));
    }
}
