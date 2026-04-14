package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.policy.*;
import io.runcycles.admin.model.shared.*;
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
class PolicyRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @InjectMocks private PolicyRepository repository;

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

    @Test
    void create_validRequest_returnsPolicy() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Rate Limit Policy");
        request.setScopePattern("org/*");
        request.setDescription("Limit API calls");

        Policy result = repository.create("tenant1", request);

        assertThat(result.getPolicyId()).startsWith("pol_");
        assertThat(result.getName()).isEqualTo("Rate Limit Policy");
        assertThat(result.getScopePattern()).isEqualTo("org/*");
        assertThat(result.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(result.getCreatedAt()).isNotNull();
        // Policy creation now uses atomic Lua script (SET + SADD in one call)
        verify(jedis).eval(anyString(), anyList(), anyList());
    }

    @Test
    void create_withCaps_setsCaps() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Cap Policy");
        request.setScopePattern("org/*");
        request.setCaps(Caps.builder().maxTokens(1000).build());

        Policy result = repository.create("tenant1", request);

        assertThat(result.getCaps().getMaxTokens()).isEqualTo(1000);
    }

    @Test
    void create_withDefaultPriority_setsZero() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Default");
        request.setScopePattern("*");

        Policy result = repository.create("tenant1", request);

        assertThat(result.getPriority()).isEqualTo(0);
    }

    @Test
    void create_withCustomPriority_usesCustom() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("High Priority");
        request.setScopePattern("*");
        request.setPriority(10);

        Policy result = repository.create("tenant1", request);

        assertThat(result.getPriority()).isEqualTo(10);
    }

    @Test
    void list_returnsAllPoliciesForTenant() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        Policy p1 = Policy.builder().policyId("pol_1").scopePattern("org/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        Policy p2 = Policy.builder().policyId("pol_2").scopePattern("team/*").status(PolicyStatus.DISABLED).createdAt(Instant.now()).build();
        String p1Json = objectMapper.writeValueAsString(p1);
        String p2Json = objectMapper.writeValueAsString(p2);
        when(jedis.get("policy:pol_1")).thenReturn(p1Json);
        when(jedis.get("policy:pol_2")).thenReturn(p2Json);

        List<Policy> result = repository.list("tenant1", null, null, null, 50);

        assertThat(result).hasSize(2);
    }

    @Test
    void list_filtersByScopePattern() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        Policy p1 = Policy.builder().policyId("pol_1").scopePattern("org/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        Policy p2 = Policy.builder().policyId("pol_2").scopePattern("team/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String p1Json = objectMapper.writeValueAsString(p1);
        String p2Json = objectMapper.writeValueAsString(p2);
        when(jedis.get("policy:pol_1")).thenReturn(p1Json);
        when(jedis.get("policy:pol_2")).thenReturn(p2Json);

        List<Policy> result = repository.list("tenant1", "org/*", null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScopePattern()).isEqualTo("org/*");
    }

    @Test
    void list_filtersByStatus() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        Policy p1 = Policy.builder().policyId("pol_1").scopePattern("org/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        Policy p2 = Policy.builder().policyId("pol_2").scopePattern("team/*").status(PolicyStatus.DISABLED).createdAt(Instant.now()).build();
        String p1Json = objectMapper.writeValueAsString(p1);
        String p2Json = objectMapper.writeValueAsString(p2);
        when(jedis.get("policy:pol_1")).thenReturn(p1Json);
        when(jedis.get("policy:pol_2")).thenReturn(p2Json);

        List<Policy> result = repository.list("tenant1", null, PolicyStatus.ACTIVE, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPolicyId()).isEqualTo("pol_1");
    }

    @Test
    void list_respectsCursorPagination() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2", "pol_3"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        Policy p2 = Policy.builder().policyId("pol_2").scopePattern("a").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        Policy p3 = Policy.builder().policyId("pol_3").scopePattern("b").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String p2Json = objectMapper.writeValueAsString(p2);
        String p3Json = objectMapper.writeValueAsString(p3);
        when(jedis.get("policy:pol_2")).thenReturn(p2Json);
        when(jedis.get("policy:pol_3")).thenReturn(p3Json);

        List<Policy> result = repository.list("tenant1", null, null, "pol_1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPolicyId()).isEqualTo("pol_2");
    }

    @Test
    void list_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        Policy p1 = Policy.builder().policyId("pol_1").scopePattern("a").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String p1Json = objectMapper.writeValueAsString(p1);
        when(jedis.get("policy:pol_1")).thenReturn(p1Json);

        List<Policy> result = repository.list("tenant1", null, null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_missingPolicyData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        when(jedis.get("policy:pol_1")).thenReturn(null);
        Policy p2 = Policy.builder().policyId("pol_2").scopePattern("org/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String p2Json = objectMapper.writeValueAsString(p2);
        when(jedis.get("policy:pol_2")).thenReturn(p2Json);

        List<Policy> result = repository.list("tenant1", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPolicyId()).isEqualTo("pol_2");
    }

    @Test
    void list_emptyPolicySet_returnsEmptyList() {
        when(jedis.smembers("policies:tenant1")).thenReturn(Collections.emptySet());

        List<Policy> result = repository.list("tenant1", null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_combinedScopePatternAndStatusFilter() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2", "pol_3"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        Policy p1 = Policy.builder().policyId("pol_1").scopePattern("org/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        Policy p2 = Policy.builder().policyId("pol_2").scopePattern("org/*").status(PolicyStatus.DISABLED).createdAt(Instant.now()).build();
        Policy p3 = Policy.builder().policyId("pol_3").scopePattern("team/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String p1Json = objectMapper.writeValueAsString(p1);
        String p2Json = objectMapper.writeValueAsString(p2);
        String p3Json = objectMapper.writeValueAsString(p3);
        when(jedis.get("policy:pol_1")).thenReturn(p1Json);
        when(jedis.get("policy:pol_2")).thenReturn(p2Json);
        when(jedis.get("policy:pol_3")).thenReturn(p3Json);

        List<Policy> result = repository.list("tenant1", "org/*", PolicyStatus.ACTIVE, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPolicyId()).isEqualTo("pol_1");
    }

    @Test
    void create_tenantNotFound_throwsTenantNotFound() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-1L);

        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Test Policy");
        request.setScopePattern("org/*");

        assertThatThrownBy(() -> repository.create("missing-tenant", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void create_tenantNotActive_throwsInvalidRequest() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(-2L);

        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Test Policy");
        request.setScopePattern("org/*");

        assertThatThrownBy(() -> repository.create("suspended-tenant", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void create_genericException_wrappedInRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Test Policy");
        request.setScopePattern("org/*");

        assertThatThrownBy(() -> repository.create("tenant1", request))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void list_deserializationFailure_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("pol_1", "pol_2"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);

        when(jedis.get("policy:pol_1")).thenReturn("{invalid json}");
        Policy p2 = Policy.builder().policyId("pol_2").scopePattern("org/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String p2Json = objectMapper.writeValueAsString(p2);
        when(jedis.get("policy:pol_2")).thenReturn(p2Json);

        List<Policy> result = repository.list("tenant1", null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPolicyId()).isEqualTo("pol_2");
    }

    // ========== update() tests ==========

    @Test
    void update_success_returnsUpdatedPolicy() throws Exception {
        // tenantId = null simulates the admin-auth path (v0.1.25.14 dual-auth),
        // which is the only path that doesn't short-circuit on the ownership
        // check — Policy.tenantId is @JsonIgnore so the persisted JSON never
        // carries it, matching the prior Lua behavior where tenant-auth always
        // tripped FORBIDDEN against a nil stored tenant_id.
        Policy stored = Policy.builder()
                .policyId("pol_123").tenantId("tenant1").name("Old").scopePattern("org/*")
                .priority(1).status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String storedJson = objectMapper.writeValueAsString(stored);
        when(jedis.get("policy:pol_123")).thenReturn(storedJson);

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setName("Updated");
        request.setPriority(10);

        Policy result = repository.update(null, "pol_123", request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Updated");
        assertThat(result.getPriority()).isEqualTo(10);
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(jedis).set(eq("policy:pol_123"), anyString());
    }

    @Test
    void update_notFound_throws404() {
        when(jedis.get("policy:pol_missing")).thenReturn(null);

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setName("New Name");

        assertThatThrownBy(() -> repository.update("tenant1", "pol_missing", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void update_forbidden_throws403() throws Exception {
        Policy stored = Policy.builder()
                .policyId("pol_123").tenantId("tenant1").scopePattern("org/*")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String storedJson = objectMapper.writeValueAsString(stored);
        when(jedis.get("policy:pol_123")).thenReturn(storedJson);

        PolicyUpdateRequest request = new PolicyUpdateRequest();

        assertThatThrownBy(() -> repository.update("wrong-tenant", "pol_123", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void update_withNullTenantId_skipsOwnershipCheck() throws Exception {
        Policy stored = Policy.builder()
                .policyId("pol_123").tenantId("tenant1").name("Old").scopePattern("org/*")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String storedJson = objectMapper.writeValueAsString(stored);
        when(jedis.get("policy:pol_123")).thenReturn(storedJson);

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setName("P");

        Policy result = repository.update(null, "pol_123", request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("P");
        verify(jedis).set(eq("policy:pol_123"), anyString());
    }

    @Test
    void update_withAllFields_appliesAllUpdates() throws Exception {
        Policy stored = Policy.builder()
                .policyId("pol_123").tenantId("tenant1").name("Old").scopePattern("org/*")
                .priority(1).status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String storedJson = objectMapper.writeValueAsString(stored);
        when(jedis.get("policy:pol_123")).thenReturn(storedJson);

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setName("New Name");
        request.setDescription("New desc");
        request.setPriority(5);
        request.setCaps(Caps.builder().maxTokens(500).build());
        request.setCommitOveragePolicy(CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);
        request.setReservationTtlOverride(ReservationTtlOverride.builder().defaultTtlMs(5000).build());
        request.setRateLimits(RateLimits.builder().maxReservationsPerMinute(100).build());
        request.setEffectiveFrom(Instant.parse("2025-01-01T00:00:00Z"));
        request.setEffectiveUntil(Instant.parse("2026-01-01T00:00:00Z"));
        request.setStatus(PolicyStatus.DISABLED);

        Policy result = repository.update(null, "pol_123", request);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getDescription()).isEqualTo("New desc");
        assertThat(result.getPriority()).isEqualTo(5);
        assertThat(result.getCaps().getMaxTokens()).isEqualTo(500);
        assertThat(result.getCommitOveragePolicy()).isEqualTo(CommitOveragePolicy.ALLOW_WITH_OVERDRAFT);
        assertThat(result.getStatus()).isEqualTo(PolicyStatus.DISABLED);
        verify(jedis).set(eq("policy:pol_123"), argThat((String s) ->
            s.contains("New Name") && s.contains("DISABLED")));
    }

    @Test
    void update_emptyToolAllowlistRoundtripsAsArray_notObject() throws Exception {
        // Regression guard for runcycles/cycles-dashboard#43 on PolicyRepository:
        // empty Caps.toolAllowlist must persist as [] (not {}). The old Lua
        // cjson round-trip corrupted this to {} and broke subsequent reads.
        Policy stored = Policy.builder()
                .policyId("pol_123").tenantId("tenant1").scopePattern("org/*")
                .status(PolicyStatus.ACTIVE)
                .caps(Caps.builder()
                        .maxTokens(500)
                        .toolAllowlist(java.util.Collections.emptyList())
                        .toolDenylist(java.util.Collections.emptyList())
                        .build())
                .createdAt(Instant.now()).build();
        String storedJson = objectMapper.writeValueAsString(stored);
        when(jedis.get("policy:pol_123")).thenReturn(storedJson);

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setPriority(7);

        repository.update(null, "pol_123", request);

        verify(jedis).set(eq("policy:pol_123"), argThat((String s) ->
            s.contains("\"tool_allowlist\":[]")
                && s.contains("\"tool_denylist\":[]")
                && !s.contains("\"tool_allowlist\":{}")
                && !s.contains("\"tool_denylist\":{}")));
    }

    @Test
    void list_legacyCorruptedCapsArrays_stillReadable() throws Exception {
        // Pre-v0.1.25.17 records updated via the old Lua path had Caps
        // tool_allowlist / tool_denylist rewritten as {} instead of [].
        // Lenient deserializer on Caps must accept those so list() doesn't
        // drop the policy silently.
        Set<String> ids = new LinkedHashSet<>(List.of("pol_corrupt"));
        when(jedis.smembers("policies:tenant1")).thenReturn(ids);
        String corruptedJson = "{"
                + "\"policy_id\":\"pol_corrupt\","
                + "\"scope_pattern\":\"org/*\","
                + "\"status\":\"ACTIVE\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"caps\":{"
                + "  \"max_tokens\":100,"
                + "  \"tool_allowlist\":{},"
                + "  \"tool_denylist\":{}"
                + "}"
                + "}";
        when(jedis.get("policy:pol_corrupt")).thenReturn(corruptedJson);

        List<Policy> result = repository.list("tenant1", null, null, null, 1000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPolicyId()).isEqualTo("pol_corrupt");
        assertThat(result.get(0).getCaps().getToolAllowlist()).isEmpty();
        assertThat(result.get(0).getCaps().getToolDenylist()).isEmpty();
    }

    @Test
    void update_genericException_wrappedInRuntimeException() {
        when(jedis.get("policy:pol_123")).thenThrow(new RuntimeException("Redis down"));

        PolicyUpdateRequest request = new PolicyUpdateRequest();

        assertThatThrownBy(() -> repository.update("tenant1", "pol_123", request))
                .isInstanceOf(RuntimeException.class);
    }

    // ========== getScopePattern() tests ==========

    @Test
    void getScopePattern_success_returnsScopePattern() throws Exception {
        Policy p = Policy.builder().policyId("pol_1").scopePattern("org/*").status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String pJson = objectMapper.writeValueAsString(p);
        when(jedis.get("policy:pol_1")).thenReturn(pJson);

        String result = repository.getScopePattern("pol_1");

        assertThat(result).isEqualTo("org/*");
    }

    @Test
    void getScopePattern_notFound_throwsGovernanceException() {
        when(jedis.get("policy:pol_missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.getScopePattern("pol_missing"))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void getScopePattern_redisException_throwsRuntimeException() {
        when(jedis.get("policy:pol_err")).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.getScopePattern("pol_err"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void create_withAllOptionalFields() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Full Policy");
        request.setScopePattern("org/eng/*");
        request.setDescription("Full description");
        request.setPriority(5);
        request.setCaps(Caps.builder().maxTokens(1000).build());
        request.setCommitOveragePolicy(CommitOveragePolicy.ALLOW_IF_AVAILABLE);
        request.setReservationTtlOverride(ReservationTtlOverride.builder().defaultTtlMs(5000).build());
        request.setRateLimits(RateLimits.builder().maxReservationsPerMinute(100).build());
        request.setEffectiveFrom(Instant.parse("2025-01-01T00:00:00Z"));
        request.setEffectiveUntil(Instant.parse("2026-01-01T00:00:00Z"));

        Policy result = repository.create("tenant1", request);

        assertThat(result.getDescription()).isEqualTo("Full description");
        assertThat(result.getCommitOveragePolicy()).isEqualTo(CommitOveragePolicy.ALLOW_IF_AVAILABLE);
        assertThat(result.getReservationTtlOverride().getDefaultTtlMs()).isEqualTo(5000);
        assertThat(result.getRateLimits().getMaxReservationsPerMinute()).isEqualTo(100);
        assertThat(result.getEffectiveFrom()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(result.getEffectiveUntil()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void update_withStatus_passesStatusField() throws Exception {
        Policy stored = Policy.builder()
                .policyId("pol_123").tenantId("tenant1").name("P").scopePattern("org/*")
                .status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build();
        String storedJson = objectMapper.writeValueAsString(stored);
        when(jedis.get("policy:pol_123")).thenReturn(storedJson);

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setStatus(PolicyStatus.DISABLED);

        Policy result = repository.update(null, "pol_123", request);
        assertThat(result.getStatus()).isEqualTo(PolicyStatus.DISABLED);
    }
}
