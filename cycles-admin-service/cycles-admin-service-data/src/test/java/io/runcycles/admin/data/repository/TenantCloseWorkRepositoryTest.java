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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
            .thenReturn(objectMapper.writeValueAsString(intent()), null);

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
            .thenReturn("OK", null);

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
    void listOutbox_missingBodyFailsVisiblyInsteadOfStickingSilently() {
        when(jedis.smembers("tenant-close:outbox:t1"))
            .thenReturn(Set.of("api_key:key-1"));

        assertThatThrownBy(() -> repository.listOutbox("t1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Failed to read tenant-close outbox")
            .hasRootCauseMessage(
                "Tenant-close outbox index points to a missing item: api_key:key-1");
    }

    @Test
    void acknowledgeAndCompleteUseAtomicScripts() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        repository.acknowledge("t1", "webhook:w1");
        assertThat(repository.completeIfDrained("t1")).isTrue();

        verify(jedis).eval(anyString(),
            eq(List.of("tenant-close:outbox:item:t1:webhook:w1",
                "tenant-close:outbox:t1")), eq(List.of("webhook:w1")));
        verify(jedis).eval(anyString(),
            eq(List.of(TenantCloseWorkRepository.PENDING_KEY,
                "tenant-close:intent:t1", "tenant-close:outbox:t1")),
            eq(List.of("t1")));
    }

    @Test
    void rescheduleAndDiscardMaintainDurableQueue() {
        repository.reschedule("t1", 0L);
        repository.discard("t1");

        verify(jedis).zadd(eq(TenantCloseWorkRepository.PENDING_KEY), anyDouble(), eq("t1"));
        verify(jedis).zrem(TenantCloseWorkRepository.PENDING_KEY, "t1");
        verify(jedis).del("tenant-close:intent:t1");
    }

    private static TenantCloseWorkRepository.Intent intent() {
        return new TenantCloseWorkRepository.Intent(
            "t1", "req-1", "0123456789abcdef0123456789abcdef",
            "tenant_close_cascade:t1:req-1", "127.0.0.1", "test",
            Instant.parse("2026-07-15T12:00:00Z"));
    }
}
