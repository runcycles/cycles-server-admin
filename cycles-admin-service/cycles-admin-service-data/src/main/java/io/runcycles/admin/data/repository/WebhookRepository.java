package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import java.time.Instant;
import java.util.*;
@Repository
public class WebhookRepository {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired private ObjectMapper objectMapper;

    // Lua script for atomic webhook creation: SET JSON + SADD tenant index + SADD global index
    private static final String SAVE_WEBHOOK_LUA =
        "redis.call('SET', KEYS[1], ARGV[1])\n" +
        "redis.call('SADD', KEYS[2], ARGV[2])\n" +
        "redis.call('SADD', KEYS[3], ARGV[2])\n" +
        "return 1\n";

    // Lua script for atomic webhook deletion: GET to find tenantId, then DEL + SREM tenant + SREM global
    private static final String DELETE_WEBHOOK_LUA =
        "local data = redis.call('GET', KEYS[1])\n" +
        "if not data then return 0 end\n" +
        "redis.call('DEL', KEYS[1])\n" +
        "redis.call('SREM', KEYS[2], ARGV[1])\n" +
        "redis.call('SREM', KEYS[3], ARGV[1])\n" +
        "return 1\n";

    public void save(WebhookSubscription sub) {
        try (Jedis jedis = jedisPool.getResource()) {
            String subscriptionId = "whsub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            sub.setSubscriptionId(subscriptionId);
            if (sub.getCreatedAt() == null) {
                sub.setCreatedAt(Instant.now());
            }
            if (sub.getStatus() == null) {
                sub.setStatus(WebhookStatus.ACTIVE);
            }
            if (sub.getConsecutiveFailures() == null) {
                sub.setConsecutiveFailures(0);
            }
            String signingSecret = sub.getSigningSecret();
            String json = objectMapper.writeValueAsString(sub);
            jedis.eval(SAVE_WEBHOOK_LUA,
                List.of("webhook:" + subscriptionId,
                        "webhooks:" + sub.getTenantId(),
                        "webhooks:_all"),
                List.of(json, subscriptionId));
            // Store signing secret separately (not in JSON due to @JsonIgnore)
            if (signingSecret != null) {
                jedis.set("webhook:secret:" + subscriptionId, signingSecret);
            }
        } catch (Exception e) {
            LOG.error("Failed to save webhook subscription", e);
            throw new RuntimeException("Failed to save webhook subscription", e);
        }
    }

