package io.runcycles.admin.data.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
}
