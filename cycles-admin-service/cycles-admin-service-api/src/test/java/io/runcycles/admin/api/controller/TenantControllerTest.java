package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TenantController.class)
class TenantControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private TenantRepository tenantRepository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

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
                Tenant.builder().tenantId("t1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build());
        when(tenantRepository.list(any(), any(), any(), anyInt())).thenReturn(tenants);

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isArray())
                .andExpect(jsonPath("$.tenants[0].tenant_id").value("t1"));
    }

    @Test
    void listTenants_withStatusFilter() throws Exception {
        when(tenantRepository.list(eq(TenantStatus.ACTIVE), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isEmpty());
    }

    @Test
    void getTenant_returns200() throws Exception {
        Tenant tenant = Tenant.builder()
                .tenantId("t1").name("A").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.get("t1")).thenReturn(tenant);

        mockMvc.perform(get("/v1/admin/tenants/t1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value("t1"));
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
                .tenantId("t1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("t1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/t1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void updateTenant_invalidTransition_returns400() throws Exception {
        when(tenantRepository.update(eq("t1"), any()))
                .thenThrow(new GovernanceException(ErrorCode.INVALID_REQUEST, "Cannot transition from CLOSED", 400));

        mockMvc.perform(patch("/v1/admin/tenants/t1")
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
                .tenantId("t1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("t1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/t1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                "updateTenant".equals(entry.getOperation()) &&
                "t1".equals(entry.getTenantId()) &&
                entry.getStatus() == 200));
    }

    @Test
    void listTenants_emptyResult_hasMoreFalseAndNoCursor() throws Exception {
        when(tenantRepository.list(any(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listTenants_limitClampedToMax100() throws Exception {
        when(tenantRepository.list(any(), any(), any(), eq(100))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), any(), any(), eq(100));
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
        when(tenantRepository.list(any(), any(), any(), eq(1))).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), any(), any(), eq(1));
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
                .tenantId("t1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("t1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/t1")
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
                .tenantId("t1").name("Updated").status(TenantStatus.ACTIVE).createdAt(Instant.now()).build();
        when(tenantRepository.update(eq("t1"), any())).thenReturn(updated);

        mockMvc.perform(patch("/v1/admin/tenants/t1")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());

        verify(auditRepository).log(argThat(entry ->
                entry.getSourceIp() != null &&
                "updateTenant".equals(entry.getOperation())));
    }

    @Test
    void listTenants_withParentTenantIdFilter() throws Exception {
        when(tenantRepository.list(any(), eq("parent-1"), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("parent_tenant_id", "parent-1"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), eq("parent-1"), any(), anyInt());
    }

    @Test
    void listTenants_withCursorParam_passesToRepository() throws Exception {
        when(tenantRepository.list(any(), any(), eq("t-cursor"), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("cursor", "t-cursor"))
                .andExpect(status().isOk());

        verify(tenantRepository).list(any(), any(), eq("t-cursor"), anyInt());
    }
}
