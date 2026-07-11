package io.runcycles.admin.data.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryAction;
import io.runcycles.admin.data.repository.WebhookRepository.CategoryBoundaryRepairOutcome;
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
        inject(webhookRepository, "cryptoService", new CryptoService(""));
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
    void reconcile_seededMix_disablesOffenders_conservatively_andIsIdempotent() {
        // 1) offender: admin category alongside a legit type → DISABLE (NOT
        //    stripped: it may be legit-but-misconfigured operator monitoring).
        seed("wh_admin_cat", "tenant-1", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.BUDGET, EventCategory.API_KEY), WebhookStatus.ACTIVE);
        // 2) offender: admin-category-only, no types → DISABLE (admin categories).
        seed("wh_admin_only", "tenant-2", List.of(),
                List.of(EventCategory.API_KEY), WebhookStatus.ACTIVE);
        // 3) legit tenant-accessible categories → untouched
        seed("wh_legit", "tenant-3", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.RESERVATION), WebhookStatus.ACTIVE);
        // 4) __system__ row with admin categories → NOT tenant-owned, untouched
        seed("wh_system", "__system__", List.of(EventType.API_KEY_CREATED),
                List.of(EventCategory.API_KEY), WebhookStatus.ACTIVE);
        // 5) pre-existing empty-both (match-ALL) row → DISABLE
        seed("wh_empty_both", "tenant-4", List.of(), null, WebhookStatus.ACTIVE);
        // 6) legit types-only → untouched
        seed("wh_types_only", "tenant-5", List.of(EventType.BUDGET_CREATED),
                null, WebhookStatus.ACTIVE);
        // 7) already-DISABLED offender → skipped (idempotent)
        seed("wh_already_disabled", "tenant-6", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.POLICY), WebhookStatus.DISABLED);

        List<CategoryBoundaryRepairOutcome> outcomes = webhookRepository.reconcileTenantCategoryBoundary(false);

        // Exactly the three ACTIVE offenders were disabled.
        assertThat(outcomes).extracting(CategoryBoundaryRepairOutcome::subscriptionId)
                .containsExactlyInAnyOrder("wh_admin_cat", "wh_admin_only", "wh_empty_both");

        assertThat(outcomeFor(outcomes, "wh_admin_cat").action()).isEqualTo(CategoryBoundaryAction.DISABLED_ADMIN_CATEGORIES);
        assertThat(outcomeFor(outcomes, "wh_admin_cat").offendingCategories()).containsExactly("api_key");
        assertThat(outcomeFor(outcomes, "wh_admin_only").action()).isEqualTo(CategoryBoundaryAction.DISABLED_ADMIN_CATEGORIES);
        assertThat(outcomeFor(outcomes, "wh_empty_both").action()).isEqualTo(CategoryBoundaryAction.DISABLED_EMPTY_BOTH);

        // Offenders DISABLED, categories left INTACT for operator review.
        WebhookSubscription adminCat = read("wh_admin_cat");
        assertThat(adminCat.getStatus()).isEqualTo(WebhookStatus.DISABLED);
        assertThat(adminCat.getEventCategories()).containsExactly(EventCategory.BUDGET, EventCategory.API_KEY); // NOT stripped
        assertThat(adminCat.getEventTypes()).containsExactly(EventType.BUDGET_CREATED);

        assertThat(read("wh_admin_only").getStatus()).isEqualTo(WebhookStatus.DISABLED);
        assertThat(read("wh_admin_only").getEventCategories()).containsExactly(EventCategory.API_KEY); // intact
        assertThat(read("wh_empty_both").getStatus()).isEqualTo(WebhookStatus.DISABLED);

        // Untouched rows.
        assertThat(read("wh_legit").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(read("wh_system").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(read("wh_system").getEventCategories()).containsExactly(EventCategory.API_KEY);
        assertThat(read("wh_types_only").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(read("wh_already_disabled").getStatus()).isEqualTo(WebhookStatus.DISABLED);

        // Idempotent: a second run finds nothing to disable.
        assertThat(webhookRepository.reconcileTenantCategoryBoundary(false)).isEmpty();
    }

    @Test
    void reconcile_dryRun_reportsOffenders_withoutMutating() {
        seed("wh_admin_cat", "tenant-1", List.of(EventType.BUDGET_CREATED),
                List.of(EventCategory.API_KEY), WebhookStatus.ACTIVE);
        seed("wh_empty_both", "tenant-2", List.of(), null, WebhookStatus.ACTIVE);

        List<CategoryBoundaryRepairOutcome> outcomes = webhookRepository.reconcileTenantCategoryBoundary(true);

        // Reports both offenders...
        assertThat(outcomes).extracting(CategoryBoundaryRepairOutcome::subscriptionId)
                .containsExactlyInAnyOrder("wh_admin_cat", "wh_empty_both");
        // ...but mutates nothing (both still ACTIVE).
        assertThat(read("wh_admin_cat").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(read("wh_empty_both").getStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void reconcile_emptyStore_isNoOp() {
        assertThat(webhookRepository.reconcileTenantCategoryBoundary(false)).isEmpty();
    }
}
