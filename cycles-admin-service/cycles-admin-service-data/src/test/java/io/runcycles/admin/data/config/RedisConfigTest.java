package io.runcycles.admin.data.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.*;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RedisConfig")
class RedisConfigTest {

    private RedisConfig redisConfig;

    @BeforeEach
    void setUp() throws Exception {
        redisConfig = new RedisConfig();
        setField("host", "localhost");
        setField("port", 6379);
        setField("password", "");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RedisConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(redisConfig, value);
    }

    @Test
    @DisplayName("objectMapper() registers JavaTimeModule and disables WRITE_DATES_AS_TIMESTAMPS")
    void objectMapper_configuredCorrectly() {
        ObjectMapper mapper = redisConfig.objectMapper();

        assertThat(mapper).isNotNull();
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    @Test
    @DisplayName("redisObjectMapper() tolerates fields from newer rolling-deployment peers")
    void redisObjectMapper_isTolerantWithoutWeakeningPrimaryMapper() throws Exception {
        ObjectMapper primary = redisConfig.objectMapper();
        ObjectMapper redis = redisConfig.redisObjectMapper();
        String json = "{\"name\":\"row\",\"future_field\":true}";

        assertThatThrownBy(() -> primary.readValue(json, MapperFixture.class))
            .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
        assertThat(redis.readValue(json, MapperFixture.class).name).isEqualTo("row");
    }

    @Test
    @DisplayName("jedisPool() creates pool with empty password (no-auth)")
    void jedisPool_emptyPassword_createsPoolWithoutAuth() {
        JedisPool pool = redisConfig.jedisPool();

        assertThat(pool).isNotNull();
        pool.close();
    }

    @Test
    @DisplayName("jedisPool() creates pool with non-empty password (auth)")
    void jedisPool_nonEmptyPassword_createsPoolWithAuth() throws Exception {
        setField("password", "secret");

        JedisPool pool = redisConfig.jedisPool();

        assertThat(pool).isNotNull();
        pool.close();
    }

    @Test
    void jedisPool_nullPassword_createsPoolWithoutAuth() throws Exception {
        setField("password", null);

        try (JedisPool pool = redisConfig.jedisPool()) {
            assertThat(pool).isNotNull();
        }
    }

    @Test
    void jedisPool_whitespacePassword_exercisesConfiguredButNonEmptyPath() throws Exception {
        setField("password", " ");

        try (JedisPool pool = redisConfig.jedisPool()) {
            assertThat(pool).isNotNull();
        }
    }

    public static class MapperFixture {
        public String name;
    }
}
