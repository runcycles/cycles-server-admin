package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookSecurityConfigRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @InjectMocks private WebhookSecurityConfigRepository repository;

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

    // ---- get() ----

    @Test
    void get_returnsStoredConfig() throws Exception {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder()
                .blockedCidrRanges(List.of("10.0.0.0/8"))
                .allowHttp(true)
                .build();
        String json = objectMapper.writeValueAsString(config);
        when(jedis.get("config:webhook-security")).thenReturn(json);

        WebhookSecurityConfig result = repository.get();

        assertThat(result.getBlockedCidrRanges()).containsExactly("10.0.0.0/8");
        assertThat(result.getAllowHttp()).isTrue();
    }

    @Test
    void get_returnsDefaultWhenKeyMissing() {
        when(jedis.get("config:webhook-security")).thenReturn(null);

        WebhookSecurityConfig result = repository.get();

        assertThat(result).isNotNull();
        // Default config should be returned from builder defaults
        assertThat(result.getBlockedCidrRanges()).contains("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");
        assertThat(result.getAllowHttp()).isFalse();
    }

    // ---- save() ----

    @Test
    void save_callsSetWithSerializedJson() throws Exception {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder()
                .blockedCidrRanges(List.of("10.0.0.0/8", "172.16.0.0/12"))
                .allowHttp(false)
                .build();

        repository.save(config);

        verify(jedis).set(eq("config:webhook-security"), argThat(json -> json.contains("10.0.0.0/8")));
    }
}
