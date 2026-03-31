package io.runcycles.admin.data.repository;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
@Repository
public class WebhookSecurityConfigRepository {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookSecurityConfigRepository.class);
    private static final String CONFIG_KEY = "config:webhook-security";
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    public WebhookSecurityConfig get() {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get(CONFIG_KEY);
            if (data == null) {
                return WebhookSecurityConfig.builder().build();
            }
            return objectMapper.readValue(data, WebhookSecurityConfig.class);
        } catch (Exception e) {
            LOG.error("Failed to read webhook security config", e);
            throw new RuntimeException("Failed to read webhook security config", e);
        }
    }

    public void save(WebhookSecurityConfig config) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(config);
            jedis.set(CONFIG_KEY, json);
        } catch (Exception e) {
            LOG.error("Failed to save webhook security config", e);
            throw new RuntimeException("Failed to save webhook security config", e);
        }
    }
}
