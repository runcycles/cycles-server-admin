package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.service.CryptoService;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortSpec;
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
    @Autowired private CryptoService cryptoService;

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
            String subscriptionId = sub.getSubscriptionId();
            if (subscriptionId == null || subscriptionId.isBlank()) {
                subscriptionId = "whsub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                sub.setSubscriptionId(subscriptionId);
            }
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
            // Store signing secret separately, encrypted at rest
            if (signingSecret != null) {
                jedis.set("webhook:secret:" + subscriptionId, cryptoService.encrypt(signingSecret));
            }
        } catch (Exception e) {
            LOG.error("Failed to save webhook subscription: subscription_id={} tenant_id={} status={} event_types={} event_categories={} secret_present={}",
                sub != null ? sub.getSubscriptionId() : null,
                sub != null ? sub.getTenantId() : null,
                sub != null ? sub.getStatus() : null,
                sub != null ? sub.getEventTypes() : null,
                sub != null ? sub.getEventCategories() : null,
                sub != null && sub.getSigningSecret() != null,
                e);
            throw new RuntimeException("Failed to save webhook subscription", e);
        }
    }

    public String getSigningSecret(String subscriptionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String encrypted = jedis.get("webhook:secret:" + subscriptionId);
            return cryptoService.decrypt(encrypted);
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
            LOG.error("Failed to find webhook subscription: subscription_id={}", subscriptionId, e);
            throw new RuntimeException("Failed to find webhook subscription", e);
        }
    }

    /** Outcome of one webhook row in a tenant-close cascade. */
    public record CascadeDisableOutcome(String subscriptionId, String name, WebhookStatus priorStatus) {}

    /**
     * Spec v0.1.25.29 CASCADE SEMANTICS (Rule 1): disable every
     * non-DISABLED webhook subscription owned by {@code tenantId}. Already-
     * DISABLED subscriptions are skipped so re-issuing the cascade is a
     * no-op. Returns one outcome per subscription that was actually
     * transitioned; caller emits matching audit + events.
     *
     * <p>WebhookSubscription has no spec-level terminal enum value. Rule 2
     * (terminal-owner mutation guard, enforced by the controller-layer
     * interceptor) blocks any subsequent re-enable for closed-owner rows,
     * making DISABLED effectively-terminal without widening the enum.
     */
    public List<CascadeDisableOutcome> cascadeDisable(String tenantId) {
        List<CascadeDisableOutcome> outcomes = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers("webhooks:" + tenantId);
            if (ids == null || ids.isEmpty()) return outcomes;
            for (String id : ids) {
                try {
                    String data = jedis.get("webhook:" + id);
                    if (data == null) continue;
                    WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);
                    if (sub.getStatus() == WebhookStatus.DISABLED) continue;
                    WebhookStatus prior = sub.getStatus();
                    sub.setStatus(WebhookStatus.DISABLED);
                    sub.setUpdatedAt(Instant.now());
                    jedis.set("webhook:" + id, objectMapper.writeValueAsString(sub));
                    outcomes.add(new CascadeDisableOutcome(id, sub.getName(), prior));
                } catch (Exception e) {
                    LOG.warn("Cascade-disable skipped webhook subscription: subscription_id={} tenant_id={} error={}",
                        id, tenantId, e.getMessage(), e);
                }
            }
        }
        return outcomes;
    }

    public void update(String subscriptionId, WebhookSubscription updated) {
        try (Jedis jedis = jedisPool.getResource()) {
            updated.setUpdatedAt(Instant.now());
            // Persist rotated signing secret if present
            String newSecret = updated.getSigningSecret();
            if (newSecret != null) {
                jedis.set("webhook:secret:" + subscriptionId, cryptoService.encrypt(newSecret));
            }
            String json = objectMapper.writeValueAsString(updated);
            jedis.set("webhook:" + subscriptionId, json);
        } catch (Exception e) {
            LOG.error("Failed to update webhook subscription: subscription_id={} tenant_id={} status={} event_types={} event_categories={} secret_present={}",
                subscriptionId,
                updated != null ? updated.getTenantId() : null,
                updated != null ? updated.getStatus() : null,
                updated != null ? updated.getEventTypes() : null,
                updated != null ? updated.getEventCategories() : null,
                updated != null && updated.getSigningSecret() != null,
                e);
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
            LOG.error("Failed to delete webhook subscription: subscription_id={}", subscriptionId, e);
            throw new RuntimeException("Failed to delete webhook subscription", e);
        }
    }

    // Lua script for atomic lock release: only delete if value matches (owner check)
    private static final String RELEASE_LOCK_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then\n" +
        "  return redis.call('DEL', KEYS[1])\n" +
        "else\n" +
        "  return 0\n" +
        "end\n";

    /**
     * Acquire a distributed replay lock for a subscription. Returns true if lock acquired,
     * false if a replay is already in progress. Lock expires after 1 hour.
     */
    public boolean acquireReplayLock(String subscriptionId, String replayId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "replay:lock:" + subscriptionId;
            String result = jedis.set(key, replayId, new redis.clients.jedis.params.SetParams().nx().ex(3600));
            return "OK".equals(result);
        }
    }

    /**
     * Release the replay lock only if it is still owned by the given replayId.
     * Prevents accidentally releasing a lock that was re-acquired by another process
     * after the original lock expired.
     */
    public void releaseReplayLock(String subscriptionId, String replayId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "replay:lock:" + subscriptionId;
            jedis.eval(RELEASE_LOCK_LUA, List.of(key), List.of(replayId));
        }
    }

    public List<WebhookSubscription> listByTenant(String tenantId, String status, String eventType,
                                                   String cursor, int limit) {
        return listFromSet("webhooks:" + tenantId, status, eventType, cursor, limit, null, null);
    }

    public List<WebhookSubscription> listByTenant(String tenantId, String status, String eventType,
                                                   String cursor, int limit, SortSpec sortSpec) {
        return listFromSet("webhooks:" + tenantId, status, eventType, cursor, limit, sortSpec, null);
    }

    public List<WebhookSubscription> listByTenant(String tenantId, String status, String eventType,
                                                   String cursor, int limit, SortSpec sortSpec, String search) {
        return listFromSet("webhooks:" + tenantId, status, eventType, cursor, limit, sortSpec, search);
    }

    public List<WebhookSubscription> listAll(String status, String eventType, String cursor, int limit) {
        return listFromSet("webhooks:_all", status, eventType, cursor, limit, null, null);
    }

    public List<WebhookSubscription> listAll(String status, String eventType, String cursor, int limit, SortSpec sortSpec) {
        return listFromSet("webhooks:_all", status, eventType, cursor, limit, sortSpec, null);
    }

    public List<WebhookSubscription> listAll(String status, String eventType, String cursor, int limit,
                                              SortSpec sortSpec, String search) {
        return listFromSet("webhooks:_all", status, eventType, cursor, limit, sortSpec, search);
    }

    /**
     * Bulk-action match phase (spec v0.1.25.21). Returns every subscription
     * matching the filter (tenant_id / status / event_type / search) up to
     * {@code cap}+1 entries. Size {@code cap+1} on return signals the caller
     * to reject with HTTP 400 LIMIT_EXCEEDED.
     *
     * <p>Set-scan is {@code webhooks:{tenantId}} when tenantId is present,
     * else the global {@code webhooks:_all} index. Status is matched against
     * the enum name; eventType against each subscription's {@code eventTypes}
     * wire value — same semantics as the list endpoint's string-keyed
     * matcher.
     */
    public List<WebhookSubscription> matchForBulk(String tenantId, WebhookStatus status,
                                                   EventType eventType, String search, int cap) {
        String setKey = (tenantId != null && !tenantId.isBlank())
            ? "webhooks:" + tenantId
            : "webhooks:_all";
        String statusStr = status != null ? status.name() : null;
        String eventTypeStr = eventType != null ? eventType.getValue() : null;
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers(setKey);
            if (ids == null || ids.isEmpty()) return new ArrayList<>();
            List<WebhookSubscription> matches = new ArrayList<>();
            int ceiling = cap + 1;
            for (String id : ids) {
                WebhookSubscription sub = tryHydrate(jedis, id);
                if (sub == null) continue;
                if (!matchesStatusAndEventType(sub, statusStr, eventTypeStr)) continue;
                if (!matchesSearch(sub, search)) continue;
                matches.add(sub);
                if (matches.size() >= ceiling) break;
            }
            return matches;
        }
    }

    public List<WebhookSubscription> findMatchingSubscriptions(String tenantId, EventType eventType, String scope) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<WebhookSubscription> matching = new ArrayList<>();
            // Check tenant-specific subscriptions
            Set<String> tenantIds = jedis.smembers("webhooks:" + tenantId);
            // Also check system-wide subscriptions (tenantId = "_system")
            Set<String> systemIds = jedis.smembers("webhooks:__system__");
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
                    LOG.warn("Failed to parse webhook subscription while matching event: subscription_id={} tenant_id={} event_type={} scope={}",
                        id, tenantId, eventType != null ? eventType.getValue() : null, scope, e);
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
                                                   String cursor, int limit, SortSpec sortSpec, String search) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers(setKey);
            if (ids == null || ids.isEmpty()) {
                return new ArrayList<>();
            }
            if (sortSpec == null) {
                return listLegacy(jedis, ids, status, eventType, cursor, limit, search);
            }
            return listSorted(jedis, ids, status, eventType, cursor, limit, sortSpec, search);
        }
    }

    private List<WebhookSubscription> listLegacy(Jedis jedis, Set<String> ids, String status,
                                                  String eventType, String cursor, int limit, String search) {
        // Sort IDs lexicographically for deterministic pagination (pre-v0.1.25.20
        // behaviour). Preserved when caller passes no SortSpec so existing cursor
        // chains don't break.
        List<String> sortedIds = new ArrayList<>(ids);
        Collections.sort(sortedIds);
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
            WebhookSubscription sub = tryHydrate(jedis, sortedIds.get(i));
            if (sub == null) continue;
            if (!matchesStatusAndEventType(sub, status, eventType)) continue;
            if (!matchesSearch(sub, search)) continue;
            results.add(sub);
        }
        return results;
    }

    private List<WebhookSubscription> listSorted(Jedis jedis, Set<String> ids, String status,
                                                  String eventType, String cursor, int limit,
                                                  SortSpec sortSpec, String search) {
        // Sorted path: hydrate all, filter, sort, walk cursor strictly-after.
        // Cursor remains the subscription_id (wire-compat); caller must pass the
        // same sortSpec on follow-up pages for stable traversal.
        List<WebhookSubscription> all = new ArrayList<>();
        for (String id : ids) {
            WebhookSubscription sub = tryHydrate(jedis, id);
            if (sub == null) continue;
            if (!matchesStatusAndEventType(sub, status, eventType)) continue;
            if (!matchesSearch(sub, search)) continue;
            all.add(sub);
        }
        all.sort(webhookComparator(sortSpec));
        List<WebhookSubscription> results = new ArrayList<>();
        boolean pastCursor = (cursor == null || cursor.isBlank());
        for (WebhookSubscription sub : all) {
            if (!pastCursor) {
                if (cursor.equals(sub.getSubscriptionId())) pastCursor = true;
                continue;
            }
            results.add(sub);
            if (results.size() >= limit) break;
        }
        return results;
    }

    private WebhookSubscription tryHydrate(Jedis jedis, String id) {
        try {
            String data = jedis.get("webhook:" + id);
            if (data == null) return null;
            return objectMapper.readValue(data, WebhookSubscription.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse webhook subscription row: subscription_id={}", id, e);
            return null;
        }
    }

    private boolean matchesStatusAndEventType(WebhookSubscription sub, String status, String eventType) {
        if (status != null && (sub.getStatus() == null || !status.equals(sub.getStatus().name()))) {
            return false;
        }
        if (eventType != null) {
            if (sub.getEventTypes() == null
                || sub.getEventTypes().stream().noneMatch(et -> et.getValue().equals(eventType))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Spec v0.1.25.21: listWebhookSubscriptions search matches
     * {@code subscription_id} OR {@code url} as a case-insensitive
     * substring. Null search = no filter.
     */
    private static boolean matchesSearch(WebhookSubscription sub, String search) {
        if (search == null) return true;
        return SearchSpec.matches(sub.getSubscriptionId(), search)
            || SearchSpec.matches(sub.getUrl(), search);
    }

    /**
     * Null-safe comparator on the whitelisted sort fields. Secondary sort on
     * subscription_id guarantees total order for deterministic cursor resume.
     * Unknown fields fall back to the tie-breaker so the repo contract stays
     * total even if a new field is ever threaded in without a comparator case.
     */
    static Comparator<WebhookSubscription> webhookComparator(SortSpec sortSpec) {
        String field = sortSpec.field();
        Comparator<WebhookSubscription> primary;
        switch (field) {
            case "url":
                primary = Comparator.comparing(WebhookSubscription::getUrl, Comparator.nullsLast(String::compareTo));
                break;
            case "tenant_id":
                primary = Comparator.comparing(WebhookSubscription::getTenantId, Comparator.nullsLast(String::compareTo));
                break;
            case "status":
                primary = Comparator.comparing(
                    s -> s.getStatus() == null ? null : s.getStatus().name(),
                    Comparator.nullsLast(String::compareTo));
                break;
            case "consecutive_failures":
                primary = Comparator.comparing(
                    s -> s.getConsecutiveFailures() == null ? 0 : s.getConsecutiveFailures(),
                    Comparator.naturalOrder());
                break;
            default:
                primary = Comparator.comparing(WebhookSubscription::getSubscriptionId,
                    Comparator.nullsLast(String::compareTo));
                break;
        }
        Comparator<WebhookSubscription> withTieBreak = primary.thenComparing(
            WebhookSubscription::getSubscriptionId, Comparator.nullsLast(String::compareTo));
        return sortSpec.isAscending() ? withTieBreak : withTieBreak.reversed();
    }
}
