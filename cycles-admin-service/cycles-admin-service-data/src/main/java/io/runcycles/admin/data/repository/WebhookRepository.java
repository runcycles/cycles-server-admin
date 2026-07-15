package io.runcycles.admin.data.repository;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.logging.LogSanitizer;
import io.runcycles.admin.data.repository.support.CascadeMutationResult;
import io.runcycles.admin.data.repository.support.TenantCloseOutboxItem;
import io.runcycles.admin.data.repository.support.SortedQueryGuard;
import io.runcycles.admin.data.repository.support.CursorSupport;
import io.runcycles.admin.data.repository.support.RedisBatchReader;
import io.runcycles.admin.data.service.CryptoService;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import java.time.Instant;
import java.util.*;
@Repository
public class WebhookRepository {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookRepository.class);
    @Autowired private JedisPool jedisPool;
    @Autowired @Qualifier("redisObjectMapper") private ObjectMapper objectMapper;
    @Autowired private CryptoService cryptoService;

    private static final String CASCADE_SET_OUTBOX_LUA =
        "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end\n" +
        "redis.call('SET', KEYS[1], ARGV[2])\n" +
        "redis.call('SET', KEYS[2], ARGV[3])\n" +
        "redis.call('SADD', KEYS[3], ARGV[4])\n" +
        "return 1\n";

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
                sub != null ? LogSanitizer.safe(sub.getSubscriptionId()) : null,
                sub != null ? LogSanitizer.safe(sub.getTenantId()) : null,
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
            LOG.error("Failed to find webhook subscription: subscription_id={}", LogSanitizer.safe(subscriptionId), e);
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
    public CascadeMutationResult<CascadeDisableOutcome> cascadeDisable(String tenantId) {
        var outcomes = CascadeMutationResult.<CascadeDisableOutcome>builder();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> ids = jedis.smembers("webhooks:" + tenantId);
            if (ids == null || ids.isEmpty()) return outcomes.build();
            for (String id : ids) {
                boolean resolved = false;
                for (int attempt = 0; attempt < 3 && !resolved; attempt++) {
                    try {
                        String data = jedis.get("webhook:" + id);
                        if (data == null) { resolved = true; continue; }
                        WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);
                        if (sub.getStatus() == WebhookStatus.DISABLED) { resolved = true; continue; }
                        WebhookStatus prior = sub.getStatus();
                        sub.setStatus(WebhookStatus.DISABLED);
                        sub.setUpdatedAt(Instant.now());
                        String itemId = "webhook:" + id;
                        TenantCloseOutboxItem item = new TenantCloseOutboxItem(
                            itemId, tenantId, "webhook_subscription", id, sub.getName(),
                            null, null, prior.name(), 0L);
                        Object cas = jedis.eval(CASCADE_SET_OUTBOX_LUA,
                            List.of("webhook:" + id,
                                TenantCloseWorkRepository.outboxItemKey(tenantId, itemId),
                                TenantCloseWorkRepository.outboxKey(tenantId)),
                            List.of(data, objectMapper.writeValueAsString(sub),
                                objectMapper.writeValueAsString(item), itemId));
                        if (Long.valueOf(1L).equals(cas)) {
                            outcomes.succeeded(new CascadeDisableOutcome(id, sub.getName(), prior));
                            resolved = true;
                        }
                    } catch (Exception e) {
                        outcomes.failed(id, e);
                        LOG.warn("Cascade-disable skipped webhook subscription: subscription_id={} tenant_id={} error={}",
                            LogSanitizer.safe(id), LogSanitizer.safe(tenantId), LogSanitizer.safe(e.getMessage()), e);
                        resolved = true;
                    }
                }
                if (!resolved) {
                    outcomes.failed(id, new IllegalStateException("concurrent modification retry exhausted"));
                }
            }
        }
        return outcomes.build();
    }

    /**
     * Atomic compare-and-set + optional index membership, in ONE server-side op.
     * Only overwrites the row if it still holds exactly the value we read
     * (ARGV[1]) — so a reconcile write can never clobber a concurrent operator
     * update between our GET and SET. When ARGV[3] == "1" it ALSO adds the id to
     * the system index (KEYS[2]) inside the same atomic script, so a null-owner
     * normalization can never persist the owner rewrite while leaving the row
     * un-indexed (there is no window between SET and SADD). Returns 1 on write,
     * 0 if the row changed underneath us (a concurrent-skip, retried next pass).
     */
    private static final String CAS_SET_AND_INDEX_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then\n" +
        "  redis.call('SET', KEYS[1], ARGV[2])\n" +
        "  if ARGV[3] == '1' then redis.call('SADD', KEYS[2], ARGV[4]) end\n" +
        "  return 1\n" +
        "else\n" +
        "  return 0\n" +
        "end\n";

    /** Batch size for the SSCAN over the global webhook index. */
    private static final int RECONCILE_SCAN_BATCH = 200;

    /**
     * Exposes the exact production CAS+index Lua and SSCAN batch so integration
     * tests exercise the real script/constant rather than a hand-copied string
     * that could silently drift from production.
     */
    public static String reconcileCasSetAndIndexScript() { return CAS_SET_AND_INDEX_LUA; }
    public static int reconcileScanBatch() { return RECONCILE_SCAN_BATCH; }

    /** What the category-boundary reconciler did (or would do) to one row. */
    public enum CategoryBoundaryAction {
        /** Concrete-tenant row: admin-only selectors STRIPPED, tenant-accessible kept. */
        STRIPPED_ADMIN_SELECTORS,
        /** Stripping admin-only selectors emptied both selector lists → DISABLED. */
        STRIPPED_AND_DISABLED,
        /** Legacy empty-both (match-ALL) row → DISABLED (the 0.1.25.50 rule). */
        DISABLED_EMPTY_BOTH,
        /** Null-owner (corruption) row normalized to the __system__ owner + index. */
        NORMALIZED_NULL_OWNER,
        /**
         * System-owned row that was MISSING from the {@code webhooks:__system__}
         * dispatch index (e.g. a prior partial normalization that set the owner
         * but crashed before the SADD) — membership repaired. Independent of
         * status (a DISABLED system row still must be indexed).
         */
        INDEXED_SYSTEM_MEMBER
    }

    /** {@code strippedSelectors} = the admin-only type/category wire values removed (empty for pure disable/normalize). */
    public record CategoryBoundaryRepairOutcome(String subscriptionId, String tenantId,
                                                CategoryBoundaryAction action,
                                                List<String> strippedSelectors) {}

    /**
     * Result of one reconcile pass. {@code failures} counts rows that errored
     * or were skipped due to a concurrent modification (CAS miss); a pass with
     * {@code failures > 0} is incomplete and the caller should retry (the sweep
     * is idempotent, so re-running is safe).
     */
    public record ReconcileResult(List<CategoryBoundaryRepairOutcome> repaired, int failures) {
        public boolean isComplete() { return failures == 0; }
    }

    /**
     * Best-effort hygiene cleanup for #209 / governance v0.1.25.40.
     *
     * <p><b>This is NOT the security mechanism.</b> The durable confidentiality
     * guarantee is the fail-closed DISPATCH boundary
     * ({@code WebhookDispatchService}): a concrete-tenant subscription never
     * receives admin-only events, immediately and unconditionally, regardless of
     * stored selectors or this reconciler's state. Because dispatch already
     * blocks the leak, admin-only selectors stored on a concrete-tenant row are
     * already non-functional — so this reconciler safely STRIPS them (storage is
     * brought in line with the effective delivery behavior; NOT a behavior
     * change, and no collateral on legitimate tenant-accessible deliveries).
     *
     * <p>The strip/disable actions are idempotent — they skip already-{@code
     * DISABLED} rows. The NORMALIZE and INDEX actions run regardless of status
     * (a DISABLED null-owner or unindexed system row still needs fixing). Per row:
     * <ul>
     *   <li><b>Null-owner</b> (corruption): normalized to the {@code __system__}
     *       owner and added to the {@code webhooks:__system__} dispatch index so
     *       it is a well-classified system subscription rather than limbo
     *       (exempt from repair yet absent from every dispatch index). The owner
     *       rewrite (SET) and the index add (SADD) execute in ONE atomic Lua op,
     *       so a partial failure can never persist the owner while leaving the
     *       row un-indexed. {@code NORMALIZED_NULL_OWNER}.</li>
     *   <li><b>System-owned but MISSING from the index</b> (e.g. a prior partial
     *       normalization): membership repaired via an idempotent SADD,
     *       independent of status. {@code INDEXED_SYSTEM_MEMBER}.</li>
     *   <li><b>Concrete-tenant</b> (owner not null/{@code __system__}, per
     *       {@link WebhookSubscription#isSystemOwner}) carrying admin-only
     *       {@code event_types} and/or {@code event_categories}: those admin
     *       selectors are STRIPPED, tenant-accessible ones kept →
     *       {@code STRIPPED_ADMIN_SELECTORS}. If stripping empties BOTH lists the
     *       row would match every event, so it is DISABLED →
     *       {@code STRIPPED_AND_DISABLED}.</li>
     *   <li><b>Any</b> row (including {@code __system__}) that is empty-both — no
     *       {@code event_types} AND no {@code event_categories}, i.e. match-ALL →
     *       {@code DISABLED_EMPTY_BOTH}. The system carve-out exempts admin
     *       SELECTORS only, not the universal "at least one selector" invariant.</li>
     * </ul>
     * Every action is loudly logged for operator visibility.
     *
     * <p><b>Robust:</b> SSCANs the global index in batches (bounded memory, no
     * whole-keyspace SMEMBERS), writes via an atomic compare-and-set (never
     * clobbers a concurrent update — a CAS miss is counted as a failure and
     * retried next pass). Never throws.
     *
     * @param dryRun when true, compute + log the actions but mutate nothing
     */
    public ReconcileResult reconcileTenantCategoryBoundary(boolean dryRun) {
        List<CategoryBoundaryRepairOutcome> repaired = new ArrayList<>();
        int failures = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams().count(RECONCILE_SCAN_BATCH);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.sscan("webhooks:_all", cursor, params);
                cursor = scan.getCursor();
                for (String id : scan.getResult()) {
                    try {
                        String data = jedis.get("webhook:" + id);
                        if (data == null) continue;
                        WebhookSubscription sub = objectMapper.readValue(data, WebhookSubscription.class);

                        boolean nullOwner = sub.getTenantId() == null;
                        // Normalize null owner → __system__ (corruption fix); the row is then
                        // system-owned for the boundary logic below.
                        String effectiveOwner = nullOwner ? WebhookSubscription.SYSTEM_TENANT : sub.getTenantId();
                        boolean system = WebhookSubscription.isSystemOwner(effectiveOwner);
                        boolean disabledRow = sub.getStatus() == WebhookStatus.DISABLED;

                        // Index-membership repair, computed INDEPENDENTLY of status: a
                        // system-owned row (incl. a just-normalized null-owner, or one left
                        // un-indexed by a prior partial normalization — even if DISABLED)
                        // must be a member of the system dispatch index.
                        boolean needsIndex = system
                            && !jedis.sismember("webhooks:" + WebhookSubscription.SYSTEM_TENANT, id);

                        // Concrete-tenant rows: strip admin-only selectors (dispatch already
                        // blocks their delivery, so this only reconciles storage). Skipped for
                        // already-DISABLED rows (idempotent — nothing is delivered anyway).
                        List<String> stripped = new ArrayList<>();
                        boolean disable = false;
                        if (!disabledRow) {
                            if (!system) {
                                List<EventType> types = sub.getEventTypes();
                                List<EventCategory> cats = sub.getEventCategories();
                                List<EventType> keptTypes = new ArrayList<>();
                                List<EventCategory> keptCats = new ArrayList<>();
                                if (types != null) for (EventType t : types) {
                                    if (t.isTenantAccessible()) keptTypes.add(t); else stripped.add(t.getValue());
                                }
                                if (cats != null) for (EventCategory c : cats) {
                                    if (c.isTenantAccessible()) keptCats.add(c); else stripped.add(c.getValue());
                                }
                                if (!stripped.isEmpty()) {
                                    sub.setEventTypes(keptTypes.isEmpty() ? null : keptTypes);
                                    sub.setEventCategories(keptCats.isEmpty() ? null : keptCats);
                                }
                            }
                            // Empty-both computed on the RESULTING selectors (after any strip).
                            List<EventType> t2 = sub.getEventTypes();
                            List<EventCategory> c2 = sub.getEventCategories();
                            boolean emptyBoth = (t2 == null || t2.isEmpty()) && (c2 == null || c2.isEmpty());
                            disable = emptyBoth;
                        }
                        boolean didStrip = !stripped.isEmpty();

                        // A JSON write is needed for owner rewrite / strip / disable; a pure
                        // index-membership repair needs only an (idempotent) SADD.
                        boolean jsonChange = nullOwner || didStrip || disable;
                        if (!jsonChange && !needsIndex) continue; // clean row

                        CategoryBoundaryAction action;
                        if (didStrip && disable) action = CategoryBoundaryAction.STRIPPED_AND_DISABLED;
                        else if (didStrip)       action = CategoryBoundaryAction.STRIPPED_ADMIN_SELECTORS;
                        else if (disable)        action = CategoryBoundaryAction.DISABLED_EMPTY_BOTH;
                        else if (nullOwner)      action = CategoryBoundaryAction.NORMALIZED_NULL_OWNER;
                        else                     action = CategoryBoundaryAction.INDEXED_SYSTEM_MEMBER;

                        if (!dryRun) {
                            if (jsonChange) {
                                if (nullOwner) sub.setTenantId(WebhookSubscription.SYSTEM_TENANT);
                                if (disable) sub.setStatus(WebhookStatus.DISABLED);
                                sub.setUpdatedAt(Instant.now());
                                String newJson = objectMapper.writeValueAsString(sub);
                                // SADD folded into the SAME atomic op when the row must be indexed.
                                boolean doIndex = nullOwner || needsIndex;
                                Object casRes = jedis.eval(CAS_SET_AND_INDEX_LUA,
                                    List.of("webhook:" + id, "webhooks:" + WebhookSubscription.SYSTEM_TENANT),
                                    List.of(data, newJson, doIndex ? "1" : "0", id));
                                if (!Long.valueOf(1L).equals(casRes)) {
                                    failures++;
                                    LOG.warn("category-boundary reconcile CAS miss (concurrent update), will retry: subscription_id={} tenant_id={}",
                                        LogSanitizer.safe(id), LogSanitizer.safe(effectiveOwner));
                                    continue;
                                }
                            } else {
                                // Pure index repair: SADD is idempotent and cannot clobber the
                                // row, so no CAS is needed.
                                jedis.sadd("webhooks:" + WebhookSubscription.SYSTEM_TENANT, id);
                            }
                        }

                        repaired.add(new CategoryBoundaryRepairOutcome(id, effectiveOwner, action, stripped));
                        LOG.warn("category-boundary reconcile {} webhook (#209): subscription_id={} tenant_id={} action={} stripped_selectors={} — dispatch already blocks admin-only delivery to concrete-tenant subs; this reconciles storage.",
                            dryRun ? "REPORTED (dry-run)" : "repaired",
                            LogSanitizer.safe(id), LogSanitizer.safe(effectiveOwner), action, stripped);
                    } catch (Exception e) {
                        failures++;
                        LOG.warn("category-boundary reconcile skipped webhook subscription (will retry): subscription_id={} error={}",
                            LogSanitizer.safe(id), LogSanitizer.safe(e.getMessage()), e);
                    }
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            failures++;
            LOG.error("category-boundary reconcile pass failed (will retry): error={}",
                LogSanitizer.safe(e.getMessage()), e);
        }
        return new ReconcileResult(repaired, failures);
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
                LogSanitizer.safe(subscriptionId),
                updated != null ? LogSanitizer.safe(updated.getTenantId()) : null,
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
            LOG.error("Failed to delete webhook subscription: subscription_id={}", LogSanitizer.safe(subscriptionId), e);
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

    /** Exact match count used only for an over-limit bulk rejection response. */
    public int countForBulk(String tenantId, WebhookStatus status,
                            EventType eventType, String search) {
        String setKey = (tenantId != null && !tenantId.isBlank())
            ? "webhooks:" + tenantId : "webhooks:_all";
        String statusStr = status != null ? status.name() : null;
        String eventTypeStr = eventType != null ? eventType.getValue() : null;
        int count = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            for (String id : jedis.smembers(setKey)) {
                WebhookSubscription sub = tryHydrate(jedis, id);
                if (sub == null) continue;
                if (!matchesStatusAndEventType(sub, statusStr, eventTypeStr)) continue;
                if (!matchesSearch(sub, search)) continue;
                count++;
            }
        }
        return count;
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
                        LogSanitizer.safe(id), LogSanitizer.safe(tenantId), eventType != null ? eventType.getValue() : null, LogSanitizer.safe(scope), e);
                }
            }
            return matching;
        }
    }

    /**
     * Event-type/category matcher shared by live dispatch
     * ({@link #findMatchingSubscriptions}) and replay
     * ({@code WebhookService.replay}) — public static so replay honors the same
     * "event types the subscription is subscribed to" contract as live delivery
     * (spec: replayEvents) and cannot leak events outside the subscription's
     * own selectors.
     */
    public static boolean matchesEventType(WebhookSubscription sub, EventType eventType) {
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

    /**
     * Spec-conformant {@code scope_filter} matcher. The admin OpenAPI spec
     * (WebhookCreateRequest/WebhookUpdateRequest {@code scope_filter}) is the
     * authority: <i>"Optional scope pattern to narrow event matching. Supports
     * wildcards: "tenant:acme-corp/*" matches all scopes under acme-corp. If
     * omitted, matches all scopes within the tenant."</i>
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code null}/blank filter — matches every event, including events
     *       with a null scope (no restriction).</li>
     *   <li>Bare {@code "*"} — matches every event that <b>has</b> a scope;
     *       null-scope and blank-scope events are excluded.</li>
     *   <li>Filter ending in {@code "*"} (e.g. {@code "tenant:acme-corp/*"}) —
     *       prefix match on the filter minus the trailing {@code "*"}: the
     *       event scope must start with {@code "tenant:acme-corp/"} <b>and</b>
     *       carry a non-empty remainder after the prefix. The bare base scope
     *       {@code "tenant:acme-corp"} does <b>not</b> match, nor does the
     *       degenerate {@code "tenant:acme-corp/"} (empty child segment) — the
     *       spec says "all scopes <b>under</b> acme-corp" (children only).</li>
     *   <li>Filter without a trailing {@code "*"} — <b>exact</b> match only.
     *       Child scopes do not match. Any non-trailing {@code "*"} is a
     *       literal character. Matching is case-sensitive.</li>
     *   <li>Non-blank filter + null or blank event scope — no match: unscoped
     *       events are not delivered to scope-filtered subscriptions (a blank
     *       scope is treated as unscoped).</li>
     * </ul>
     *
     * <p><b>BEHAVIOR CHANGE</b> from the previous implementation, which did
     * literal prefix matching ({@code scope.startsWith(filter)}) and matched
     * every filter when the event scope was null. Under the old matcher a
     * filter like {@code "tenant:acme-corp"} also matched
     * {@code "tenant:acme-corp/workspace:prod"} (and even
     * {@code "tenant:acme-corpX"}), while a spec-style {@code "tenant:acme-corp/*"}
     * matched nothing. Such prefix-style filters must now be written with the
     * spec wildcard form {@code "tenant:acme-corp/*"} to match child scopes.
     *
     * <p>Public and static: this is the single scope_filter matcher for every
     * delivery path — live dispatch ({@link #findMatchingSubscriptions}) and
     * the api-module replay path ({@code WebhookService#replay}) both call it,
     * so live and replayed deliveries cannot drift.
     *
     * @param sub   the subscription whose {@code scope_filter} applies
     * @param scope the event's scope path, may be null/blank (= unscoped)
     */
    public static boolean matchesScope(WebhookSubscription sub, String scope) {
        String filter = sub.getScopeFilter();
        if (filter == null || filter.isBlank()) return true;
        if (scope == null || scope.isBlank()) return false;
        if (filter.equals("*")) return true;
        if (filter.endsWith("*")) {
            String prefix = filter.substring(0, filter.length() - 1);
            return scope.length() > prefix.length() && scope.startsWith(prefix);
        }
        return scope.equals(filter);
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
        int startIdx = CursorSupport.startAfterIds(sortedIds, cursor);
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
        SortedQueryGuard.requireScannable(ids.size(), "webhook");
        // Sorted path: hydrate all, filter, sort, walk cursor strictly-after.
        // Cursor remains the subscription_id (wire-compat); caller must pass the
        // same sortSpec on follow-up pages for stable traversal.
        List<WebhookSubscription> all = new ArrayList<>();
        Map<String, String> rows = RedisBatchReader.getById(jedis, "webhook:", ids);
        for (String id : ids) {
            WebhookSubscription sub = tryHydrate(id, rows.get(id));
            if (sub == null) continue;
            if (!matchesStatusAndEventType(sub, status, eventType)) continue;
            if (!matchesSearch(sub, search)) continue;
            all.add(sub);
        }
        SortedQueryGuard.requireBounded(all.size(), "webhook");
        all.sort(webhookComparator(sortSpec));
        List<WebhookSubscription> results = new ArrayList<>();
        int start = CursorSupport.startAfter(
            all, cursor, WebhookSubscription::getSubscriptionId);
        for (int i = start; i < all.size(); i++) {
            WebhookSubscription sub = all.get(i);
            results.add(sub);
            if (results.size() >= limit) break;
        }
        return results;
    }

    private WebhookSubscription tryHydrate(Jedis jedis, String id) {
        try {
            String data = jedis.get("webhook:" + id);
            return tryHydrate(id, data);
        } catch (Exception e) {
            LOG.warn("Failed to parse webhook subscription row: subscription_id={}", LogSanitizer.safe(id), e);
            return null;
        }
    }

    private WebhookSubscription tryHydrate(String id, String data) {
        if (data == null) return null;
        try {
            return objectMapper.readValue(data, WebhookSubscription.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse webhook subscription row: subscription_id={}",
                LogSanitizer.safe(id), e);
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
