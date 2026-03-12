package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.tenant.*;
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
class TenantRepositoryTest {

    @Mock private JedisPool jedisPool;
    @Mock private Jedis jedis;
    @Spy private ObjectMapper objectMapper = createObjectMapper();
    @InjectMocks private TenantRepository repository;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() {
        lenient().when(jedisPool.getResource()).thenReturn(jedis);
    }

    @Test
    void create_newTenant_returnsCreatedTrue() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CREATED"));

        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Test Tenant");

        var result = repository.create(request);

        assertThat(result.created()).isTrue();
        assertThat(result.tenant().getTenantId()).isEqualTo("test-tenant");
        assertThat(result.tenant().getName()).isEqualTo("Test Tenant");
        assertThat(result.tenant().getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void create_existingTenant_returnsCreatedFalse() throws Exception {
        Tenant existing = Tenant.builder()
                .tenantId("test-tenant").name("Test Tenant").status(TenantStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        String json = objectMapper.writeValueAsString(existing);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("EXISTS", json));

        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Test Tenant");

        var result = repository.create(request);

        assertThat(result.created()).isFalse();
        assertThat(result.tenant().getName()).isEqualTo("Test Tenant");
    }

    @Test
    void create_conflictingTenant_throws409() {
        Tenant existing = Tenant.builder()
                .tenantId("test-tenant").name("Original Name").status(TenantStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CONFLICT", "{}"));

        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("test-tenant");
        request.setName("Different Name");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void get_existingTenant_returnsTenant() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("t1").name("Tenant 1").status(TenantStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        String tenantJson = objectMapper.writeValueAsString(tenant);
        when(jedis.get("tenant:t1")).thenReturn(tenantJson);

        Tenant result = repository.get("t1");

        assertThat(result.getTenantId()).isEqualTo("t1");
        assertThat(result.getName()).isEqualTo("Tenant 1");
    }

    @Test
    void get_missingTenant_throwsTenantNotFound() {
        when(jedis.get("tenant:missing")).thenReturn(null);

        assertThatThrownBy(() -> repository.get("missing"))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
    }

    @Test
    void list_returnsFilteredByStatus() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("t1", "t2"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("t1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant t2 = Tenant.builder().tenantId("t2").name("B").status(TenantStatus.SUSPENDED).createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        String t2Json = objectMapper.writeValueAsString(t2);
        when(jedis.get("tenant:t1")).thenReturn(t1Json);
        when(jedis.get("tenant:t2")).thenReturn(t2Json);

        List<Tenant> result = repository.list(TenantStatus.ACTIVE, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("t1");
    }

    @Test
    void list_respectsCursorPagination() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("t1", "t2", "t3"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t2 = Tenant.builder().tenantId("t2").name("B").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant t3 = Tenant.builder().tenantId("t3").name("C").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String t2Json = objectMapper.writeValueAsString(t2);
        String t3Json = objectMapper.writeValueAsString(t3);
        when(jedis.get("tenant:t2")).thenReturn(t2Json);
        when(jedis.get("tenant:t3")).thenReturn(t3Json);

        List<Tenant> result = repository.list(null, null, "t1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTenantId()).isEqualTo("t2");
    }

    @Test
    void list_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("t1", "t2", "t3"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("t1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        when(jedis.get("tenant:t1")).thenReturn(t1Json);

        List<Tenant> result = repository.list(null, null, null, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void list_filtersByParentTenantId() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("t1", "t2"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("t1").name("A").status(TenantStatus.ACTIVE).parentTenantId("parent1").createdAt(Instant.now()).build();
        Tenant t2 = Tenant.builder().tenantId("t2").name("B").status(TenantStatus.ACTIVE).parentTenantId("parent2").createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        String t2Json = objectMapper.writeValueAsString(t2);
        when(jedis.get("tenant:t1")).thenReturn(t1Json);
        when(jedis.get("tenant:t2")).thenReturn(t2Json);

        List<Tenant> result = repository.list(null, "parent1", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("t1");
    }

    @Test
    void update_name_updatesSuccessfully() throws Exception {
        Tenant updated = Tenant.builder().tenantId("t1").name("New Name").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String updatedJson = objectMapper.writeValueAsString(updated);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("New Name");

        Tenant result = repository.update("t1", req);

        assertThat(result.getName()).isEqualTo("New Name");
    }

    @Test
    void update_statusActiveToSuspended_succeeds() throws Exception {
        Tenant updated = Tenant.builder().tenantId("t1").name("T").status(TenantStatus.SUSPENDED)
                .suspendedAt(Instant.now()).createdAt(Instant.now()).build();
        String updatedJson = objectMapper.writeValueAsString(updated);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.SUSPENDED);

        Tenant result = repository.update("t1", req);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(result.getSuspendedAt()).isNotNull();
    }

    @Test
    void update_statusActiveToClosed_succeeds() throws Exception {
        Tenant updated = Tenant.builder().tenantId("t1").name("T").status(TenantStatus.CLOSED)
                .closedAt(Instant.now()).createdAt(Instant.now()).build();
        String updatedJson = objectMapper.writeValueAsString(updated);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.CLOSED);

        Tenant result = repository.update("t1", req);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.CLOSED);
        assertThat(result.getClosedAt()).isNotNull();
    }

    @Test
    void update_statusSuspendedToActive_succeeds() throws Exception {
        Tenant updated = Tenant.builder().tenantId("t1").name("T").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String updatedJson = objectMapper.writeValueAsString(updated);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.ACTIVE);

        Tenant result = repository.update("t1", req);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void update_statusFromClosed_throwsInvalidRequest() throws Exception {
        when(jedis.eval(anyString(), anyList(), anyList()))
                .thenReturn(List.of("INVALID_TRANSITION", "Cannot transition from CLOSED"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.ACTIVE);

        assertThatThrownBy(() -> repository.update("t1", req))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getMessage()).contains("CLOSED");
                });
    }

    @Test
    void update_metadata_updatesSuccessfully() throws Exception {
        Tenant updated = Tenant.builder().tenantId("t1").name("T").status(TenantStatus.ACTIVE)
                .metadata(Map.of("env", "prod")).createdAt(Instant.now()).build();
        String updatedJson = objectMapper.writeValueAsString(updated);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setMetadata(Map.of("env", "prod"));

        Tenant result = repository.update("t1", req);

        assertThat(result.getMetadata()).containsEntry("env", "prod");
    }

    @Test
    void update_tenantNotFound_throwsTenantNotFound() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("NOT_FOUND"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("New Name");

        assertThatThrownBy(() -> repository.update("missing", req))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
    }

    @Test
    void update_invalidStatusTransition_throwsInvalidRequest() {
        when(jedis.eval(anyString(), anyList(), anyList()))
                .thenReturn(List.of("INVALID_TRANSITION", "Invalid status transition: ACTIVE -> BOGUS"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.ACTIVE);

        assertThatThrownBy(() -> repository.update("t1", req))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getHttpStatus()).isEqualTo(400);
                    assertThat(ge.getMessage()).contains("Invalid status transition");
                });
    }

    @Test
    void list_missingTenantData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("t1", "t2"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        when(jedis.get("tenant:t1")).thenReturn(null);
        Tenant t2 = Tenant.builder().tenantId("t2").name("B").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String t2Json = objectMapper.writeValueAsString(t2);
        when(jedis.get("tenant:t2")).thenReturn(t2Json);

        List<Tenant> result = repository.list(null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("t2");
    }

    @Test
    void list_emptyTenantSet_returnsEmptyList() {
        when(jedis.smembers("tenants")).thenReturn(Collections.emptySet());

        List<Tenant> result = repository.list(null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_combinedStatusAndParentFilter() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("t1", "t2", "t3"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("t1").name("A").status(TenantStatus.ACTIVE).parentTenantId("parent1").createdAt(Instant.now()).build();
        Tenant t2 = Tenant.builder().tenantId("t2").name("B").status(TenantStatus.SUSPENDED).parentTenantId("parent1").createdAt(Instant.now()).build();
        Tenant t3 = Tenant.builder().tenantId("t3").name("C").status(TenantStatus.ACTIVE).parentTenantId("parent2").createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        String t2Json = objectMapper.writeValueAsString(t2);
        String t3Json = objectMapper.writeValueAsString(t3);
        when(jedis.get("tenant:t1")).thenReturn(t1Json);
        when(jedis.get("tenant:t2")).thenReturn(t2Json);
        when(jedis.get("tenant:t3")).thenReturn(t3Json);

        List<Tenant> result = repository.list(TenantStatus.ACTIVE, "parent1", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("t1");
    }

    @Test
    void update_nameAndStatusTogether_succeeds() throws Exception {
        Tenant updated = Tenant.builder().tenantId("t1").name("New Name").status(TenantStatus.SUSPENDED)
                .suspendedAt(Instant.now()).createdAt(Instant.now()).build();
        String updatedJson = objectMapper.writeValueAsString(updated);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("OK", updatedJson));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("New Name");
        req.setStatus(TenantStatus.SUSPENDED);

        Tenant result = repository.update("t1", req);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
    }
}
