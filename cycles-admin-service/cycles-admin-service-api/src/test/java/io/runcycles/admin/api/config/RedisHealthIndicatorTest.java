package io.runcycles.admin.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisHealthIndicatorTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;

    @Test
    void health_pingPong_reportsUp() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.ping()).thenReturn("PONG");

        RedisHealthIndicator indicator = new RedisHealthIndicator(jedisPool);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void health_pingUnexpected_reportsDown() {
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.ping()).thenReturn("NOPE");

        RedisHealthIndicator indicator = new RedisHealthIndicator(jedisPool);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void health_redisThrows_reportsDown() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("redis unavailable"));

        RedisHealthIndicator indicator = new RedisHealthIndicator(jedisPool);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
