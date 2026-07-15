package io.runcycles.admin.data.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyStoreTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    private IdempotencyStore store;

    record Envelope(String action, int total) {}

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
        store = new IdempotencyStore(jedisPool, objectMapper);
    }

    @Test
    void lookup_cacheMiss_returnsEmpty() {
        when(jedis.get("idem:tenants-bulk:k-1")).thenReturn(null);

        Optional<Envelope> result = store.lookup("tenants-bulk", "k-1", Envelope.class);

        assertThat(result).isEmpty();
    }

    @Test
    void lookup_cacheHit_deserialisesEnvelope() throws Exception {
        Envelope stored = new Envelope("SUSPEND", 3);
        String json = objectMapper.writeValueAsString(stored);
        when(jedis.get("idem:tenants-bulk:k-2")).thenReturn(json);

        Optional<Envelope> result = store.lookup("tenants-bulk", "k-2", Envelope.class);

        assertThat(result).contains(stored);
    }

    @Test
    void lookup_corruptJson_returnsEmpty() {
        when(jedis.get("idem:webhooks-bulk:k-3")).thenReturn("{not-json");

        Optional<Envelope> result = store.lookup("webhooks-bulk", "k-3", Envelope.class);

        assertThat(result).isEmpty();
    }

    @Test
    void lookup_deserialiserThrowsJsonProcessingException_returnsEmpty() throws Exception {
        when(jedis.get("idem:tenants-bulk:k-4")).thenReturn("\"raw-string\"");
        // Spy on objectMapper to force a JsonProcessingException specifically
        // (distinct branch from the generic catch).
        doThrow(new JsonProcessingException("boom") {})
            .when(objectMapper).readValue(anyString(), eq(Envelope.class));

        Optional<Envelope> result = store.lookup("tenants-bulk", "k-4", Envelope.class);

        assertThat(result).isEmpty();
    }

    @Test
    void lookup_redisThrows_returnsEmpty() {
        when(jedis.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        Optional<Envelope> result = store.lookup("tenants-bulk", "k-5", Envelope.class);

        assertThat(result).isEmpty();
    }

    @Test
    void store_writesWithFifteenMinuteTtl() throws Exception {
        Envelope envelope = new Envelope("PAUSE", 7);
        String expectedJson = objectMapper.writeValueAsString(envelope);

        store.store("webhooks-bulk", "k-6", envelope);

        // Jedis.setex takes (String, long, String) — match the long overload.
        verify(jedis).setex(eq("idem:webhooks-bulk:k-6"),
            eq((long) IdempotencyStore.TTL_SECONDS), eq(expectedJson));
    }

    @Test
    void store_serializationFailureIsSwallowed() throws Exception {
        Envelope envelope = new Envelope("DELETE", 1);
        doThrow(new JsonProcessingException("nope") {})
            .when(objectMapper).writeValueAsString(any(Envelope.class));

        // Must not throw — store failures degrade silently per javadoc.
        store.store("tenants-bulk", "k-7", envelope);

        verify(jedis, never()).setex(anyString(), anyLong(), anyString());
    }

    @Test
    void store_redisFailureIsSwallowed() {
        doThrow(new RuntimeException("Redis down"))
            .when(jedis).setex(anyString(), anyLong(), anyString());

        // Must not throw.
        store.store("tenants-bulk", "k-8", new Envelope("CLOSE", 2));
    }

    @Test
    void ttlConstant_isNineHundredSeconds() {
        assertThat(IdempotencyStore.TTL_SECONDS).isEqualTo(900);
    }

    @Test
    void begin_atomicallyAcquiresV2Ownership() {
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("ACQUIRED"));

        IdempotencyStore.Claim<Envelope> claim = store.begin(
            "tenants-bulk", "k-atomic", new Envelope("CLOSE", 2), Envelope.class);

        assertThat(claim.isReplay()).isFalse();
        assertThat(claim.ownerToken()).isNotBlank();
        verify(jedis).eval(anyString(), eq(List.of("idem:v2:tenants-bulk:k-atomic")),
            argThat(args -> args.size() == 5
                && args.get(0).matches("[0-9a-f]{64}")
                && "900".equals(args.get(3))));
    }

    @Test
    void begin_completedClaimReturnsImmutableReplay() throws Exception {
        Envelope expected = new Envelope("SUSPEND", 4);
        String replayJson = objectMapper.writeValueAsString(expected);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("COMPLETE", replayJson));

        IdempotencyStore.Claim<Envelope> claim = store.begin(
            "tenants-bulk", "k-replay", new Envelope("SUSPEND", 4), Envelope.class);

        assertThat(claim.isReplay()).isTrue();
        assertThat(claim.replayResponse()).isEqualTo(expected);
        assertThat(claim.ownerToken()).isNull();
    }

    @Test
    void begin_reusedKeyWithDifferentPayloadReturnsMismatch() {
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("MISMATCH"));

        assertThatThrownBy(() -> store.begin(
            "tenants-bulk", "k-mismatch", new Envelope("CLOSE", 1), Envelope.class))
            .isInstanceOf(GovernanceException.class)
            .satisfies(error -> assertThat(((GovernanceException) error).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_MISMATCH));
    }

    @Test
    void begin_requestSerializationFailureFailsBeforeRedis() throws Exception {
        Envelope request = new Envelope("CLOSE", 1);
        doThrow(new JsonProcessingException("cannot serialize") {})
            .when(objectMapper).writeValueAsString(request);

        assertThatThrownBy(() -> store.begin(
            "tenants-bulk", "k-bad-request", request, Envelope.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fingerprint");
        verify(jedis, never()).eval(anyString(), anyList(), anyList());
    }

    @Test
    void begin_unexpectedLuaResultFailsClosed() {
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("UNKNOWN"));

        assertThatThrownBy(() -> store.begin(
            "tenants-bulk", "k-unknown", new Envelope("CLOSE", 1), Envelope.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("claim idempotency key")
            .hasRootCauseMessage("Unexpected idempotency result: [UNKNOWN]");
    }

    @Test
    void complete_requiresCurrentOwnerAndStoresReplayEnvelope() {
        IdempotencyStore.Claim<Envelope> claim = new IdempotencyStore.Claim<>(
            "webhooks-bulk", "k-complete", "owner-1", null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        store.complete(claim, new Envelope("DELETE", 3));

        verify(jedis).eval(anyString(), eq(List.of("idem:v2:webhooks-bulk:k-complete")),
            argThat(args -> args.size() == 3
                && "owner-1".equals(args.get(0))
                && args.get(1).contains("DELETE")
                && "900".equals(args.get(2))));
    }

    @Test
    void complete_lostOwnershipFailsClosed() {
        IdempotencyStore.Claim<Envelope> claim = new IdempotencyStore.Claim<>(
            "webhooks-bulk", "k-lost", "old-owner", null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);

        assertThatThrownBy(() -> store.complete(claim, new Envelope("PAUSE", 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("complete idempotency response");
    }

    @Test
    void abandon_compareDeletesOnlyCurrentInProgressOwner() {
        IdempotencyStore.Claim<Envelope> claim = new IdempotencyStore.Claim<>(
            "budgets-bulk", "k-rejected", "owner-2", null);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        store.abandon(claim);

        verify(jedis).eval(anyString(), eq(List.of("idem:v2:budgets-bulk:k-rejected")),
            eq(List.of("owner-2")));
    }

    @Test
    void abandon_redisFailureIsBestEffort() {
        IdempotencyStore.Claim<Envelope> claim = new IdempotencyStore.Claim<>(
            "budgets-bulk", "k-rejected", "owner-2", null);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenThrow(new RuntimeException("Redis down"));

        store.abandon(claim);
    }

    @Test
    void completedOrAbsentClaimsNeedNoFurtherRedisWork() {
        IdempotencyStore.Claim<Envelope> replay = new IdempotencyStore.Claim<>(
            "budgets-bulk", "k-replay", null, new Envelope("CREDIT", 1));

        store.complete(null, new Envelope("CREDIT", 1));
        store.complete(replay, new Envelope("CREDIT", 1));
        store.abandon(null);
        store.abandon(replay);

        verifyNoInteractions(jedis);
    }

    @Test
    void begin_interruptedWaitFailsPromptlyAndPreservesInterrupt() {
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("IN_PROGRESS"));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> store.begin(
                "tenants-bulk", "k-wait", new Envelope("CLOSE", 1), Envelope.class))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