    public WebhookSubscription findById(String subscriptionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String data = jedis.get("webhook:" + subscriptionId);
            if (data == null) {
                throw GovernanceException.webhookNotFound(subscriptionId);
            }
            return objectMapper.readValue(data, WebhookSubscription.class);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to find webhook: {}", subscriptionId, e);
            throw new RuntimeException("Failed to find webhook subscription", e);
        }
    }

    public void update(String subscriptionId, WebhookSubscription updated) {
        try (Jedis jedis = jedisPool.getResource()) {
            updated.setUpdatedAt(Instant.now());
            String json = objectMapper.writeValueAsString(updated);
            jedis.set("webhook:" + subscriptionId, json);
        } catch (Exception e) {
            LOG.error("Failed to update webhook: {}", subscriptionId, e);
            throw new RuntimeException("Failed to update webhook subscription", e);
        }
    }

    public void delete(String subscriptionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            // Need to read the subscription first to find the tenantId for index cleanup
            String data = jedis.get("webhook:" + subscriptionId);
            if (data == null) {
                throw GovernanceException.webhookNotFound(subscriptionId);
            }
            WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);
            jedis.eval(DELETE_WEBHOOK_LUA,
                List.of("webhook:" + subscriptionId,
                        "webhooks:" + sub.getTenantId(),
                        "webhooks:_all"),
                List.of(subscriptionId));
            jedis.del("webhook:secret:" + subscriptionId);
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to delete webhook: {}", subscriptionId, e);
            throw new RuntimeException("Failed to delete webhook subscription", e);
        }
    }

    public List<WebhookSubscription> listByTenant(String tenantId, String status, String eventType,
                                                   String cursor, int limit) {
        return listFromSet("webhooks:" + tenantId, status, eventType, cursor, limit);
    }

    public List<WebhookSubscription> listAll(String status, String eventType, String cursor, int limit) {
        return listFromSet("webhooks:_all", status, eventType, cursor, limit);
    }

    public List<WebhookSubscription> findMatchingSubscriptions(String tenantId, EventType eventType, String scope) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<WebhookSubscription> matching = new ArrayList<>();
            // Check tenant-specific subscriptions
            Set<String> tenantIds = jedis.smembers("webhooks:" + tenantId);
            // Also check system-wide subscriptions (tenantId = "_system")
            Set<String> systemIds = jedis.smembers("webhooks:_system");
            Set<String> allIds = new HashSet<>();
            if (tenantIds != null) allIds.addAll(tenantIds);
            if (systemIds != null) allIds.addAll(systemIds);

            for (String id : allIds) {
                try {
                    String data = jedis.get("webhook:" + id);
                    if (data == null) continue;
                    WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);
                    if (sub.getStatus() != WebhookStatus.ACTIVE) continue;
                    // Check event type match: subscription must include this event type or its category
                    if (!matchesEventType(sub, eventType)) continue;
                    // Check scope filter match
                    if (!matchesScope(sub, scope)) continue;
                    matching.add(sub);
                } catch (Exception e) {
                    LOG.warn("Failed to parse webhook subscription: {}", id, e);
                }
            }
            return matching;
        }
    }

    private boolean matchesEventType(WebhookSubscription sub, EventType eventType) {
        // Check if the subscription explicitly lists this event type
        if (sub.getEventTypes() != null && !sub.getEventTypes().isEmpty()) {
            if (sub.getEventTypes().contains(eventType)) return true;
        }
        // Check if the subscription matches by category (wildcard category subscription)
        if (sub.getEventCategories() != null && !sub.getEventCategories().isEmpty()) {
            if (sub.getEventCategories().contains(eventType.getCategory())) return true;
        }
        // If neither event types nor categories are specified, match all
        return (sub.getEventTypes() == null || sub.getEventTypes().isEmpty())
            && (sub.getEventCategories() == null || sub.getEventCategories().isEmpty());
    }

    private boolean matchesScope(WebhookSubscription sub, String scope) {
        if (sub.getScopeFilter() == null || sub.getScopeFilter().isBlank()) return true;
        if (scope == null) return true;
        // Scope filter uses prefix matching (e.g., "workspace:eng" matches "workspace:eng:project1")
        return scope.startsWith(sub.getScopeFilter()) || sub.getScopeFilter().equals("*");
    }

    private List<WebhookSubscription> listFromSet(String setKey, String status, String eventType,
                                                   String cursor, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers(setKey);
            if (ids == null || ids.isEmpty()) {
                return new ArrayList<>();
            }

            // Sort for deterministic pagination
            List<String> sortedIds = new ArrayList<>(ids);
            Collections.sort(sortedIds);

            // Apply cursor: skip all IDs up to and including the cursor
            int startIdx = 0;
            if (cursor != null && !cursor.isBlank()) {
                for (int i = 0; i < sortedIds.size(); i++) {
                    if (sortedIds.get(i).equals(cursor)) {
                        startIdx = i + 1;
                        break;
                    }
                }
            }

            List<WebhookSubscription> results = new ArrayList<>();
            for (int i = startIdx; i < sortedIds.size() && results.size() < limit; i++) {
                String id = sortedIds.get(i);
                try {
                    String data = jedis.get("webhook:" + id);
                    if (data == null) continue;
                    WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);
                    if (status != null && !status.equals(sub.getStatus().name())) continue;
                    if (eventType != null && sub.getEventTypes() != null
                        && sub.getEventTypes().stream().noneMatch(et -> et.getValue().equals(eventType))) continue;
                    results.add(sub);
                } catch (Exception e) {
                    LOG.warn("Failed to parse webhook subscription: {}", id, e);
                }
            }
            return results;
        }
    }
}
