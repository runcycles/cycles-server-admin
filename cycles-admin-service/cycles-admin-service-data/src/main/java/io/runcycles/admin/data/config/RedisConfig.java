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
        LOG.info("Budget Governance v0.1.23 - Redis: {}:{}", host, port);
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);
        return (password == null || password.isEmpty()) ? new JedisPool(config, host, port) : new JedisPool(config, host, port, 2000, password);
    }
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
