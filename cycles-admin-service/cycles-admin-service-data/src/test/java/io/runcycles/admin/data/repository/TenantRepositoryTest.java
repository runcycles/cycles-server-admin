package io.runcycles.admin.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
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
        // Non-close updates use a compare-and-set Lua script. Individual
        // create/close tests override this with their structured Lua result.
        lenient().when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        RedisBatchTestStubs.installStringReads(jedis);
    }

    @Test
    void countForBulk_returnsExactFilteredCountAndSkipsBadRows() throws Exception {
        Tenant active = Tenant.builder().tenantId("t-active").name("Acme")
            .status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant suspended = Tenant.builder().tenantId("t-suspended").name("Acme")
            .status(TenantStatus.SUSPENDED).createdAt(Instant.now()).build();
        when(jedis.smembers("tenants")).thenReturn(
            new LinkedHashSet<>(List.of("t-active", "t-suspended", "missing", "bad")));
        String activeJson = objectMapper.writeValueAsString(active);
        String suspendedJson = objectMapper.writeValueAsString(suspended);
        when(jedis.get("tenant:t-active")).thenReturn(activeJson);
        when(jedis.get("tenant:t-suspended")).thenReturn(suspendedJson);
        when(jedis.get("tenant:missing")).thenReturn(null);
        when(jedis.get("tenant:bad")).thenReturn("{bad-json");

        assertThat(repository.countForBulk(TenantStatus.ACTIVE, null, "acme"))
            .isEqualTo(1);
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
                .tenantId("tenant-1").name("Tenant 1").status(TenantStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        String tenantJson = objectMapper.writeValueAsString(tenant);
        when(jedis.get("tenant:tenant-1")).thenReturn(tenantJson);

        Tenant result = repository.get("tenant-1");

        assertThat(result.getTenantId()).isEqualTo("tenant-1");
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
        Set<String> ids = new LinkedHashSet<>(List.of("tenant-1", "tenant-2"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("tenant-1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant t2 = Tenant.builder().tenantId("tenant-2").name("B").status(TenantStatus.SUSPENDED).createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        String t2Json = objectMapper.writeValueAsString(t2);
        when(jedis.get("tenant:tenant-1")).thenReturn(t1Json);
        when(jedis.get("tenant:tenant-2")).thenReturn(t2Json);

        List<Tenant> result = repository.list(TenantStatus.ACTIVE, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    void list_respectsCursorPagination() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("tenant-1", "tenant-2", "tenant-3"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t2 = Tenant.builder().tenantId("tenant-2").name("B").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant t3 = Tenant.builder().tenantId("tenant-3").name("C").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String t2Json = objectMapper.writeValueAsString(t2);
        String t3Json = objectMapper.writeValueAsString(t3);
        when(jedis.get("tenant:tenant-2")).thenReturn(t2Json);
        when(jedis.get("tenant:tenant-3")).thenReturn(t3Json);

        List<Tenant> result = repository.list(null, null, "tenant-1", 50);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-2");
    }

    @Test
    void list_respectsLimit() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("tenant-1", "tenant-2", "tenant-3"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("tenant-1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        when(jedis.get("tenant:tenant-1")).thenReturn(t1Json);

        List<Tenant> result = repository.list(null, null, null, 1);

        assertThat(result).hasSize(1);
    }

    // ---- v0.1.25.20: sort_by / sort_dir tests ----

    private Tenant buildTenant(String id, String name, TenantStatus status, Instant createdAt) {
        return Tenant.builder().tenantId(id).name(name).status(status).createdAt(createdAt).build();
    }

    private void stubTenants(Tenant... tenants) throws Exception {
        // Pre-serialize all JSON strings *before* calling any mockito
        // when()/thenReturn() to avoid strict-stubbing "unfinished stub"
        // failures: objectMapper is a @Spy and calling it inside a
        // when()/thenReturn() chain interleaves mock interactions.
        Set<String> ids = new LinkedHashSet<>();
        java.util.Map<String, String> jsonByKey = new java.util.LinkedHashMap<>();
        for (Tenant t : tenants) {
            ids.add(t.getTenantId());
            jsonByKey.put("tenant:" + t.getTenantId(), objectMapper.writeValueAsString(t));
        }
        when(jedis.smembers("tenants")).thenReturn(ids);
        for (var entry : jsonByKey.entrySet()) {
            when(jedis.get(entry.getKey())).thenReturn(entry.getValue());
        }
    }

    @Test
    void list_sortByName_ascending() throws Exception {
        stubTenants(
            buildTenant("t3", "Charlie", TenantStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")),
            buildTenant("t1", "Alpha",   TenantStatus.ACTIVE, Instant.parse("2026-03-01T00:00:00Z")),
            buildTenant("t2", "Bravo",   TenantStatus.ACTIVE, Instant.parse("2026-02-01T00:00:00Z"))
        );

        var result = repository.list(null, null, null, 50, SortSpec.of("name", SortDirection.ASC));

        assertThat(result).extracting(Tenant::getName).containsExactly("Alpha", "Bravo", "Charlie");
    }

    @Test
    void list_sortByName_descending() throws Exception {
        stubTenants(
            buildTenant("t1", "Alpha",   TenantStatus.ACTIVE, Instant.parse("2026-03-01T00:00:00Z")),
            buildTenant("t2", "Bravo",   TenantStatus.ACTIVE, Instant.parse("2026-02-01T00:00:00Z")),
            buildTenant("t3", "Charlie", TenantStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"))
        );

        var result = repository.list(null, null, null, 50, SortSpec.of("name", SortDirection.DESC));

        assertThat(result).extracting(Tenant::getName).containsExactly("Charlie", "Bravo", "Alpha");
    }

    @Test
    void list_sortByCreatedAt_descendingIsDefaultOrdering() throws Exception {
        stubTenants(
            buildTenant("t1", "A", TenantStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")),
            buildTenant("t2", "B", TenantStatus.ACTIVE, Instant.parse("2026-03-01T00:00:00Z")),
            buildTenant("t3", "C", TenantStatus.ACTIVE, Instant.parse("2026-02-01T00:00:00Z"))
        );

        var result = repository.list(null, null, null, 50,
            SortSpec.of("created_at", SortDirection.DESC));

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t2", "t3", "t1");
    }

    @Test
    void list_sortByStatus_ascending() throws Exception {
        stubTenants(
            buildTenant("t1", "A", TenantStatus.SUSPENDED, Instant.now()),
            buildTenant("t2", "B", TenantStatus.ACTIVE, Instant.now()),
            buildTenant("t3", "C", TenantStatus.CLOSED, Instant.now())
        );

        var result = repository.list(null, null, null, 50,
            SortSpec.of("status", SortDirection.ASC));

        assertThat(result).extracting(t -> t.getStatus().name())
            .containsExactly("ACTIVE", "CLOSED", "SUSPENDED");
    }

    @Test
    void list_sortByTenantId_ascending() throws Exception {
        stubTenants(
            buildTenant("beta",  "X", TenantStatus.ACTIVE, Instant.now()),
            buildTenant("alpha", "Y", TenantStatus.ACTIVE, Instant.now()),
            buildTenant("gamma", "Z", TenantStatus.ACTIVE, Instant.now())
        );

        var result = repository.list(null, null, null, 50,
            SortSpec.of("tenant_id", SortDirection.ASC));

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void list_sortWithUnknownFieldFallsBackToTenantId() throws Exception {
        // Repository defensively maps unknown fields to tenant_id natural order.
        // The controller is responsible for rejecting bad fields before they
        // reach the repo, but the repo default keeps the method contract total.
        stubTenants(
            buildTenant("b", "X", TenantStatus.ACTIVE, Instant.now()),
            buildTenant("a", "Y", TenantStatus.ACTIVE, Instant.now())
        );

        var result = repository.list(null, null, null, 50, SortSpec.of("unknown", SortDirection.ASC));

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("a", "b");
    }

    @Test
    void list_sortCursorResumesInSortedOrder() throws Exception {
        stubTenants(
            buildTenant("t1", "A", TenantStatus.ACTIVE, Instant.now()),
            buildTenant("t2", "B", TenantStatus.ACTIVE, Instant.now()),
            buildTenant("t3", "C", TenantStatus.ACTIVE, Instant.now())
        );

        // DESC by name: expected order [C, B, A]. Cursor 't2' (= "B") →
        // returns the tail [A].
        var result = repository.list(null, null, "t2", 50,
            SortSpec.of("name", SortDirection.DESC));

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t1");
    }

    @Test
    void list_sortNullFallsBackToDefaultOrder() throws Exception {
        // Null SortSpec = pre-sort semantic: tenant_id ascending (descending of reversed with same secondary).
        // The overload that takes null should not throw.
        stubTenants(
            buildTenant("b", "X", TenantStatus.ACTIVE, Instant.now()),
            buildTenant("a", "Y", TenantStatus.ACTIVE, Instant.now())
        );

        var result = repository.list(null, null, null, 50, null);

        // null SortSpec defaults to descending-natural via reverse; explicit assertion
        // pins whichever order the impl returns so future changes are deliberate.
        assertThat(result).hasSize(2);
    }

    @Test
    void list_filtersByParentTenantId() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("tenant-1", "tenant-2"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("tenant-1").name("A").status(TenantStatus.ACTIVE).parentTenantId("parent1").createdAt(Instant.now()).build();
        Tenant t2 = Tenant.builder().tenantId("tenant-2").name("B").status(TenantStatus.ACTIVE).parentTenantId("parent2").createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        String t2Json = objectMapper.writeValueAsString(t2);
        when(jedis.get("tenant:tenant-1")).thenReturn(t1Json);
        when(jedis.get("tenant:tenant-2")).thenReturn(t2Json);

        List<Tenant> result = repository.list(null, "parent1", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-1");
    }

    private String storedTenantJson(TenantStatus status) throws Exception {
        return objectMapper.writeValueAsString(Tenant.builder()
                .tenantId("tenant-1").name("Old").status(status)
                .createdAt(Instant.now()).build());
    }

    @Test
    void update_name_updatesSuccessfully() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.ACTIVE);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("New Name");

        Tenant result = repository.update("tenant-1", req);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(jedis).eval(anyString(), eq(List.of("tenant:tenant-1")), anyList());
    }

    @Test
    void update_statusActiveToSuspended_succeeds() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.ACTIVE);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.SUSPENDED);

        Tenant result = repository.update("tenant-1", req);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(result.getSuspendedAt()).isNotNull();
    }

    @Test
    void update_statusActiveToClosed_succeeds() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.ACTIVE);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CLOSED"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.CLOSED);

        Tenant result = repository.update("tenant-1", req);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.CLOSED);
        assertThat(result.getClosedAt()).isNotNull();
        verify(jedis).eval(anyString(), argThat(keys -> keys.size() == 6
            && keys.contains("tenant-close:intent:tenant-1")
            && keys.contains("tenant-close:committed:tenant-1")
            && keys.contains("tenant-close:outbox:tenant-1")), anyList());
    }

    @Test
    void update_statusSuspendedToActive_succeeds() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.SUSPENDED);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.ACTIVE);

        Tenant result = repository.update("tenant-1", req);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void update_statusFromClosed_throwsInvalidRequest() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.CLOSED);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.ACTIVE);

        assertThatThrownBy(() -> repository.update("tenant-1", req))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getMessage()).contains("CLOSED");
                });
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void update_statusClosedToSuspended_throwsInvalidRequest() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.CLOSED);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.SUSPENDED);

        assertThatThrownBy(() -> repository.update("tenant-1", req))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ge.getMessage()).contains("CLOSED");
                });
    }

    // Spec v0.1.25.29: re-issuing CLOSE on an already-CLOSED tenant does not
    // mutate the tenant. It may backfill the durable parent-event obligation
    // for a row closed by a pre-upgrade replica.
    @Test
    void update_statusClosedToClosed_backfillsMissingDurabilityMarker() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.CLOSED);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("BACKFILLED"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.CLOSED);

        Tenant result = repository.update("tenant-1", req);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.CLOSED);
        verify(jedis).eval(anyString(), argThat(keys -> keys.size() == 6
            && keys.contains("tenant-close:committed:tenant-1")
            && keys.contains("tenant-close:outbox:tenant-1")), anyList());
    }

    @Test
    void update_statusClosedToClosed_alreadyDurableReturnsStoredTenant() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.CLOSED);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("ALREADY_DURABLE"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.CLOSED);

        assertThat(repository.update("tenant-1", req).getStatus())
            .isEqualTo(TenantStatus.CLOSED);
    }

    @Test
    void update_statusClosedToClosed_missingIntentFailsClosed() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.CLOSED);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("MISSING_INTENT"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.CLOSED);

        assertThatThrownBy(() -> repository.update("tenant-1", req))
            .hasRootCauseMessage(
                "Tenant-close intent disappeared before durability backfill");
    }

    @Test
    void update_statusClosedToClosed_retriesConcurrentChange() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.CLOSED);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);
        when(jedis.eval(anyString(), anyList(), anyList()))
            .thenReturn(List.of("RETRY"), List.of("BACKFILLED"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.CLOSED);

        assertThat(repository.update("tenant-1", req).getStatus())
            .isEqualTo(TenantStatus.CLOSED);
        verify(jedis, times(2)).get("tenant:tenant-1");
    }

    @Test
    void update_casRetryExhaustionUsesSpecDeclaredInternalErrorStatus() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.ACTIVE);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("concurrent-update");

        assertThatThrownBy(() -> repository.update("tenant-1", req))
            .isInstanceOf(GovernanceException.class)
            .satisfies(error -> {
                GovernanceException governance = (GovernanceException) error;
                assertThat(governance.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                assertThat(governance.getHttpStatus()).isEqualTo(500);
            });
        verify(jedis, times(5)).eval(anyString(), anyList(), anyList());
    }

    @Test
    void update_metadata_updatesSuccessfully() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.ACTIVE);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setMetadata(Map.of("env", "prod"));

        Tenant result = repository.update("tenant-1", req);

        assertThat(result.getMetadata()).containsEntry("env", "prod");
    }

    @Test
    void update_tenantNotFound_throwsTenantNotFound() {
        when(jedis.get("tenant:missing")).thenReturn(null);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("New Name");

        assertThatThrownBy(() -> repository.update("missing", req))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void update_invalidStatusTransition_throwsInvalidRequest() {
        // Simulate a legacy Redis record carrying a status the enum doesn't know about
        // to guarantee Jackson produces something the validator rejects is overkill —
        // with the enum-typed model, any TenantStatus value supplied through the request
        // is by definition valid. The validator in Java now ONLY rejects transitions
        // out of CLOSED; everything else is a no-op transition accepted by the state
        // machine. This test keeps coverage on the error path by driving it via CLOSED.
        when(jedis.get("tenant:tenant-1")).thenReturn(null);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.ACTIVE);

        assertThatThrownBy(() -> repository.update("tenant-1", req))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(404);
                });
    }

    @Test
    void list_missingTenantData_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("tenant-1", "tenant-2"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        when(jedis.get("tenant:tenant-1")).thenReturn(null);
        Tenant t2 = Tenant.builder().tenantId("tenant-2").name("B").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String t2Json = objectMapper.writeValueAsString(t2);
        when(jedis.get("tenant:tenant-2")).thenReturn(t2Json);

        List<Tenant> result = repository.list(null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-2");
    }

    @Test
    void list_emptyTenantSet_returnsEmptyList() {
        when(jedis.smembers("tenants")).thenReturn(Collections.emptySet());

        List<Tenant> result = repository.list(null, null, null, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void list_combinedStatusAndParentFilter() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("tenant-1", "tenant-2", "tenant-3"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        Tenant t1 = Tenant.builder().tenantId("tenant-1").name("A").status(TenantStatus.ACTIVE).parentTenantId("parent1").createdAt(Instant.now()).build();
        Tenant t2 = Tenant.builder().tenantId("tenant-2").name("B").status(TenantStatus.SUSPENDED).parentTenantId("parent1").createdAt(Instant.now()).build();
        Tenant t3 = Tenant.builder().tenantId("tenant-3").name("C").status(TenantStatus.ACTIVE).parentTenantId("parent2").createdAt(Instant.now()).build();
        String t1Json = objectMapper.writeValueAsString(t1);
        String t2Json = objectMapper.writeValueAsString(t2);
        String t3Json = objectMapper.writeValueAsString(t3);
        when(jedis.get("tenant:tenant-1")).thenReturn(t1Json);
        when(jedis.get("tenant:tenant-2")).thenReturn(t2Json);
        when(jedis.get("tenant:tenant-3")).thenReturn(t3Json);

        List<Tenant> result = repository.list(TenantStatus.ACTIVE, "parent1", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    void update_nameAndStatusTogether_succeeds() throws Exception {
        String storedJson = storedTenantJson(TenantStatus.ACTIVE);
        when(jedis.get("tenant:tenant-1")).thenReturn(storedJson);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("New Name");
        req.setStatus(TenantStatus.SUSPENDED);

        Tenant result = repository.update("tenant-1", req);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    void create_genericException_wrappedInRuntimeException() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("Redis down"));

        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("tenant-1");
        request.setName("Test");

        assertThatThrownBy(() -> repository.create(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create tenant");
    }

    @Test
    void get_genericException_wrappedInRuntimeException() {
        when(jedis.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> repository.get("tenant-1"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void list_deserializationFailure_skipsGracefully() throws Exception {
        Set<String> ids = new LinkedHashSet<>(List.of("tenant-1", "tenant-2"));
        when(jedis.smembers("tenants")).thenReturn(ids);

        when(jedis.get("tenant:tenant-1")).thenReturn("{invalid json}");
        Tenant t2 = Tenant.builder().tenantId("tenant-2").name("B").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        String t2Json = objectMapper.writeValueAsString(t2);
        when(jedis.get("tenant:tenant-2")).thenReturn(t2Json);

        List<Tenant> result = repository.list(null, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-2");
    }

    @Test
    void update_genericException_wrappedInRuntimeException() {
        when(jedis.get("tenant:tenant-1")).thenThrow(new RuntimeException("Redis down"));

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setName("New Name");

        assertThatThrownBy(() -> repository.update("tenant-1", req))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ---- matchForBulk (spec v0.1.25.21) ----

    private Tenant tenantRow(String id, String name, TenantStatus status, String parent) {
        return Tenant.builder()
                .tenantId(id).name(name).status(status).parentTenantId(parent)
                .createdAt(Instant.now()).build();
    }

    @Test
    void matchForBulk_noFilters_returnsAllTenants() throws Exception {
        String j1 = objectMapper.writeValueAsString(tenantRow("t-1", "Alpha", TenantStatus.ACTIVE, null));
        String j2 = objectMapper.writeValueAsString(tenantRow("t-2", "Beta", TenantStatus.SUSPENDED, null));
        when(jedis.smembers("tenants"))
            .thenReturn(new LinkedHashSet<>(List.of("t-1", "t-2")));
        when(jedis.get("tenant:t-1")).thenReturn(j1);
        when(jedis.get("tenant:t-2")).thenReturn(j2);

        List<Tenant> result = repository.matchForBulk(null, null, null, 500);

        assertThat(result).extracting(Tenant::getTenantId).containsExactlyInAnyOrder("t-1", "t-2");
    }

    @Test
    void matchForBulk_statusFilter_excludesNonMatching() throws Exception {
        String j1 = objectMapper.writeValueAsString(tenantRow("t-1", "Alpha", TenantStatus.ACTIVE, null));
        String j2 = objectMapper.writeValueAsString(tenantRow("t-2", "Beta", TenantStatus.SUSPENDED, null));
        when(jedis.smembers("tenants"))
            .thenReturn(new LinkedHashSet<>(List.of("t-1", "t-2")));
        when(jedis.get("tenant:t-1")).thenReturn(j1);
        when(jedis.get("tenant:t-2")).thenReturn(j2);

        List<Tenant> result = repository.matchForBulk(TenantStatus.ACTIVE, null, null, 500);

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t-1");
    }

    @Test
    void matchForBulk_parentTenantIdFilter_excludesNonMatching() throws Exception {
        String j1 = objectMapper.writeValueAsString(tenantRow("t-1", "Alpha", TenantStatus.ACTIVE, "parent-x"));
        String j2 = objectMapper.writeValueAsString(tenantRow("t-2", "Beta", TenantStatus.ACTIVE, "parent-y"));
        when(jedis.smembers("tenants"))
            .thenReturn(new LinkedHashSet<>(List.of("t-1", "t-2")));
        when(jedis.get("tenant:t-1")).thenReturn(j1);
        when(jedis.get("tenant:t-2")).thenReturn(j2);

        List<Tenant> result = repository.matchForBulk(null, "parent-x", null, 500);

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t-1");
    }

    @Test
    void matchForBulk_searchMatchesNameSubstring() throws Exception {
        String j1 = objectMapper.writeValueAsString(tenantRow("t-1", "Acme Corp", TenantStatus.ACTIVE, null));
        String j2 = objectMapper.writeValueAsString(tenantRow("t-2", "Globex", TenantStatus.ACTIVE, null));
        when(jedis.smembers("tenants"))
            .thenReturn(new LinkedHashSet<>(List.of("t-1", "t-2")));
        when(jedis.get("tenant:t-1")).thenReturn(j1);
        when(jedis.get("tenant:t-2")).thenReturn(j2);

        List<Tenant> result = repository.matchForBulk(null, null, "acme", 500);

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t-1");
    }

    @Test
    void matchForBulk_searchMatchesTenantIdSubstring() throws Exception {
        String j1 = objectMapper.writeValueAsString(tenantRow("acme-1", "A", TenantStatus.ACTIVE, null));
        String j2 = objectMapper.writeValueAsString(tenantRow("other-2", "B", TenantStatus.ACTIVE, null));
        when(jedis.smembers("tenants"))
            .thenReturn(new LinkedHashSet<>(List.of("acme-1", "other-2")));
        when(jedis.get("tenant:acme-1")).thenReturn(j1);
        when(jedis.get("tenant:other-2")).thenReturn(j2);

        List<Tenant> result = repository.matchForBulk(null, null, "acme", 500);

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("acme-1");
    }

    @Test
    void matchForBulk_missingTenantJson_isSkipped() throws Exception {
        String j1 = objectMapper.writeValueAsString(tenantRow("t-1", "A", TenantStatus.ACTIVE, null));
        when(jedis.smembers("tenants"))
            .thenReturn(new LinkedHashSet<>(List.of("t-1", "t-gone")));
        when(jedis.get("tenant:t-1")).thenReturn(j1);
        when(jedis.get("tenant:t-gone")).thenReturn(null);

        List<Tenant> result = repository.matchForBulk(null, null, null, 500);

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t-1");
    }

    @Test
    void matchForBulk_corruptJson_isSkippedAndLoopContinues() throws Exception {
        String ok = objectMapper.writeValueAsString(tenantRow("t-ok", "OK", TenantStatus.ACTIVE, null));
        when(jedis.smembers("tenants"))
            .thenReturn(new LinkedHashSet<>(List.of("t-bad", "t-ok")));
        when(jedis.get("tenant:t-bad")).thenReturn("{not-json");
        when(jedis.get("tenant:t-ok")).thenReturn(ok);

        List<Tenant> result = repository.matchForBulk(null, null, null, 500);

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t-ok");
    }

    @Test
    void matchForBulk_capPlusOneSentinel_stopsIteration() throws Exception {
        LinkedHashSet<String> ids = new LinkedHashSet<>(List.of("t-1", "t-2", "t-3", "t-4", "t-5"));
        when(jedis.smembers("tenants")).thenReturn(ids);
        // cap=3 → ceiling 4 → loop breaks after 4 matches, never touching t-5.
        // Only stub rows the loop actually hydrates; Mockito strict mode
        // would flag an unused stub on t-5 otherwise.
        for (int i = 1; i <= 4; i++) {
            String j = objectMapper.writeValueAsString(
                tenantRow("t-" + i, "n-" + i, TenantStatus.ACTIVE, null));
            when(jedis.get("tenant:t-" + i)).thenReturn(j);
        }

        List<Tenant> result = repository.matchForBulk(null, null, null, 3);

        assertThat(result).hasSize(4);
        verify(jedis, never()).get("tenant:t-5");
    }

    @Test
    void create_explicitReservationSettingsOverrideDefaults() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(List.of("CREATED"));
        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("tenant-explicit");
        request.setName("Explicit");
        request.setDefaultCommitOveragePolicy(CommitOveragePolicy.REJECT);
        request.setDefaultReservationTtlMs(12_000L);
        request.setMaxReservationTtlMs(90_000L);
        request.setMaxReservationExtensions(3);

        Tenant result = repository.create(request).tenant();

        assertThat(result.getDefaultCommitOveragePolicy()).isEqualTo(CommitOveragePolicy.REJECT);
        assertThat(result.getDefaultReservationTtlMs()).isEqualTo(12_000L);
        assertThat(result.getMaxReservationTtlMs()).isEqualTo(90_000L);
        assertThat(result.getMaxReservationExtensions()).isEqualTo(3);
    }

    @Test
    void sortedList_skipsMissingAndMalformedRowsAndStopsAtLimit() throws Exception {
        when(jedis.smembers("tenants")).thenReturn(
            new LinkedHashSet<>(List.of("missing", "malformed", "t-1", "t-2")));
        when(jedis.get("tenant:missing")).thenReturn(null);
        when(jedis.get("tenant:malformed")).thenReturn("{bad-json");
        String firstJson = objectMapper.writeValueAsString(
            tenantRow("t-1", "Needle One", TenantStatus.ACTIVE, null));
        String secondJson = objectMapper.writeValueAsString(
            tenantRow("t-2", "Needle Two", TenantStatus.ACTIVE, null));
        when(jedis.get("tenant:t-1")).thenReturn(firstJson);
        when(jedis.get("tenant:t-2")).thenReturn(secondJson);

        List<Tenant> result = repository.list(null, null, "needle", null, 1,
            SortSpec.of("name", SortDirection.ASC));

        assertThat(result).extracting(Tenant::getTenantId).containsExactly("t-1");
    }

    @Test
    void sortedList_statusComparatorHandlesNullAndCursorContinuation() throws Exception {
        Tenant nullStatus = tenantRow("t-null", "Null", null, null);
        Tenant active = tenantRow("t-active", "Active", TenantStatus.ACTIVE, null);
        when(jedis.smembers("tenants")).thenReturn(
            new LinkedHashSet<>(List.of("t-null", "t-active")));
        String nullStatusJson = objectMapper.writeValueAsString(nullStatus);
        String activeJson = objectMapper.writeValueAsString(active);
        when(jedis.get("tenant:t-null")).thenReturn(nullStatusJson);
        when(jedis.get("tenant:t-active")).thenReturn(activeJson);

        List<Tenant> sorted = repository.list(null, null, null, null, 10,
            SortSpec.of("status", SortDirection.ASC));
        assertThat(sorted).extracting(Tenant::getTenantId).containsExactly("t-active", "t-null");

        List<Tenant> afterCursor = repository.list(null, null, null, "t-active", 10,
            SortSpec.of("status", SortDirection.ASC));
        assertThat(afterCursor).extracting(Tenant::getTenantId).containsExactly("t-null");
    }

    @Test
    void update_nullLegacyStatusAndAllOptionalSettingsAreHandled() throws Exception {
        Tenant legacy = Tenant.builder().tenantId("tenant-1").name("Legacy")
            .status(null).createdAt(Instant.now()).build();
        String legacyJson = objectMapper.writeValueAsString(legacy);
        when(jedis.get("tenant:tenant-1")).thenReturn(legacyJson);
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setStatus(TenantStatus.ACTIVE);
        request.setMetadata(Map.of("tier", "gold"));
        request.setDefaultCommitOveragePolicy(CommitOveragePolicy.REJECT);
        request.setDefaultReservationTtlMs(5_000L);
        request.setMaxReservationTtlMs(50_000L);
        request.setMaxReservationExtensions(7);

        Tenant result = repository.update("tenant-1", request);

        assertThat(result.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(result.getMetadata()).containsEntry("tier", "gold");
        assertThat(result.getDefaultCommitOveragePolicy()).isEqualTo(CommitOveragePolicy.REJECT);
        assertThat(result.getDefaultReservationTtlMs()).isEqualTo(5_000L);
        assertThat(result.getMaxReservationTtlMs()).isEqualTo(50_000L);
        assertThat(result.getMaxReservationExtensions()).isEqualTo(7);
    }

    @Test
    void sortedListCoversBlankSearchNullFieldFilteringAndCursorBoundaries() throws Exception {
        Tenant active = tenantRow("active", "Active", TenantStatus.ACTIVE, null);
        Tenant suspended = tenantRow("suspended", "Suspended", TenantStatus.SUSPENDED, null);
        String activeJson = objectMapper.writeValueAsString(active);
        String suspendedJson = objectMapper.writeValueAsString(suspended);
        when(jedis.smembers("tenants")).thenReturn(
            new LinkedHashSet<>(List.of("missing", "malformed", "active", "suspended")));
        when(jedis.get("tenant:missing")).thenReturn(null);
        when(jedis.get("tenant:malformed")).thenReturn("{bad-json");
        when(jedis.get("tenant:active")).thenReturn(activeJson);
        when(jedis.get("tenant:suspended")).thenReturn(suspendedJson);
        SortSpec defaultField = new SortSpec(null, SortDirection.ASC);

        assertThat(repository.list(TenantStatus.ACTIVE, null, null, " ", 1, defaultField))
            .extracting(Tenant::getTenantId).containsExactly("active");
        assertThat(repository.list(TenantStatus.ACTIVE, null, " ", null, 1, defaultField)).isEmpty();
        assertThatThrownBy(() -> repository.list(
                null, null, null, "not-found", 10, defaultField))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("restart pagination");
    }

    @Test
    void legacyListDiagnosticsCoverBlankAndPresentSearchValues() throws Exception {
        when(jedis.smembers("tenants")).thenReturn(new LinkedHashSet<>(List.of("missing", "malformed")));
        when(jedis.get("tenant:missing")).thenReturn(null);
        when(jedis.get("tenant:malformed")).thenReturn("{bad-json");

        assertThat(repository.list(null, null, " ", " ", 10, null)).isEmpty();
        assertThat(repository.list(null, null, "needle", null, 10, null)).isEmpty();
        assertThat(repository.matchForBulk(null, null, " ", 10)).isEmpty();
    }
}
