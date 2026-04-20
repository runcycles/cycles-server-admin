package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.api.service.TenantCloseCascadeService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.idempotency.IdempotencyStore;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.CommitOveragePolicy;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import io.runcycles.admin.api.contract.ContractValidationConfig;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TenantController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class TenantControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private TenantRepository tenantRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;
    @MockitoBean private EventService eventService;
    @MockitoBean private IdempotencyStore idempotencyStore;
    @MockitoBean private TenantCloseCascadeService tenantCloseCascadeService;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void createTenant_returns201() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("new-tenant").name("New").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, true));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"new-tenant\",\"name\":\"New\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant_id").value("new-tenant"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createTenant_withDefaultCommitOveragePolicy_returns201() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("new-tenant").name("New").status(TenantStatus.ACTIVE)
                .defaultCommitOveragePolicy(CommitOveragePolicy.ALLOW_WITH_OVERDRAFT)
                .createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, true));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"new-tenant\",\"name\":\"New\",\"default_commit_overage_policy\":\"ALLOW_WITH_OVERDRAFT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.default_commit_overage_policy").value("ALLOW_WITH_OVERDRAFT"));
    }

    @Test
    void createTenant_existingReturns200() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("existing").name("E").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, false));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"existing\",\"name\":\"E\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void createTenant_missingName_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTenant_invalidTenantId_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"INVALID!\",\"name\":\"N\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTenant_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"test\",\"name\":\"N\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listTenants_returns200() throws Exception {
        List<Tenant> tenants = List.of(
                Tenant.builder().tenantId("tenant-1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build());
        when(tenantRepository.list(any(), any(), any(), any(), anyInt(), any())).thenReturn(tenants);

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isArray())
                .andExpect(jsonPath("$.tenants[0].tenant_id").value("tenant-1"));
    }

    @Test
    void listTenants_withStatusFilter() throws Exception {
        when(tenantRepository.list(eq(TenantStatus.ACTIVE), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isEmpty());
    }

    @Test
    void getTenant_returns200() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("tenant-1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.get("tenant-1")).thenReturn(tenant);

        mockMvc.perform(get("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value("tenant-1"));
    }

    @Test
    void getTenant_notFound_returns404() throws Exception {
        when(tenantRepository.get("missing")).thenThrow(GovernanceException.tenantNotFound("missing"));

        mockMvc.perform(get("/v1/admin/tenants/missing")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void updateTenant_returns200() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void updateTenant_invalidTransition_returns400() throws Exception {
        when(tenantRepository.update(eq("tenant-1"), any()))
                .thenThrow(new GovernanceException(ErrorCode.INVALID_REQUEST, "Cannot transition from CLOSED", 400));

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void createTenant_logsAuditEntryWith201() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("new-tenant").name("New").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, true));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"new-tenant\",\"name\":\"New\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                "createTenant".equals(entry.getOperation()) &&
                "new-tenant".equals(entry.getTenantId()) &&
                entry.getStatus() == 201));
    }

    @Test
    void createTenant_existingTenant_logsAuditEntryWith200() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("existing").name("E").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, false));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"existing\",\"name\":\"E\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "createTenant".equals(entry.getOperation()) &&
                entry.getStatus() == 200));
    }

    @Test
    void updateTenant_logsAuditEntry() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updateTenant".equals(entry.getOperation()) &&
                "tenant-1".equals(entry.getTenantId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void listTenants_emptyResult_hasMoreFalseAndNoCursor() throws Exception {
        when(tenantRepository.list(any(), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listTenants_limitClampedToMax100() throws Exception {
        when(tenantRepository.list(any(), any(), any(), any(), eq(100), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), any(), any(), any(), eq(100), any());
    }

    @Test
    void updateTenant_notFound_returns404() throws Exception {
        when(tenantRepository.update(eq("missing"), any()))
                .thenThrow(GovernanceException.tenantNotFound("missing"));

        mockMvc.perform(patch("/v1/admin/tenants/missing")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void listTenants_limitClampedToMin1() throws Exception {
        when(tenantRepository.list(any(), any(), any(), any(), eq(1), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), any(), any(), any(), eq(1), any());
    }

    @Test
    void createTenant_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("new-tenant").name("New").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, true));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"new-tenant\",\"name\":\"New\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getRequestId() != null &&
                entry.getRequestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") &&
                "createTenant".equals(entry.getOperation())));
    }

    @Test
    void createTenant_auditEntry_userAgentIsNullWhenHeaderMissing() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("new-tenant").name("New").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, true));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"new-tenant\",\"name\":\"New\"}"))
                .andExpect(status().isCreated());

        verify(auditRepository).log(argThat(entry ->
                entry.getUserAgent() == null &&
                "createTenant".equals(entry.getOperation())));
    }

    @Test
    void updateTenant_auditEntry_requestIdIsFallbackUuidWhenAttributeMissing() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                entry.getRequestId() != null &&
                entry.getRequestId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") &&
                "updateTenant".equals(entry.getOperation())));
    }

    @Test
    void updateTenant_auditEntry_capturesSourceIp() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                entry.getSourceIp() != null &&
                "updateTenant".equals(entry.getOperation())));
    }

    @Test
    void updateTenant_defaultCommitOveragePolicy_returns200() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.ACTIVE)
                .defaultCommitOveragePolicy(CommitOveragePolicy.ALLOW_WITH_OVERDRAFT)
                .createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"default_commit_overage_policy\":\"ALLOW_WITH_OVERDRAFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_commit_overage_policy").value("ALLOW_WITH_OVERDRAFT"));
    }

    @Test
    void updateTenant_invalidCommitOveragePolicy_returns400() throws Exception {
        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"default_commit_overage_policy\":\"INVALID_VALUE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listTenants_withParentTenantIdFilter() throws Exception {
        when(tenantRepository.list(any(), eq("parent-1"), any(), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("parent_tenant_id", "parent-1"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), eq("parent-1"), any(), any(), anyInt(), any());
    }

    @Test
    void listTenants_resultCountEqualsLimit_hasMoreTrueWithCursor() throws Exception {
        // Return exactly 2 tenants with limit=2 to trigger has_more=true branch
        List<Tenant> tenants = List.of(
                Tenant.builder().tenantId("tenant-1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build(),
                Tenant.builder().tenantId("tenant-2").name("B").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build());
        when(tenantRepository.list(any(), any(), any(), any(), eq(2), any())).thenReturn(tenants);

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("tenant-2"));
    }

    @Test
    void listTenants_withCursorParam_passesToRepository() throws Exception {
        when(tenantRepository.list(any(), any(), any(), eq("t-cursor"), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("cursor", "t-cursor"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), any(), any(), eq("t-cursor"), anyInt(), any());
    }

    @Test
    void createTenant_emitsEvent() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("new-tenant").name("New").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.create(any())).thenReturn(new TenantRepository.TenantCreateResult(tenant, true));

        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"new-tenant\",\"name\":\"New\"}"))
                .andExpect(status().isCreated());

        verify(eventService).emit(eq(EventType.TENANT_CREATED), eq("new-tenant"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateTenant_emitsEvent() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.TENANT_UPDATED), eq("tenant-1"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateTenant_withSuspendedStatus_emitsSuspendedEvent() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.SUSPENDED).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.TENANT_SUSPENDED), eq("tenant-1"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateTenant_withClosedStatus_emitsClosedEvent() throws Exception {
        // Spec v0.1.25.29 Rule 1: PATCH→CLOSED reads the prior tenant,
        // flips status via repository.update (activating Rule 2's mutation
        // guard), then runs the cascade. Stub all three collaborators.
        Tenant prior = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.CLOSED).createdAt(Instant.now()).build();
        when(tenantRepository.get("tenant-1")).thenReturn(prior);
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);
        when(tenantCloseCascadeService.cascade(eq("tenant-1"), any()))
                .thenReturn(new TenantCloseCascadeService.CascadeResult(2, 1, 1, 0L));

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());

        // Repository.update MUST run before cascade (flip-first ordering so
        // Rule 2's guard is active during the cascade window).
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenantRepository, tenantCloseCascadeService);
        order.verify(tenantRepository).update(eq("tenant-1"), any());
        order.verify(tenantCloseCascadeService).cascade(eq("tenant-1"), any());
        verify(eventService).emit(eq(EventType.TENANT_CLOSED), eq("tenant-1"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateTenant_closedStatus_tenantClosedEvent_carriesCascadeCorrelationId() throws Exception {
        // Spec v0.1.25.29 Rule 1 correlation-id parity: the TENANT_CLOSED
        // event must share the same correlation_id that the cascade stamps
        // on every child event, so downstream consumers can JOIN parent +
        // children as one logical operation.
        Tenant prior = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.CLOSED).createdAt(Instant.now()).build();
        when(tenantRepository.get("tenant-1")).thenReturn(prior);
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);
        when(tenantCloseCascadeService.cascade(eq("tenant-1"), any()))
                .thenReturn(new TenantCloseCascadeService.CascadeResult(1, 0, 0, 0L));

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .header("X-Request-ID", "req-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<String> correlationCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(eventService).emit(eq(EventType.TENANT_CLOSED), eq("tenant-1"),
                any(), any(), any(), any(),
                correlationCaptor.capture(), any());
        String expected = "tenant_close_cascade:tenant-1:req-abc";
        org.junit.jupiter.api.Assertions.assertEquals(expected, correlationCaptor.getValue(),
            "TENANT_CLOSED event must share cascade correlation_id");
    }

    @Test
    void updateTenant_closedStatus_cascadeThrows_tenantStaysClosed() throws Exception {
        // Spec v0.1.25.29 Rule 1 partial-failure: tenant is already flipped
        // when the cascade throws. An operator re-issuing the close picks
        // up any remaining non-terminal children (cascade is idempotent).
        Tenant prior = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.CLOSED).createdAt(Instant.now()).build();
        when(tenantRepository.get("tenant-1")).thenReturn(prior);
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);
        when(tenantCloseCascadeService.cascade(eq("tenant-1"), any()))
                .thenThrow(new RuntimeException("cascade boom"));

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().is5xxServerError());

        // Tenant flip already committed before the cascade blew up.
        verify(tenantRepository).update(eq("tenant-1"), any());
    }

    @Test
    void updateTenant_priorStatusAlreadyClosed_skipsCascade() throws Exception {
        // Re-close must be a no-op: cascade is not re-run when the tenant is
        // already CLOSED. TenantRepository's idempotent short-circuit handles
        // the status-flip side; the controller must avoid redundant child
        // writes.
        Tenant prior = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.CLOSED).createdAt(Instant.now()).build();
        when(tenantRepository.get("tenant-1")).thenReturn(prior);
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(prior);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());

        verify(tenantCloseCascadeService, org.mockito.Mockito.never()).cascade(any(), any());
    }

    @Test
    void updateTenant_withActiveStatus_emitsReactivatedEvent() throws Exception {
        Tenant updated = Tenant.builder()
                .tenantId("tenant-1").name("Test").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        verify(eventService).emit(eq(EventType.TENANT_REACTIVATED), eq("tenant-1"), any(), any(), any(), any(), any(), any());
    }

    // ========== Tenant TTL update fields ==========

    @Test
    void updateTenant_defaultReservationTtlMs_returns200() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("tenant-1").name("T1").status(TenantStatus.ACTIVE)
                .defaultReservationTtlMs(30000L).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(tenant);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"default_reservation_ttl_ms\":30000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_reservation_ttl_ms").value(30000));
    }

    @Test
    void updateTenant_maxReservationTtlMs_returns200() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("tenant-1").name("T1").status(TenantStatus.ACTIVE)
                .maxReservationTtlMs(1800000L).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(tenant);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_reservation_ttl_ms\":1800000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.max_reservation_ttl_ms").value(1800000));
    }

    @Test
    void updateTenant_maxReservationExtensions_returns200() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("tenant-1").name("T1").status(TenantStatus.ACTIVE)
                .maxReservationExtensions(20).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("tenant-1"), any())).thenReturn(tenant);

        mockMvc.perform(patch("/v1/admin/tenants/tenant-1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_reservation_extensions\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.max_reservation_extensions").value(20));
    }

    // v0.1.25.8: observe_mode query param must be accepted and silently ignored
    @Test
    void listTenants_withObserveModeParam_acceptedAndIgnored() throws Exception {
        when(tenantRepository.list(any(), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("observe_mode", "shadow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isArray());

        // Repository call unchanged — observe_mode is not passed through on v0.1.25.x
        verify(tenantRepository).list(any(), any(), any(), any(), anyInt(), any());
    }

    // ---- v0.1.25.20: sort_by / sort_dir contract ----

    @Test
    void listTenants_withValidSortByAndSortDir_delegatesToRepository() throws Exception {
        when(tenantRepository.list(any(), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "name")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<io.runcycles.admin.model.shared.SortSpec> captor =
            org.mockito.ArgumentCaptor.forClass(io.runcycles.admin.model.shared.SortSpec.class);
        verify(tenantRepository).list(any(), any(), any(), any(), anyInt(), captor.capture());
        assertEquals("name", captor.getValue().field());
        assertEquals(io.runcycles.admin.model.shared.SortDirection.ASC, captor.getValue().direction());
    }

    @Test
    void listTenants_defaultsToCreatedAtDescending() throws Exception {
        when(tenantRepository.list(any(), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<io.runcycles.admin.model.shared.SortSpec> captor =
            org.mockito.ArgumentCaptor.forClass(io.runcycles.admin.model.shared.SortSpec.class);
        verify(tenantRepository).list(any(), any(), any(), any(), anyInt(), captor.capture());
        assertEquals("created_at", captor.getValue().field());
        assertEquals(io.runcycles.admin.model.shared.SortDirection.DESC, captor.getValue().direction());
    }

    @Test
    void listTenants_unknownSortByReturns400() throws Exception {
        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "not_a_field"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(tenantRepository, never()).list(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void listTenants_unknownSortDirReturns400() throws Exception {
        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_dir", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(tenantRepository, never()).list(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void listTenants_acceptsAllWhitelistedSortFields() throws Exception {
        when(tenantRepository.list(any(), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        for (String field : new String[]{"tenant_id", "name", "status", "created_at"}) {
            mockMvc.perform(get("/v1/admin/tenants")
                            .header("X-Admin-API-Key", ADMIN_KEY)
                            .param("sort_by", field))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void listTenants_searchOver128Chars_returns400() throws Exception {
        String over = "x".repeat(129);
        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("search", over))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(tenantRepository, never()).list(any(), any(), any(), any(), anyInt(), any());
    }

    // --- Bulk-action contract tests (spec v0.1.25.21) ---

    private static Tenant tenantRow(String id, TenantStatus status) {
        return Tenant.builder()
                .tenantId(id).name(id + " Corp").status(status)
                .createdAt(Instant.now()).build();
    }

    @Test
    void bulkActionTenants_suspend_happyPath_returns200() throws Exception {
        when(idempotencyStore.lookup(eq("tenants-bulk"), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(eq(TenantStatus.ACTIVE), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE), tenantRow("t2", TenantStatus.ACTIVE)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.ACTIVE));
        when(tenantRepository.get("t2")).thenReturn(tenantRow("t2", TenantStatus.ACTIVE));
        when(tenantRepository.update(anyString(), any())).thenReturn(tenantRow("t1", TenantStatus.SUSPENDED));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SUSPEND"))
                .andExpect(jsonPath("$.total_matched").value(2))
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.idempotency_key").value("k1"));

        verify(tenantRepository, times(2)).update(anyString(), any());
        verify(idempotencyStore).store(eq("tenants-bulk"), eq("k1"), any(TenantBulkActionResponse.class));
    }

    @Test
    void bulkActionTenants_emptyFilter_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(tenantRepository, never()).matchForBulk(any(), any(), any(), anyInt());
    }

    @Test
    void bulkActionTenants_searchOver128Chars_returns400() throws Exception {
        String over = "x".repeat(129);
        String body = "{\"filter\":{\"search\":\"" + over + "\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}";
        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(tenantRepository, never()).matchForBulk(any(), any(), any(), anyInt());
    }

    @Test
    void bulkActionTenants_expectedCountMismatch_returns409_noWrites() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE), tenantRow("t2", TenantStatus.ACTIVE)));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\","
                                + "\"expected_count\":5,\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("COUNT_MISMATCH"))
                .andExpect(jsonPath("$.details.total_matched").value(2));

        verify(tenantRepository, never()).update(anyString(), any());
    }

    @Test
    void bulkActionTenants_over500Matches_returns400_limitExceeded() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        List<Tenant> oversized = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) oversized.add(tenantRow("t" + i, TenantStatus.ACTIVE));
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500))).thenReturn(oversized);

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.total_matched").value(501));

        verify(tenantRepository, never()).update(anyString(), any());
    }

    @Test
    void bulkActionTenants_idempotencyReplay_returnsCachedEnvelope_noWrites() throws Exception {
        TenantBulkActionResponse cached = TenantBulkActionResponse.builder()
                .action(TenantBulkAction.SUSPEND)
                .totalMatched(3)
                .succeeded(List.of())
                .failed(List.of())
                .skipped(List.of())
                .idempotencyKey("k1")
                .build();
        when(idempotencyStore.lookup(eq("tenants-bulk"), eq("k1"), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.of(cached));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_matched").value(3));

        verify(tenantRepository, never()).matchForBulk(any(), any(), any(), anyInt());
        verify(tenantRepository, never()).update(anyString(), any());
        verify(idempotencyStore, never()).store(anyString(), anyString(), any());
    }

    @Test
    void bulkActionTenants_alreadyInTargetState_lisInSkipped() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.SUSPENDED)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.SUSPENDED));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"SUSPENDED\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value("t1"))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_IN_TARGET_STATE"))
                .andExpect(jsonPath("$.succeeded.length()").value(0));

        verify(tenantRepository, never()).update(anyString(), any());
    }

    @Test
    void bulkActionTenants_closedTenantSuspendAttempt_landsInFailed() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.CLOSED)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.CLOSED));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"parent_tenant_id\":\"p1\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].id").value("t1"))
                .andExpect(jsonPath("$.failed[0].error_code").value("INVALID_TRANSITION"));

        verify(tenantRepository, never()).update(anyString(), any());
    }

    @Test
    void bulkActionTenants_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void bulkActionTenants_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isUnauthorized());

        verify(tenantRepository, never()).matchForBulk(any(), any(), any(), anyInt());
    }

    @Test
    void bulkActionTenants_close_fromActive_succeeds() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.ACTIVE));
        when(tenantRepository.update(eq("t1"), any())).thenReturn(tenantRow("t1", TenantStatus.CLOSED));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"CLOSE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("CLOSE"))
                .andExpect(jsonPath("$.succeeded[0].id").value("t1"));

        // Spec v0.1.25.29 Rule 1: bulk CLOSE flips tenant status BEFORE cascade
        // so Rule 2's mutation guard is active during the cascade window.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenantRepository, tenantCloseCascadeService);
        order.verify(tenantRepository).update(eq("t1"), any());
        order.verify(tenantCloseCascadeService).cascade(eq("t1"), any());
    }

    @Test
    void bulkActionTenants_reactivateFromClosed_landsInFailed() throws Exception {
        // REACTIVATE → target ACTIVE; CLOSED tenant with action != CLOSE hits
        // the INVALID_TRANSITION branch of applyTenantAction.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.CLOSED)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.CLOSED));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"CLOSED\"},\"action\":\"REACTIVATE\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].id").value("t1"))
                .andExpect(jsonPath("$.failed[0].error_code").value("INVALID_TRANSITION"));

        verify(tenantRepository, never()).update(anyString(), any());
    }

    @Test
    void bulkActionTenants_governanceException_classifiedByErrorCode() throws Exception {
        // Update throws GovernanceException with INSUFFICIENT_PERMISSIONS →
        // classifyFailureCode → PERMISSION_DENIED in failed[].
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.ACTIVE));
        when(tenantRepository.update(eq("t1"), any()))
                .thenThrow(new GovernanceException(ErrorCode.INSUFFICIENT_PERMISSIONS,
                        "denied", 403));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].id").value("t1"))
                .andExpect(jsonPath("$.failed[0].error_code").value("PERMISSION_DENIED"));
    }

    @Test
    void bulkActionTenants_governanceException_invalidRequest_classifiedAsInvalidTransition() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.ACTIVE));
        when(tenantRepository.update(eq("t1"), any()))
                .thenThrow(new GovernanceException(ErrorCode.INVALID_REQUEST,
                        "bad", 400));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("INVALID_TRANSITION"));
    }

    @Test
    void bulkActionTenants_governanceException_tenantNotFound_classifiedAsNotFound() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE)));
        when(tenantRepository.get("t1"))
                .thenThrow(new GovernanceException(ErrorCode.TENANT_NOT_FOUND,
                        "gone", 404));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("NOT_FOUND"));
    }

    @Test
    void bulkActionTenants_auditMetadata_carriesV030EnrichmentKeys() throws Exception {
        // v0.1.25.30: audit metadata now carries per-row outcomes + filter
        // echo + duration so post-incident triage needs only the audit log.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(eq(TenantStatus.ACTIVE), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.ACTIVE));
        when(tenantRepository.update(eq("t1"), any()))
                .thenReturn(tenantRow("t1", TenantStatus.SUSPENDED));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<io.runcycles.admin.model.audit.AuditLogEntry> auditArg =
                org.mockito.ArgumentCaptor.forClass(io.runcycles.admin.model.audit.AuditLogEntry.class);
        verify(auditRepository).log(auditArg.capture());
        java.util.Map<String, Object> meta = auditArg.getValue().getMetadata();
        assertEquals("bulkActionTenants", auditArg.getValue().getOperation());
        assertEquals("SUSPEND", meta.get("action"));
        assertEquals(1, meta.get("total_matched"));
        assertEquals(List.of("t1"), meta.get("succeeded_ids"));
        assertEquals(List.of(), meta.get("failed_rows"));
        assertEquals(List.of(), meta.get("skipped_rows"));
        assertEquals("k1", meta.get("idempotency_key"));
        org.assertj.core.api.Assertions.assertThat(meta).containsKey("filter");
        org.assertj.core.api.Assertions.assertThat((Long) meta.get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void bulkActionTenants_genericException_classifiedAsInternalError() throws Exception {
        // Non-GovernanceException falls into the generic Exception catch,
        // logged and reported as INTERNAL_ERROR.
        when(idempotencyStore.lookup(anyString(), anyString(), eq(TenantBulkActionResponse.class)))
                .thenReturn(java.util.Optional.empty());
        when(tenantRepository.matchForBulk(any(), any(), any(), eq(500)))
                .thenReturn(List.of(tenantRow("t1", TenantStatus.ACTIVE)));
        when(tenantRepository.get("t1")).thenReturn(tenantRow("t1", TenantStatus.ACTIVE));
        when(tenantRepository.update(eq("t1"), any()))
                .thenThrow(new RuntimeException("redis-down"));

        mockMvc.perform(post("/v1/admin/tenants/bulk-action")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"status\":\"ACTIVE\"},\"action\":\"SUSPEND\",\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].error_code").value("INTERNAL_ERROR"));
    }
}
