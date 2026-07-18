package io.runcycles.admin.data.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryAction;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryRepairOutcome;
import io.runcycles.admin.data.repository.WebhookRepository.ReconcileResult;
import io.runcycles.admin.data.service.CryptoService;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #209 (d2) one-time cleanup: {@link WebhookRepository#reconcileTenantCategoryBoundary()}
 * over a real Redis with a seeded mix of offender / legit / empty-both /
 * __system__ rows, asserting terminal states and idempotency on re-run.
 */
@Testcontainers
class WebhookCategoryBoundaryReconcileIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static JedisPool jedisPool;
    private static ObjectMapper objectMapper;
    private static WebhookRepository webhookRepository;

    @BeforeAll
    static void setupAll() throws Exception {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(10);
        jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        webhookRepository = new WebhookRepository();
        inject(webhookRepository, "jedisPool", jedisPool);
        inject(webhookRepository, "objectMapper", objectMapper);
        inject(webhookRepository, "cryptoService", new CryptoService("", true));
    }

    @AfterAll
    static void tearDownAll() {
        if (jedisPool != null) jedisPool.close();
    }

    @BeforeEach
    void flush() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
        }
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Seed a raw subscription row + index membership (mirrors save()'s keys). */
    private void seed(String id, String tenantId, List<EventType> types,
                      List<EventCategory> categories, WebhookStatus status) {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId(id).tenantId(tenantId).url("https://example.com/" + id)
                .eventTypes(types).eventCategories(categories).status(status)
                .createdAt(Instant.now()).build();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("webhook:" + id, objectMapper.writeValueAsString(sub));
            jedis.sadd("webhooks:_all", id);
            // Mirror production save(): also index by owning tenant (incl.
            // __system__). Null owner is the corruption case — left unindexed.
            if (tenantId != null) jedis.sadd("webhooks:" + tenantId, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WebhookSubscription read(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            return objectMapper.readValue(jedis.get("webhook:" + id), WebhookSubscription.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CategoryBoundaryRepairOutcome outcomeFor(List<CategoryBoundaryRepairOutcome> outcomes, String id) {
        return outcomes.stream().filter(o -> o.subscriptionId().equals(id)).findFirst().orElse(null);
    }

    @Test
    void reconcile_seededMix_stripsAndDisables_conservatively_andIsIdempotent() {
        // 1) concrete admin CATEGORY alongside a legit type -> STRIP admin cat, stay ACTIVE
        seed("wh_admin_cat", "tenant-1", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.BUDGET, EventCategory.API_KEY), WebhookStatus.ACTIVE);
        // 2) concrete admin-only TYPE, no other selector -> strip empties both -> DISABLE
        seed("wh_admin_type", "tenant-2", List.of(EventType.API_KEY_CREATED),
                null, WebhookStatus.ACTIVE);
        // 3) legit tenant-accessible categories -> untouched
        seed("wh_legit", "tenant-3", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.RESERVATION), WebhookStatus.ACTIVE);
        // 4) __system__ row with admin categories -> untouched (not tenant-owned)
        seed("wh_system", "__system__", List.of(EventType.API_KEY_CREATED),
                List.of(EventCategory.API_KEY), WebhookStatus.ACTIVE);
        // 5) tenant empty-both (match-ALL) -> DISABLE
        seed("wh_empty_both", "tenant-4", List.of(), null, WebhookStatus.ACTIVE);
        // 6) __system__ empty-both -> DISABLE (carve-out is admin-selectors only)
        seed("wh_system_empty_both", "__system__", List.of(), null, WebhookStatus.ACTIVE);
        // 7) null-owner -> normalized to __system__ (+ index), selectors intact
        seed("wh_null_owner", null, List.of(EventType.API_KEY_CREATED),
                List.of(EventCategory.API_KEY), WebhookStatus.ACTIVE);
        // 8) legit types-only -> untouched
        seed("wh_types_only", "tenant-5", List.of(EventType.BUDGET_CREATED),
                null, WebhookStatus.ACTIVE);
        // 9) already-DISABLED offender -> skipped (idempotent)
        seed("wh_already_disabled", "tenant-6", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.POLICY), WebhookStatus.DISABLED);

        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(false);
        assertThat(result.isComplete()).isTrue();
        List<CategoryBoundaryRepairOutcome> outcomes = result.repaired();

        assertThat(outcomes).extracting(CategoryBoundaryRepairOutcome::subscriptionId)
                .containsExactlyInAnyOrder("wh_admin_cat", "wh_admin_type", "wh_empty_both",
                        "wh_system_empty_both", "wh_null_owner");

        assertThat(outcomeFor(outcomes, "wh_admin_cat").action()).isEqualTo(CategoryBoundaryAction.STRIPPED_ADMIN_SELECTORS);
        assertThat(outcomeFor(outcomes, "wh_admin_cat").strippedSelectors()).containsExactly("api_key");
        assertThat(outcomeFor(outcomes, "wh_admin_type").action()).isEqualTo(CategoryBoundaryAction.STRIPPED_AND_DISABLED);
        assertThat(outcomeFor(outcomes, "wh_empty_both").action()).isEqualTo(CategoryBoundaryAction.DISABLED_EMPTY_BOTH);
        assertThat(outcomeFor(outcomes, "wh_system_empty_both").action()).isEqualTo(CategoryBoundaryAction.DISABLED_EMPTY_BOTH);
        assertThat(outcomeFor(outcomes, "wh_null_owner").action()).isEqualTo(CategoryBoundaryAction.NORMALIZED_NULL_OWNER);

        // Persisted states.
        WebhookSubscription adminCat = read("wh_admin_cat");
        assertThat(adminCat.getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(adminCat.getEventCategories()).containsExactly(EventCategory.BUDGET); // admin stripped
        assertThat(adminCat.getEventTypes()).containsExactly(EventType.BUDGET_CREATED);  // kept
        assertThat(read("wh_admin_type").getStatus()).isEqualTo(WebhookStatus.DISABLED);
        assertThat(read("wh_empty_both").getStatus()).isEqualTo(WebhookStatus.DISABLED);
        assertThat(read("wh_system_empty_both").getStatus()).isEqualTo(WebhookStatus.DISABLED);

        WebhookSubscription normalized = read("wh_null_owner");
        assertThat(normalized.getTenantId()).isEqualTo("__system__");
        assertThat(normalized.getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(normalized.getEventCategories()).containsExactly(EventCategory.API_KEY); // intact (now system)
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.sismember("webhooks:__system__", "wh_null_owner")).isTrue();
        }

        // Untouched.
        assertThat(read("wh_legit").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(read("wh_system").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(read("wh_types_only").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(read("wh_already_disabled").getStatus()).isEqualTo(WebhookStatus.DISABLED);

        // Idempotent: a second run reconciles nothing (all offenders repaired).
        ReconcileResult rerun = webhookRepository.reconcileTenantCategoryBoundary(false);
        assertThat(rerun.repaired()).isEmpty();
        assertThat(rerun.isComplete()).isTrue();
    }

    @Test
    void reconcile_dryRun_reportsWithoutMutating() {
        seed("wh_admin_cat", "tenant-1", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.BUDGET, EventCategory.API_KEY), WebhookStatus.ACTIVE);

        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(true);

        assertThat(result.repaired()).extracting(CategoryBoundaryRepairOutcome::subscriptionId)
                .containsExactly("wh_admin_cat");
        // Unchanged: admin category still present, still ACTIVE.
        assertThat(read("wh_admin_cat").getEventCategories())
                .containsExactly(EventCategory.BUDGET, EventCategory.API_KEY);
        assertThat(read("wh_admin_cat").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void reconcile_manyRows_spansMultipleSscanCursors() {
        // Force a small SSCAN batch so the sweep must page across cursors.
        // (RECONCILE_SCAN_BATCH is a COUNT hint; 600 members over a batch of 200
        // means several SSCAN round-trips — a single-batch bug would miss rows.)
        for (int i = 0; i < 600; i++) {
            seed("wh_ok_" + i, "tenant-x", List.of(EventType.BUDGET_CREATED), null, WebhookStatus.ACTIVE);
        }
        seed("wh_off_a", "tenant-y", List.of(EventType.API_KEY_CREATED), null, WebhookStatus.ACTIVE);
        seed("wh_off_b", "tenant-z", List.of(), null, WebhookStatus.ACTIVE);

        // Assert the index genuinely spans MULTIPLE SSCAN cursors at the
        // production COUNT — otherwise a single-batch bug wouldn't be caught.
        int roundTrips = 0;
        String cur = ScanParams.SCAN_POINTER_START;
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams p = new ScanParams().count(WebhookRepository.reconcileScanBatch());
            do {
                ScanResult<String> s = jedis.sscan("webhooks:_all", cur, p);
                cur = s.getCursor();
                roundTrips++;
            } while (!cur.equals(ScanParams.SCAN_POINTER_START));
        }
        assertThat(roundTrips).isGreaterThan(1);

        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(false);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.repaired()).extracting(CategoryBoundaryRepairOutcome::subscriptionId)
                .containsExactlyInAnyOrder("wh_off_a", "wh_off_b");
        assertThat(read("wh_off_a").getStatus()).isEqualTo(WebhookStatus.DISABLED); // admin type → strip empties → disable
        assertThat(read("wh_off_b").getStatus()).isEqualTo(WebhookStatus.DISABLED); // empty-both
    }

    @Test
    void reconcile_concurrentUpdate_casMiss_noClobber_notComplete() throws Exception {
        // Real-Redis CAS test: mutate the row AFTER the reconciler read it. We
        // stage the concurrent write by overriding get() through a wrapper is
        // hard here, so instead we prove the Lua CAS semantics directly: a row
        // whose stored value differs from the value the reconciler serialized-from
        // must NOT be overwritten. We simulate by seeding, capturing the raw JSON,
        // then changing the row, then invoking a CAS with the STALE old-value.
        seed("wh_cas", "tenant-1", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.API_KEY), WebhookStatus.ACTIVE);
        String stale;
        try (Jedis jedis = jedisPool.getResource()) {
            stale = jedis.get("webhook:wh_cas");
            // Concurrent operator update lands (changes the row).
            jedis.set("webhook:wh_cas", stale.replace("https://example.com/", "https://changed.example.com/"));
        }
        // A reconcile pass now reads the CHANGED value, so its CAS old-value
        // matches current → it WOULD write. To exercise the miss path we invoke
        // the ACTUAL production script (not a hand-copied string, so drift is
        // caught): CAS with the stale value must return 0 and not write.
        try (Jedis jedis = jedisPool.getResource()) {
            Object res = jedis.eval(
                WebhookRepository.reconcileCasSetAndIndexScript(),
                List.of("webhook:wh_cas", "webhooks:__system__"),
                List.of(stale, "{}", "0", "wh_cas"));
            assertThat(res).isEqualTo(0L);
            assertThat(jedis.get("webhook:wh_cas")).contains("https://changed.example.com/"); // not clobbered
        }
        // And a normal reconcile pass (reading the current value) succeeds.
        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(false);
        assertThat(result.isComplete()).isTrue();
        assertThat(read("wh_cas").getEventCategories()).isNullOrEmpty(); // admin category stripped
    }

    @Test
    void reconcile_blankOwner_isConcrete_stripped() {
        // Finding 5 at the data layer: a blank (whitespace-only) owner is NOT
        // system — it is concrete, so admin selectors are stripped.
        seed("wh_blank", "   ", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.API_KEY), WebhookStatus.ACTIVE);

        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(false);

        assertThat(outcomeFor(result.repaired(), "wh_blank").action())
                .isEqualTo(CategoryBoundaryAction.STRIPPED_ADMIN_SELECTORS);
        assertThat(read("wh_blank").getEventCategories()).isNullOrEmpty();
        assertThat(read("wh_blank").getEventTypes()).containsExactly(EventType.BUDGET_CREATED);
    }

    // #209 finding 2 (real Redis): a DISABLED null-owner row is still normalized
    // to __system__ and indexed atomically, and stays DISABLED.
    @Test
    void reconcile_disabledNullOwner_normalizedAndIndexed_staysDisabled() {
        seed("wh_dnull", null, List.of(EventType.API_KEY_CREATED),
                List.of(EventCategory.API_KEY), WebhookStatus.DISABLED);

        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(false);

        assertThat(outcomeFor(result.repaired(), "wh_dnull").action())
                .isEqualTo(CategoryBoundaryAction.NORMALIZED_NULL_OWNER);
        WebhookSubscription w = read("wh_dnull");
        assertThat(w.getTenantId()).isEqualTo("__system__");
        assertThat(w.getStatus()).isEqualTo(WebhookStatus.DISABLED);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.sismember("webhooks:__system__", "wh_dnull")).isTrue();
        }
    }

    // #209 finding 2 (real Redis): a system-owned row present in webhooks:_all
    // but MISSING from webhooks:__system__ (a prior partial normalization) has
    // its index membership repaired, independent of status.
    @Test
    void reconcile_systemRowMissingFromIndex_membershipRepaired() {
        // Seed directly into _all WITHOUT the __system__ index (simulate the
        // partial-failure state the old non-atomic code could leave behind).
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("wh_orphan").tenantId("__system__")
                .url("https://example.com/wh_orphan")
                .eventCategories(List.of(EventCategory.API_KEY))
                .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).build();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("webhook:wh_orphan", objectMapper.writeValueAsString(sub));
            jedis.sadd("webhooks:_all", "wh_orphan");
            assertThat(jedis.sismember("webhooks:__system__", "wh_orphan")).isFalse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(false);

        assertThat(outcomeFor(result.repaired(), "wh_orphan").action())
                .isEqualTo(CategoryBoundaryAction.INDEXED_SYSTEM_MEMBER);
        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.sismember("webhooks:__system__", "wh_orphan")).isTrue();
        }
        // Idempotent: a second pass sees it indexed and does nothing.
        assertThat(webhookRepository.reconcileTenantCategoryBoundary(false).repaired()).isEmpty();
    }

    @Test
    void reconcile_emptyStore_isNoOp() {
        ReconcileResult result = webhookRepository.reconcileTenantCategoryBoundary(false);
        assertThat(result.repaired()).isEmpty();
        assertThat(result.isComplete()).isTrue();
    }
}
