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

    @Test
    void listAuditLogs_limitClampedToMax100() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), eq(100)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "500"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), eq(100));
    }

    @Test
    void listAuditLogs_limitClampedToMin1() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), eq(1)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), eq(1));
    }

    @Test
    void listAuditLogs_emptyResult_nextCursorIsNull() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listAuditLogs_resultCountEqualsLimit_hasMoreTrueWithCursor() throws Exception {
        AuditLogEntry e1 = AuditLogEntry.builder()
                .logId("log_1").tenantId("t1").operation("createTenant")
                .status(201).timestamp(Instant.now()).build();
        AuditLogEntry e2 = AuditLogEntry.builder()
                .logId("log_2").tenantId("t1").operation("updateTenant")
                .status(200).timestamp(Instant.now()).build();
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), eq(2)))
                .thenReturn(List.of(e1, e2));

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").value("log_2"));
    }

    @Test
    void listAuditLogs_withFromAndTo_passesInstantParams() throws Exception {
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2025-12-31T23:59:59Z");
        when(auditRepository.list(isNull(), isNull(), isNull(), isNull(), eq(from), eq(to), isNull(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2025-12-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isEmpty());

        verify(auditRepository).list(isNull(), isNull(), isNull(), isNull(), eq(from), eq(to), isNull(), eq(50));
    }
}
