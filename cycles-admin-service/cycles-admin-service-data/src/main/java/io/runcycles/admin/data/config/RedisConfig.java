package io.runcycles.admin.data.config;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import redis.clients.jedis.*;
@Configuration
public class RedisConfig {
    private static final Logger LOG = LoggerFactory.getLogger(RedisConfig.class);
    @Value("${redis.host:localhost}") private String host;
    @Value("${redis.port:6379}") private int port;
    @Value("${redis.password:}") private String password;
    @Bean
    public JedisPool jedisPool() {
        LOG.info("Cycles Admin Redis pool initializing: host={} port={} password_configured={}",
            host, port, password != null && !password.isBlank());
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);
        config.setMaxIdle(10);
        config.setMinIdle(5);
        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(2000)
            .socketTimeoutMillis(2000);
        if (password != null && !password.isEmpty()) {
            clientConfig.password(password);
        }
        return new JedisPool(config, new HostAndPort(host, port), clientConfig.build());
    }
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /**
     * Redis records are an internal persistence format and may contain fields
     * written by a newer rolling-deployment peer. Keep that reader tolerant
     * without weakening the primary HTTP ObjectMapper used for spec-strict
     * request bodies.
     */
    @Bean("redisObjectMapper")
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = objectMapper().copy();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
