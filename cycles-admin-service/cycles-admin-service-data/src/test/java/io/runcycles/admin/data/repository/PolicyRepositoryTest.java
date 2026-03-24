package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.policy.*;
import io.runcycles.admin.model.shared.Caps;
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
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();

        String updatedJson = objectMapper.writeValueAsString(Policy.builder()
                .policyId("pol_123").tenantId("tenant1").name("Updated").scopePattern("org/*")
                .priority(10).status(PolicyStatus.ACTIVE).createdAt(Instant.now()).build());
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setName("Updated");
        request.setPriority(10);

        Policy result = repository.update("tenant1", "pol_123", request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Updated");
        assertThat(result.getPriority()).isEqualTo(10);
    }

    @Test
    void update_notFound_throws404() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("NOT_FOUND"));

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setName("New Name");

        assertThatThrownBy(() -> repository.update("tenant1", "pol_missing", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void update_forbidden_throws403() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("FORBIDDEN"));

        PolicyUpdateRequest request = new PolicyUpdateRequest();

        assertThatThrownBy(() -> repository.update("wrong-tenant", "pol_123", request))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    @Test
    void update_withStatus_passesStatusField() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();

        String updatedJson = objectMapper.writeValueAsString(Policy.builder()
                .policyId("pol_123").tenantId("tenant1").name("P").scopePattern("org/*")
                .status(PolicyStatus.DISABLED).createdAt(Instant.now()).build());
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        PolicyUpdateRequest request = new PolicyUpdateRequest();
        request.setStatus(PolicyStatus.DISABLED);

        Policy result = repository.update("tenant1", "pol_123", request);
        assertThat(result.getStatus()).isEqualTo(PolicyStatus.DISABLED);
    }
}
