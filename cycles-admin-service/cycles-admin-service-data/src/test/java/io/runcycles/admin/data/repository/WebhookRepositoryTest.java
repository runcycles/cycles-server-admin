package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.service.CryptoService;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.model.webhook.WebhookStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @Spy private CryptoService cryptoService = new CryptoService(""); // pass-through
    @InjectMocks private WebhookRepository repository;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
    }

    // ---- save() ----

    @Test
    void save_setsSubscriptionIdAndDefaults() {
        WebhookSubscription sub = WebhookSubscription.builder()
                .tenantId("tenant-1")
                .url("https://example.com/hook")
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .build();

        repository.save(sub);

        assertThat(sub.getSubscriptionId()).startsWith("whsub_");
        assertThat(sub.getCreatedAt()).isNotNull();
        assertThat(sub.getStatus()).isEqualTo(WebhookStatus.ACTIVE);
        assertThat(sub.getConsecutiveFailures()).isEqualTo(0);
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void save_callsLuaWithCorrectKeysAndSerializedJson() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .tenantId("tenant-1")
                .url("https://example.com/hook")
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .build();

        repository.save(sub);

        verify(jedis).eval(anyString(), argThat((List<String> keys) -> {
            return keys.size() == 3
                && keys.get(0).startsWith("webhook:whsub_")
                && keys.get(1).equals("webhooks:tenant-1")
                && keys.get(2).equals("webhooks:_all");
        }), argThat((List<String> args) -> {
            try {
                WebhookSubscription stored = objectMapper.readValue(args.get(0), WebhookSubscription.class);
                return "tenant-1".equals(stored.getTenantId())
                    && "https://example.com/hook".equals(stored.getUrl());
            } catch (Exception e) {
                return false;
            }
        }));
    }

    // ---- findById() ----

    @Test
    void findById_success_returnsSubscription() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_abc123")
                .tenantId("tenant-1")
                .url("https://example.com/hook")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .createdAt(Instant.now())
                .consecutiveFailures(0)
                .build();
        String json = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_abc123")).thenReturn(json);

        WebhookSubscription result = repository.findById("whsub_abc123");

        assertThat(result.getSubscriptionId()).isEqualTo("whsub_abc123");
        assertThat(result.getTenantId()).isEqualTo("tenant-1");
        assertThat(result.getStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void findById_notFound_throwsWebhookNotFound() {
        when(jedis.get("webhook:whsub_missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.findById("whsub_missing"))
                .isInstanceOf(GovernanceException.class)
                .satisfies(ex -> {
                    GovernanceException ge = (GovernanceException) ex;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.WEBHOOK_NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                    assertThat(ge.getMessage()).contains("whsub_missing");
                });
    }

    // ---- update() ----

    @Test
    void update_callsSetWithUpdatedJson() throws Exception {
        WebhookSubscription updated = WebhookSubscription.builder()
                .subscriptionId("whsub_abc123")
                .tenantId("tenant-1")
                .url("https://updated.example.com/hook")
                .status(WebhookStatus.PAUSED)
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .createdAt(Instant.now())
                .consecutiveFailures(0)
                .build();

        repository.update("whsub_abc123", updated);

        assertThat(updated.getUpdatedAt()).isNotNull();
        verify(jedis).set(eq("webhook:whsub_abc123"), argThat(json -> json.contains("updated.example.com")));
    }

    // ---- delete() ----

    @Test
    void delete_callsLuaWithCorrectKeys() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_abc123")
                .tenantId("tenant-1")
                .url("https://example.com/hook")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .createdAt(Instant.now())
                .consecutiveFailures(0)
                .build();
        String json = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_abc123")).thenReturn(json);

        repository.delete("whsub_abc123");

        verify(jedis).eval(anyString(), argThat((List<String> keys) -> {
            return keys.size() == 3
                && keys.get(0).equals("webhook:whsub_abc123")
                && keys.get(1).equals("webhooks:tenant-1")
                && keys.get(2).equals("webhooks:_all");
        }), argThat((List<String> args) -> "whsub_abc123".equals(args.get(0))));
    }

    @Test
    void delete_notFound_throwsWebhookNotFound() {
        when(jedis.get("webhook:whsub_missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.delete("whsub_missing"))
                .isInstanceOf(GovernanceException.class)
                .satisfies(ex -> {
                    GovernanceException ge = (GovernanceException) ex;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.WEBHOOK_NOT_FOUND);
                });
    }

    // ---- listByTenant() ----

    @Test
    void listByTenant_returnsTenantSubscriptions() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("tenant-1").url("https://b.com").status(WebhookStatus.PAUSED).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        String s2Json = objectMapper.writeValueAsString(s2);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);

        List<WebhookSubscription> result = repository.listByTenant("tenant-1", null, null, null, 50);

        assertThat(result).hasSize(2);
    }

    @Test
    void listByTenant_filtersByStatus() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("tenant-1").url("https://b.com").status(WebhookStatus.PAUSED).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        String s2Json = objectMapper.writeValueAsString(s2);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);

        List<WebhookSubscription> result = repository.listByTenant("tenant-1", "ACTIVE", null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void listByTenant_filtersByEventType() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("tenant-1").url("https://b.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        String s2Json = objectMapper.writeValueAsString(s2);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);

        List<WebhookSubscription> result = repository.listByTenant("tenant-1", null, "budget.created", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubscriptionId()).isEqualTo("whsub_2");
    }

    // ---- listAll() ----

    @Test
    void listAll_usesGlobalSet() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:_all")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);

        List<WebhookSubscription> result = repository.listAll(null, null, null, 50);

        assertThat(result).hasSize(1);
        verify(jedis).smembers("webhooks:_all");
    }

    @Test
    void listAll_emptySet_returnsEmpty() {
        when(jedis.smembers("webhooks:_all")).thenReturn(Set.of());

        List<WebhookSubscription> result = repository.listAll(null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void listAll_zeroLimit_returnsEmpty() {
        List<WebhookSubscription> result = repository.listAll(null, null, null, 0);
        assertThat(result).isEmpty();
    }

    // ---- findMatchingSubscriptions() ----

    @Test
    void findMatchingSubscriptions_matchesByEventType() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_matchesByEventCategory() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventCategories(List.of(EventCategory.BUDGET))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_FUNDED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_scopeFilterMatching() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("org/eng")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> matchResult = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/eng/team1");
        assertThat(matchResult).hasSize(1);

        List<WebhookSubscription> noMatchResult = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "org/sales/team1");
        assertThat(noMatchResult).isEmpty();
    }

    @Test
    void findMatchingSubscriptions_skipsPausedAndDisabled() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_paused", "whsub_disabled"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription paused = WebhookSubscription.builder()
                .subscriptionId("whsub_paused").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.PAUSED)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription disabled = WebhookSubscription.builder()
                .subscriptionId("whsub_disabled").tenantId("tenant-1").url("https://b.com")
                .status(WebhookStatus.DISABLED)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String pausedJson = objectMapper.writeValueAsString(paused);
        String disabledJson = objectMapper.writeValueAsString(disabled);
        when(jedis.get("webhook:whsub_paused")).thenReturn(pausedJson);
        when(jedis.get("webhook:whsub_disabled")).thenReturn(disabledJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findMatchingSubscriptions_includesSystemSubscriptions() throws Exception {
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(Set.of());
        Set<String> systemIds = new LinkedHashSet<>(List.of("whsub_sys1"));
        when(jedis.smembers("webhooks:__system__")).thenReturn(systemIds);

        WebhookSubscription sysSub = WebhookSubscription.builder()
                .subscriptionId("whsub_sys1").tenantId("__system__").url("https://system.example.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String sysJson = objectMapper.writeValueAsString(sysSub);
        when(jedis.get("webhook:whsub_sys1")).thenReturn(sysJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.TENANT_CREATED, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("__system__");
    }

    @Test
    void findMatchingSubscriptions_wildcardScopeFilter_matchesAll() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("*")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "any/scope/here");

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_missingData_skipsGracefully() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_gone", "whsub_ok"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_ok").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_gone")).thenReturn(null);
        when(jedis.get("webhook:whsub_ok")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_eventTypeNotMatched_excluded() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.TENANT_CREATED, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findMatchingSubscriptions_nullScopeWithScopeFilter_matches() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("org/eng")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        // null scope should match (matchesScope returns true when scope is null)
        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_blankScopeFilter_matchesAll() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("   ")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.BUDGET_CREATED, "any/scope");

        assertThat(result).hasSize(1);
    }

    @Test
    void listByTenant_withCursor_skipsIdsUpToCursor() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2", "whsub_3"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(ids);

        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("tenant-1").url("https://b.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s3 = WebhookSubscription.builder().subscriptionId("whsub_3").tenantId("tenant-1").url("https://c.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s2Json = objectMapper.writeValueAsString(s2);
        String s3Json = objectMapper.writeValueAsString(s3);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);
        when(jedis.get("webhook:whsub_3")).thenReturn(s3Json);

        List<WebhookSubscription> result = repository.listByTenant("tenant-1", null, null, "whsub_1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSubscriptionId()).isEqualTo("whsub_2");
    }

    @Test
    void listAll_filtersByStatusAndEventType() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2"));
        when(jedis.smembers("webhooks:_all")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("tenant-1").url("https://b.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        String s2Json = objectMapper.writeValueAsString(s2);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);

        List<WebhookSubscription> result = repository.listAll("ACTIVE", "tenant.created", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubscriptionId()).isEqualTo("whsub_1");
    }

    @Test
    void listAll_nullSmembers_returnsEmpty() {
        when(jedis.smembers("webhooks:_all")).thenReturn(null);

        List<WebhookSubscription> result = repository.listAll(null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void listFromSet_missingData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_gone", "whsub_ok"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(ids);

        WebhookSubscription s = WebhookSubscription.builder().subscriptionId("whsub_ok").tenantId("tenant-1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String sJson = objectMapper.writeValueAsString(s);
        when(jedis.get("webhook:whsub_gone")).thenReturn(null);
        when(jedis.get("webhook:whsub_ok")).thenReturn(sJson);

        List<WebhookSubscription> result = repository.listByTenant("tenant-1", null, null, null, 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void listFromSet_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2", "whsub_3"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);

        List<WebhookSubscription> result = repository.listByTenant("tenant-1", null, null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void save_redisException_throwsRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        WebhookSubscription sub = WebhookSubscription.builder()
                .tenantId("tenant-1")
                .url("https://example.com/hook")
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .build();

        assertThatThrownBy(() -> repository.save(sub))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to save webhook");
    }

    @Test
    void update_redisException_throwsRuntimeException() {
        when(jedis.set(anyString(), anyString())).thenThrow(new RuntimeException("Redis down"));

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();

        assertThatThrownBy(() -> repository.update("whsub_1", sub))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to update webhook");
    }

    @Test
    void delete_redisException_throwsRuntimeException() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.delete("whsub_1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to delete webhook");
    }

    @Test
    void findById_redisException_throwsRuntimeException() {
        when(jedis.get("webhook:whsub_err")).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.findById("whsub_err"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to find webhook");
    }

    @Test
    void findMatchingSubscriptions_wildcardSubscription_matchesAllEvents() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:__system__")).thenReturn(Set.of());

        // No eventTypes and no eventCategories means match all
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("tenant-1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("tenant-1", EventType.TENANT_CREATED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void getSigningSecret_found() {
        when(jedis.get("webhook:secret:whsub_1")).thenReturn("my-secret");

        String secret = repository.getSigningSecret("whsub_1");

        assertThat(secret).isEqualTo("my-secret");
    }

    @Test
    void getSigningSecret_notFound() {
        when(jedis.get("webhook:secret:whsub_missing")).thenReturn(null);

        String secret = repository.getSigningSecret("whsub_missing");

        assertThat(secret).isNull();
    }

    // ---- sort (spec v0.1.25.20 §V4) ----

    private WebhookSubscription sub(String id, String tenantId, String url,
                                    WebhookStatus status, Integer failures) {
        return WebhookSubscription.builder()
                .subscriptionId(id).tenantId(tenantId).url(url).status(status)
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .createdAt(Instant.now())
                .consecutiveFailures(failures)
                .build();
    }

    private void stubSet(String setKey, WebhookSubscription... subs) throws Exception {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        // Pre-serialize JSON outside Mockito's when(...) scope to avoid
        // UnfinishedStubbing when the @Spy ObjectMapper is invoked mid-stub.
        List<String> jsons = new ArrayList<>();
        for (WebhookSubscription s : subs) {
            ids.add(s.getSubscriptionId());
            jsons.add(objectMapper.writeValueAsString(s));
        }
        when(jedis.smembers(setKey)).thenReturn(ids);
        for (int i = 0; i < subs.length; i++) {
            when(jedis.get("webhook:" + subs[i].getSubscriptionId())).thenReturn(jsons.get(i));
        }
    }

    @Test
    void listByTenant_sortByUrlAsc_ordersLexicographically() throws Exception {
        WebhookSubscription a = sub("whsub_3", "tenant-1", "https://aardvark.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription b = sub("whsub_1", "tenant-1", "https://mango.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription c = sub("whsub_2", "tenant-1", "https://zebra.com", WebhookStatus.ACTIVE, 0);
        stubSet("webhooks:tenant-1", a, b, c);

        List<WebhookSubscription> result = repository.listByTenant(
            "tenant-1", null, null, null, 50, SortSpec.of("url", SortDirection.ASC));

        assertThat(result).extracting(WebhookSubscription::getUrl)
            .containsExactly("https://aardvark.com", "https://mango.com", "https://zebra.com");
    }

    @Test
    void listByTenant_sortByUrlDesc_reversesOrder() throws Exception {
        WebhookSubscription a = sub("whsub_1", "tenant-1", "https://aardvark.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription b = sub("whsub_2", "tenant-1", "https://zebra.com", WebhookStatus.ACTIVE, 0);
        stubSet("webhooks:tenant-1", a, b);

        List<WebhookSubscription> result = repository.listByTenant(
            "tenant-1", null, null, null, 50, SortSpec.of("url", SortDirection.DESC));

        assertThat(result).extracting(WebhookSubscription::getUrl)
            .containsExactly("https://zebra.com", "https://aardvark.com");
    }

    @Test
    void listAll_sortByTenantIdAsc() throws Exception {
        WebhookSubscription a = sub("whsub_1", "tenant-zulu", "https://a.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription b = sub("whsub_2", "tenant-alpha", "https://b.com", WebhookStatus.ACTIVE, 0);
        stubSet("webhooks:_all", a, b);

        List<WebhookSubscription> result = repository.listAll(
            null, null, null, 50, SortSpec.of("tenant_id", SortDirection.ASC));

        assertThat(result).extracting(WebhookSubscription::getTenantId)
            .containsExactly("tenant-alpha", "tenant-zulu");
    }

    @Test
    void listAll_sortByStatusAsc() throws Exception {
        WebhookSubscription a = sub("whsub_1", "tenant-1", "https://a.com", WebhookStatus.PAUSED, 0);
        WebhookSubscription b = sub("whsub_2", "tenant-1", "https://b.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription c = sub("whsub_3", "tenant-1", "https://c.com", WebhookStatus.DISABLED, 0);
        stubSet("webhooks:_all", a, b, c);

        List<WebhookSubscription> result = repository.listAll(
            null, null, null, 50, SortSpec.of("status", SortDirection.ASC));

        assertThat(result).extracting(s -> s.getStatus().name())
            .containsExactly("ACTIVE", "DISABLED", "PAUSED");
    }

    @Test
    void listAll_sortByConsecutiveFailuresDesc_surfacesFlakyFirst() throws Exception {
        WebhookSubscription a = sub("whsub_1", "tenant-1", "https://a.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription b = sub("whsub_2", "tenant-1", "https://b.com", WebhookStatus.ACTIVE, 7);
        WebhookSubscription c = sub("whsub_3", "tenant-1", "https://c.com", WebhookStatus.ACTIVE, 3);
        stubSet("webhooks:_all", a, b, c);

        List<WebhookSubscription> result = repository.listAll(
            null, null, null, 50, SortSpec.of("consecutive_failures", SortDirection.DESC));

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_2", "whsub_3", "whsub_1");
    }

    @Test
    void listAll_sortByConsecutiveFailures_treatsNullAsZero() throws Exception {
        WebhookSubscription a = sub("whsub_1", "tenant-1", "https://a.com", WebhookStatus.ACTIVE, null);
        WebhookSubscription b = sub("whsub_2", "tenant-1", "https://b.com", WebhookStatus.ACTIVE, 5);
        stubSet("webhooks:_all", a, b);

        List<WebhookSubscription> result = repository.listAll(
            null, null, null, 50, SortSpec.of("consecutive_failures", SortDirection.ASC));

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_1", "whsub_2");
    }

    @Test
    void listAll_sortedCursorResumesInSortedOrder() throws Exception {
        WebhookSubscription a = sub("whsub_a", "tenant-1", "https://aaa.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription b = sub("whsub_b", "tenant-1", "https://bbb.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription c = sub("whsub_c", "tenant-1", "https://ccc.com", WebhookStatus.ACTIVE, 0);
        stubSet("webhooks:_all", a, b, c);

        List<WebhookSubscription> result = repository.listAll(
            null, null, "whsub_a", 50, SortSpec.of("url", SortDirection.ASC));

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_b", "whsub_c");
    }

    @Test
    void listAll_unknownSortField_fallsBackToSubscriptionIdTieBreaker() throws Exception {
        WebhookSubscription a = sub("whsub_c", "tenant-1", "https://c.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription b = sub("whsub_a", "tenant-1", "https://a.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription c = sub("whsub_b", "tenant-1", "https://b.com", WebhookStatus.ACTIVE, 0);
        stubSet("webhooks:_all", a, b, c);

        // Bypass whitelist to exercise the comparator's default branch directly.
        SortSpec bogus = new SortSpec("unknown_field", SortDirection.ASC);
        List<WebhookSubscription> result = repository.listAll(null, null, null, 50, bogus);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_a", "whsub_b", "whsub_c");
    }

    @Test
    void listAll_nullSortSpec_preservesLegacyLexicographicOrder() throws Exception {
        WebhookSubscription a = sub("whsub_c", "tenant-1", "https://c.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription b = sub("whsub_a", "tenant-1", "https://a.com", WebhookStatus.ACTIVE, 0);
        stubSet("webhooks:_all", a, b);

        List<WebhookSubscription> result = repository.listAll(null, null, null, 50, null);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_a", "whsub_c");
    }

    @Test
    void listByTenant_sortedWithStatusFilter_appliesBothBeforeSort() throws Exception {
        WebhookSubscription a = sub("whsub_1", "tenant-1", "https://a.com", WebhookStatus.PAUSED, 0);
        WebhookSubscription b = sub("whsub_2", "tenant-1", "https://b.com", WebhookStatus.ACTIVE, 0);
        WebhookSubscription c = sub("whsub_3", "tenant-1", "https://c.com", WebhookStatus.ACTIVE, 0);
        stubSet("webhooks:tenant-1", a, b, c);

        List<WebhookSubscription> result = repository.listByTenant(
            "tenant-1", "ACTIVE", null, null, 50, SortSpec.of("url", SortDirection.DESC));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_3", "whsub_2");
    }

    @Test
    void listAll_sortedRespectsLimit() throws Exception {
        WebhookSubscription a = sub("whsub_1", "tenant-1", "https://a.com", WebhookStatus.ACTIVE, 1);
        WebhookSubscription b = sub("whsub_2", "tenant-1", "https://b.com", WebhookStatus.ACTIVE, 2);
        WebhookSubscription c = sub("whsub_3", "tenant-1", "https://c.com", WebhookStatus.ACTIVE, 3);
        stubSet("webhooks:_all", a, b, c);

        List<WebhookSubscription> result = repository.listAll(
            null, null, null, 2, SortSpec.of("consecutive_failures", SortDirection.DESC));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_3", "whsub_2");
    }

    // ---- search overloads (spec v0.1.25.21) ----

    @Test
    void listByTenant_withSearch_filtersBySubscriptionIdOrUrl() throws Exception {
        WebhookSubscription a = WebhookSubscription.builder()
            .subscriptionId("whsub_prod_1").tenantId("tenant-1").url("https://one.com/hook")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription b = WebhookSubscription.builder()
            .subscriptionId("whsub_dev_2").tenantId("tenant-1").url("https://two.com/hook")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();
        stubSet("webhooks:tenant-1", a, b);

        List<WebhookSubscription> bySubId = repository.listByTenant(
            "tenant-1", null, null, null, 50, null, "prod");
        assertThat(bySubId).extracting(WebhookSubscription::getSubscriptionId).containsExactly("whsub_prod_1");

        List<WebhookSubscription> byUrl = repository.listByTenant(
            "tenant-1", null, null, null, 50, null, "two.com");
        assertThat(byUrl).extracting(WebhookSubscription::getSubscriptionId).containsExactly("whsub_dev_2");
    }

    @Test
    void listAll_withSearch_filtersAcrossTenants() throws Exception {
        WebhookSubscription a = WebhookSubscription.builder()
            .subscriptionId("whsub_a").tenantId("tenant-1").url("https://alpha.example.com/hook")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription b = WebhookSubscription.builder()
            .subscriptionId("whsub_b").tenantId("tenant-2").url("https://beta.example.com/hook")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();
        stubSet("webhooks:_all", a, b);

        List<WebhookSubscription> result = repository.listAll(null, null, null, 50, null, "alpha");

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId).containsExactly("whsub_a");
    }

    // ---- update signing-secret rotation ----

    @Test
    void update_withNewSigningSecret_encryptsAndPersists() throws Exception {
        WebhookSubscription updated = WebhookSubscription.builder()
            .subscriptionId("whsub_rotate").tenantId("tenant-1").url("https://a.com/hook")
            .status(WebhookStatus.ACTIVE).signingSecret("rotated-secret-value")
            .createdAt(Instant.now()).consecutiveFailures(0).build();

        repository.update("whsub_rotate", updated);

        verify(jedis).set(eq("webhook:secret:whsub_rotate"), anyString());
    }

    // ---- replay lock ----

    @Test
    void acquireReplayLock_redisOk_returnsTrue() {
        when(jedis.set(eq("replay:lock:whsub_1"), eq("replay_123"), any(redis.clients.jedis.params.SetParams.class)))
            .thenReturn("OK");

        boolean acquired = repository.acquireReplayLock("whsub_1", "replay_123");

        assertThat(acquired).isTrue();
    }

    @Test
    void acquireReplayLock_redisNull_returnsFalse() {
        when(jedis.set(eq("replay:lock:whsub_1"), eq("replay_123"), any(redis.clients.jedis.params.SetParams.class)))
            .thenReturn(null);

        boolean acquired = repository.acquireReplayLock("whsub_1", "replay_123");

        assertThat(acquired).isFalse();
    }

    @Test
    void releaseReplayLock_delegatesToEvalWithOwnershipCheck() {
        repository.releaseReplayLock("whsub_1", "replay_owner");

        verify(jedis).eval(anyString(),
            argThat((List<String> keys) -> keys.size() == 1 && "replay:lock:whsub_1".equals(keys.get(0))),
            argThat((List<String> args) -> args.size() == 1 && "replay_owner".equals(args.get(0))));
    }

    // ---- tryHydrate deserialization failure ----

    @Test
    void list_hydrationFailureOnMalformedJson_skipsAndContinues() throws Exception {
        WebhookSubscription good = WebhookSubscription.builder()
            .subscriptionId("whsub_good").tenantId("tenant-1").url("https://ok.com")
            .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();
        String goodJson = objectMapper.writeValueAsString(good);
        when(jedis.smembers("webhooks:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_bad", "whsub_good")));
        when(jedis.get("webhook:whsub_bad")).thenReturn("{not valid json");
        when(jedis.get("webhook:whsub_good")).thenReturn(goodJson);

        List<WebhookSubscription> result = repository.listByTenant("tenant-1", null, null, null, 50);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_good");
    }

    // ---- matchForBulk (spec v0.1.25.21) ----

    private WebhookSubscription webhookRow(String id, String tenantId, String url,
                                           WebhookStatus status, EventType type) {
        return WebhookSubscription.builder()
            .subscriptionId(id).tenantId(tenantId).url(url).status(status)
            .eventTypes(List.of(type)).consecutiveFailures(0).createdAt(Instant.now()).build();
    }

    @Test
    void matchForBulk_tenantScoped_scansTenantSet() throws Exception {
        String json = objectMapper.writeValueAsString(
            webhookRow("whsub_1", "tenant-1", "https://a/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        when(jedis.smembers("webhooks:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_1")));
        when(jedis.get("webhook:whsub_1")).thenReturn(json);

        List<WebhookSubscription> result =
            repository.matchForBulk("tenant-1", null, null, null, 500);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_1");
        verify(jedis).smembers("webhooks:tenant-1");
        verify(jedis, never()).smembers("webhooks:_all");
    }

    @Test
    void matchForBulk_globalScope_scansAllSet() throws Exception {
        String json = objectMapper.writeValueAsString(
            webhookRow("whsub_a", "tenant-x", "https://a/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        when(jedis.smembers("webhooks:_all"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_a")));
        when(jedis.get("webhook:whsub_a")).thenReturn(json);

        List<WebhookSubscription> result =
            repository.matchForBulk(null, null, null, null, 500);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_a");
        verify(jedis).smembers("webhooks:_all");
    }

    @Test
    void matchForBulk_blankTenantId_fallsBackToAllSet() throws Exception {
        String json = objectMapper.writeValueAsString(
            webhookRow("whsub_b", "tenant-x", "https://b/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        when(jedis.smembers("webhooks:_all"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_b")));
        when(jedis.get("webhook:whsub_b")).thenReturn(json);

        List<WebhookSubscription> result =
            repository.matchForBulk("   ", null, null, null, 500);

        assertThat(result).hasSize(1);
        verify(jedis).smembers("webhooks:_all");
    }

    @Test
    void matchForBulk_emptyIdSet_returnsEmpty() {
        when(jedis.smembers("webhooks:_all")).thenReturn(Collections.emptySet());

        List<WebhookSubscription> result =
            repository.matchForBulk(null, null, null, null, 500);

        assertThat(result).isEmpty();
    }

    @Test
    void matchForBulk_nullIdSet_returnsEmpty() {
        when(jedis.smembers("webhooks:_all")).thenReturn(null);

        List<WebhookSubscription> result =
            repository.matchForBulk(null, null, null, null, 500);

        assertThat(result).isEmpty();
    }

    @Test
    void matchForBulk_statusFilter_excludesNonMatching() throws Exception {
        String json1 = objectMapper.writeValueAsString(
            webhookRow("whsub_1", "t", "https://a/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        String json2 = objectMapper.writeValueAsString(
            webhookRow("whsub_2", "t", "https://b/", WebhookStatus.PAUSED, EventType.TENANT_CREATED));
        when(jedis.smembers("webhooks:_all"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_1", "whsub_2")));
        when(jedis.get("webhook:whsub_1")).thenReturn(json1);
        when(jedis.get("webhook:whsub_2")).thenReturn(json2);

        List<WebhookSubscription> result =
            repository.matchForBulk(null, WebhookStatus.ACTIVE, null, null, 500);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_1");
    }

    @Test
    void matchForBulk_eventTypeFilter_excludesNonMatching() throws Exception {
        String json1 = objectMapper.writeValueAsString(
            webhookRow("whsub_1", "t", "https://a/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        String json2 = objectMapper.writeValueAsString(
            webhookRow("whsub_2", "t", "https://b/", WebhookStatus.ACTIVE, EventType.BUDGET_CREATED));
        when(jedis.smembers("webhooks:_all"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_1", "whsub_2")));
        when(jedis.get("webhook:whsub_1")).thenReturn(json1);
        when(jedis.get("webhook:whsub_2")).thenReturn(json2);

        List<WebhookSubscription> result =
            repository.matchForBulk(null, null, EventType.TENANT_CREATED, null, 500);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_1");
    }

    @Test
    void matchForBulk_searchMatchesUrlOrSubscriptionId() throws Exception {
        String acme = objectMapper.writeValueAsString(
            webhookRow("whsub_acme", "t", "https://partner.com/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        String other = objectMapper.writeValueAsString(
            webhookRow("whsub_other", "t", "https://elsewhere.io/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        when(jedis.smembers("webhooks:_all"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_acme", "whsub_other")));
        when(jedis.get("webhook:whsub_acme")).thenReturn(acme);
        when(jedis.get("webhook:whsub_other")).thenReturn(other);

        List<WebhookSubscription> byId =
            repository.matchForBulk(null, null, null, "acme", 500);
        assertThat(byId).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_acme");

        List<WebhookSubscription> byUrl =
            repository.matchForBulk(null, null, null, "elsewhere", 500);
        assertThat(byUrl).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_other");
    }

    @Test
    void matchForBulk_hydrationFailure_isSkipped() throws Exception {
        String okJson = objectMapper.writeValueAsString(
            webhookRow("whsub_ok", "t", "https://ok/", WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
        when(jedis.smembers("webhooks:_all"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_bad", "whsub_ok")));
        when(jedis.get("webhook:whsub_bad")).thenReturn("{not-json");
        when(jedis.get("webhook:whsub_ok")).thenReturn(okJson);

        List<WebhookSubscription> result =
            repository.matchForBulk(null, null, null, null, 500);

        assertThat(result).extracting(WebhookSubscription::getSubscriptionId)
            .containsExactly("whsub_ok");
    }

    @Test
    void matchForBulk_capPlusOneSentinel_stopsIteration() throws Exception {
        LinkedHashSet<String> ids =
            new LinkedHashSet<>(List.of("whsub_1", "whsub_2", "whsub_3", "whsub_4", "whsub_5"));
        when(jedis.smembers("webhooks:_all")).thenReturn(ids);
        // cap=3 → ceiling 4 → fifth row never hydrated. Only stub what
        // the loop will actually call; Mockito strict mode flags unused stubs.
        for (int i = 1; i <= 4; i++) {
            String j = objectMapper.writeValueAsString(
                webhookRow("whsub_" + i, "t", "https://" + i + "/",
                    WebhookStatus.ACTIVE, EventType.TENANT_CREATED));
            when(jedis.get("webhook:whsub_" + i)).thenReturn(j);
        }

        List<WebhookSubscription> result =
            repository.matchForBulk(null, null, null, null, 3);

        assertThat(result).hasSize(4);
        verify(jedis, never()).get("webhook:whsub_5");
    }

    // ---- cascadeDisable (spec v0.1.25.29 Rule 1) ----

    @Test
    void cascadeDisable_noOwnedSubscriptions_returnsEmpty() {
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(Collections.emptySet());

        List<WebhookRepository.CascadeDisableOutcome> outcomes = repository.cascadeDisable("tenant-1");

        assertThat(outcomes).isEmpty();
        verify(jedis, never()).get(anyString());
    }

    @Test
    void cascadeDisable_nullIndex_returnsEmpty() {
        when(jedis.smembers("webhooks:tenant-1")).thenReturn(null);

        List<WebhookRepository.CascadeDisableOutcome> outcomes = repository.cascadeDisable("tenant-1");

        assertThat(outcomes).isEmpty();
    }

    @Test
    void cascadeDisable_mixedStatuses_transitionsOnlyNonDisabled() throws Exception {
        WebhookSubscription active = webhookRow("whsub_1", "tenant-1", "https://a/",
            WebhookStatus.ACTIVE, EventType.TENANT_CREATED);
        WebhookSubscription paused = webhookRow("whsub_2", "tenant-1", "https://b/",
            WebhookStatus.PAUSED, EventType.TENANT_CREATED);
        WebhookSubscription disabled = webhookRow("whsub_3", "tenant-1", "https://c/",
            WebhookStatus.DISABLED, EventType.TENANT_CREATED);
        // Pre-compute JSON outside when(...) — Jackson invocations against the
        // @Spy objectMapper inside a stubbing expression trip Mockito's
        // UnfinishedStubbingException detector.
        String json1 = objectMapper.writeValueAsString(active);
        String json2 = objectMapper.writeValueAsString(paused);
        String json3 = objectMapper.writeValueAsString(disabled);
        when(jedis.smembers("webhooks:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_1", "whsub_2", "whsub_3")));
        when(jedis.get("webhook:whsub_1")).thenReturn(json1);
        when(jedis.get("webhook:whsub_2")).thenReturn(json2);
        when(jedis.get("webhook:whsub_3")).thenReturn(json3);

        List<WebhookRepository.CascadeDisableOutcome> outcomes = repository.cascadeDisable("tenant-1");

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).extracting(WebhookRepository.CascadeDisableOutcome::subscriptionId)
            .containsExactlyInAnyOrder("whsub_1", "whsub_2");
        assertThat(outcomes).extracting(WebhookRepository.CascadeDisableOutcome::priorStatus)
            .containsExactlyInAnyOrder(WebhookStatus.ACTIVE, WebhookStatus.PAUSED);
        assertThat(outcomes.get(0).toString()).contains("whsub_");
    }

    @Test
    void cascadeDisable_missingPayload_skipsRow() throws Exception {
        WebhookSubscription active = webhookRow("whsub_1", "tenant-1", "https://a/",
            WebhookStatus.ACTIVE, EventType.TENANT_CREATED);
        String json = objectMapper.writeValueAsString(active);
        when(jedis.smembers("webhooks:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_stale", "whsub_1")));
        when(jedis.get("webhook:whsub_stale")).thenReturn(null);
        when(jedis.get("webhook:whsub_1")).thenReturn(json);

        List<WebhookRepository.CascadeDisableOutcome> outcomes = repository.cascadeDisable("tenant-1");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).subscriptionId()).isEqualTo("whsub_1");
    }

    @Test
    void cascadeDisable_deserializationException_skipsRowAndContinues() throws Exception {
        WebhookSubscription active = webhookRow("whsub_1", "tenant-1", "https://a/",
            WebhookStatus.ACTIVE, EventType.TENANT_CREATED);
        String json = objectMapper.writeValueAsString(active);
        when(jedis.smembers("webhooks:tenant-1"))
            .thenReturn(new LinkedHashSet<>(List.of("whsub_bad", "whsub_1")));
        when(jedis.get("webhook:whsub_bad")).thenReturn("not-json");
        when(jedis.get("webhook:whsub_1")).thenReturn(json);

        List<WebhookRepository.CascadeDisableOutcome> outcomes = repository.cascadeDisable("tenant-1");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).subscriptionId()).isEqualTo("whsub_1");
    }
}
