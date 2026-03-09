package io.runcycles.admin.api.controller;

import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void listAuditLogs_returns200() throws Exception {
        AuditLogEntry entry = AuditLogEntry.builder()
                .logId("log_1").tenantId("t1").operation("createTenant")
                .status(201).timestamp(Instant.now()).build();
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(entry));

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isArray())
                .andExpect(jsonPath("$.logs[0].log_id").value("log_1"));
    }

    @Test
    void listAuditLogs_withFilters() throws Exception {
        when(auditRepository.list(eq("t1"), eq("key_1"), eq("createTenant"), eq(201), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1")
                        .param("key_id", "key_1")
                        .param("operation", "createTenant")
                        .param("status", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isEmpty());
    }

    @Test
    void listAuditLogs_noAdminKey_returns401() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAuditLogs_emptyResult_returnsEmptyList() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false));
    }
}
