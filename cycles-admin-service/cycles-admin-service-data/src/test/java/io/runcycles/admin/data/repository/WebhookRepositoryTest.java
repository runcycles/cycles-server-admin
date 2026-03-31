package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
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
                .tenantId("t1")
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
                .tenantId("t1")
                .url("https://example.com/hook")
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .build();

        repository.save(sub);

        verify(jedis).eval(anyString(), argThat((List<String> keys) -> {
            return keys.size() == 3
                && keys.get(0).startsWith("webhook:whsub_")
                && keys.get(1).equals("webhooks:t1")
                && keys.get(2).equals("webhooks:_all");
        }), argThat((List<String> args) -> {
            try {
                WebhookSubscription stored = objectMapper.readValue(args.get(0), WebhookSubscription.class);
                return "t1".equals(stored.getTenantId())
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
                .tenantId("t1")
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
        assertThat(result.getTenantId()).isEqualTo("t1");
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
                .tenantId("t1")
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
                .tenantId("t1")
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
                && keys.get(1).equals("webhooks:t1")
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
        when(jedis.smembers("webhooks:t1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("t1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("t1").url("https://b.com").status(WebhookStatus.PAUSED).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        String s2Json = objectMapper.writeValueAsString(s2);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);

        List<WebhookSubscription> result = repository.listByTenant("t1", null, null, null, 50);

        assertThat(result).hasSize(2);
    }

    @Test
    void listByTenant_filtersByStatus() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2"));
        when(jedis.smembers("webhooks:t1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("t1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("t1").url("https://b.com").status(WebhookStatus.PAUSED).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        String s2Json = objectMapper.writeValueAsString(s2);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);

        List<WebhookSubscription> result = repository.listByTenant("t1", "ACTIVE", null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(WebhookStatus.ACTIVE);
    }

    @Test
    void listByTenant_filtersByEventType() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2"));
        when(jedis.smembers("webhooks:t1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("t1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("t1").url("https://b.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        String s2Json = objectMapper.writeValueAsString(s2);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);

        List<WebhookSubscription> result = repository.listByTenant("t1", null, "budget.created", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubscriptionId()).isEqualTo("whsub_2");
    }

    // ---- listAll() ----

    @Test
    void listAll_usesGlobalSet() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:_all")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("t1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
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
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_matchesByEventCategory() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventCategories(List.of(EventCategory.BUDGET))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.BUDGET_FUNDED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_scopeFilterMatching() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("org/eng")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> matchResult = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "org/eng/team1");
        assertThat(matchResult).hasSize(1);

        List<WebhookSubscription> noMatchResult = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "org/sales/team1");
        assertThat(noMatchResult).isEmpty();
    }

    @Test
    void findMatchingSubscriptions_skipsPausedAndDisabled() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_paused", "whsub_disabled"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription paused = WebhookSubscription.builder()
                .subscriptionId("whsub_paused").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.PAUSED)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription disabled = WebhookSubscription.builder()
                .subscriptionId("whsub_disabled").tenantId("t1").url("https://b.com")
                .status(WebhookStatus.DISABLED)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String pausedJson = objectMapper.writeValueAsString(paused);
        String disabledJson = objectMapper.writeValueAsString(disabled);
        when(jedis.get("webhook:whsub_paused")).thenReturn(pausedJson);
        when(jedis.get("webhook:whsub_disabled")).thenReturn(disabledJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findMatchingSubscriptions_includesSystemSubscriptions() throws Exception {
        when(jedis.smembers("webhooks:t1")).thenReturn(Set.of());
        Set<String> systemIds = new LinkedHashSet<>(List.of("whsub_sys1"));
        when(jedis.smembers("webhooks:_system")).thenReturn(systemIds);

        WebhookSubscription sysSub = WebhookSubscription.builder()
                .subscriptionId("whsub_sys1").tenantId("_system").url("https://system.example.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.TENANT_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String sysJson = objectMapper.writeValueAsString(sysSub);
        when(jedis.get("webhook:whsub_sys1")).thenReturn(sysJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.TENANT_CREATED, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("_system");
    }

    @Test
    void findMatchingSubscriptions_wildcardScopeFilter_matchesAll() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("*")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "any/scope/here");

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_missingData_skipsGracefully() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_gone", "whsub_ok"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_ok").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_gone")).thenReturn(null);
        when(jedis.get("webhook:whsub_ok")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_eventTypeNotMatched_excluded() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.TENANT_CREATED, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findMatchingSubscriptions_nullScopeWithScopeFilter_matches() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("org/eng")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        // null scope should match (matchesScope returns true when scope is null)
        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findMatchingSubscriptions_blankScopeFilter_matchesAll() throws Exception {
        Set<String> tenantIds = new LinkedHashSet<>(List.of("whsub_1"));
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .eventTypes(List.of(EventType.BUDGET_CREATED))
                .scopeFilter("   ")
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.BUDGET_CREATED, "any/scope");

        assertThat(result).hasSize(1);
    }

    @Test
    void listByTenant_withCursor_skipsIdsUpToCursor() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2", "whsub_3"));
        when(jedis.smembers("webhooks:t1")).thenReturn(ids);

        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("t1").url("https://b.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s3 = WebhookSubscription.builder().subscriptionId("whsub_3").tenantId("t1").url("https://c.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s2Json = objectMapper.writeValueAsString(s2);
        String s3Json = objectMapper.writeValueAsString(s3);
        when(jedis.get("webhook:whsub_2")).thenReturn(s2Json);
        when(jedis.get("webhook:whsub_3")).thenReturn(s3Json);

        List<WebhookSubscription> result = repository.listByTenant("t1", null, null, "whsub_1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSubscriptionId()).isEqualTo("whsub_2");
    }

    @Test
    void listAll_filtersByStatusAndEventType() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2"));
        when(jedis.smembers("webhooks:_all")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("t1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        WebhookSubscription s2 = WebhookSubscription.builder().subscriptionId("whsub_2").tenantId("t1").url("https://b.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.BUDGET_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
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
        when(jedis.smembers("webhooks:t1")).thenReturn(ids);

        WebhookSubscription s = WebhookSubscription.builder().subscriptionId("whsub_ok").tenantId("t1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String sJson = objectMapper.writeValueAsString(s);
        when(jedis.get("webhook:whsub_gone")).thenReturn(null);
        when(jedis.get("webhook:whsub_ok")).thenReturn(sJson);

        List<WebhookSubscription> result = repository.listByTenant("t1", null, null, null, 50);

        assertThat(result).hasSize(1);
    }

    @Test
    void listFromSet_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("whsub_1", "whsub_2", "whsub_3"));
        when(jedis.smembers("webhooks:t1")).thenReturn(ids);

        WebhookSubscription s1 = WebhookSubscription.builder().subscriptionId("whsub_1").tenantId("t1").url("https://a.com").status(WebhookStatus.ACTIVE).eventTypes(List.of(EventType.TENANT_CREATED)).createdAt(Instant.now()).consecutiveFailures(0).build();
        String s1Json = objectMapper.writeValueAsString(s1);
        when(jedis.get("webhook:whsub_1")).thenReturn(s1Json);

        List<WebhookSubscription> result = repository.listByTenant("t1", null, null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void save_redisException_throwsRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        WebhookSubscription sub = WebhookSubscription.builder()
                .tenantId("t1")
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
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE).createdAt(Instant.now()).consecutiveFailures(0).build();

        assertThatThrownBy(() -> repository.update("whsub_1", sub))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to update webhook");
    }

    @Test
    void delete_redisException_throwsRuntimeException() throws Exception {
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
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
        when(jedis.smembers("webhooks:t1")).thenReturn(tenantIds);
        when(jedis.smembers("webhooks:_system")).thenReturn(Set.of());

        // No eventTypes and no eventCategories means match all
        WebhookSubscription sub = WebhookSubscription.builder()
                .subscriptionId("whsub_1").tenantId("t1").url("https://a.com")
                .status(WebhookStatus.ACTIVE)
                .createdAt(Instant.now()).consecutiveFailures(0).build();
        String subJson = objectMapper.writeValueAsString(sub);
        when(jedis.get("webhook:whsub_1")).thenReturn(subJson);

        List<WebhookSubscription> result = repository.findMatchingSubscriptions("t1", EventType.TENANT_CREATED, null);

        assertThat(result).hasSize(1);
    }
}
