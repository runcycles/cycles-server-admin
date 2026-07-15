package io.runcycles.admin.api.controller;

import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import io.runcycles.admin.api.support.MetricsTestConfiguration;
import io.runcycles.admin.api.contract.ContractValidationConfig;
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
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    // 18-arg canonical matcher suite per spec v0.1.25.27:
    // tenantId, keyId, operations, status, resourceTypes, resourceId,
    // from, to, cursor, limit, sortSpec, search,
    // errorCodes, errorCodeExcludes, statusMin, statusMax,
    // traceId, requestId.
    private static void stubAnyListReturns(AuditRepository repo, List<AuditLogEntry> out) {
        when(repo.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(out);
    }

    @Test
    void listAuditLogs_returns200() throws Exception {
        AuditLogEntry entry = AuditLogEntry.builder()
                .logId("log_1").tenantId("tenant-1").operation("createTenant")
                .status(201).timestamp(Instant.now()).build();
        stubAnyListReturns(auditRepository, List.of(entry));

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isArray())
                .andExpect(jsonPath("$.logs[0].log_id").value("log_1"));
    }

    @Test
    void listAuditLogs_withFilters() throws Exception {
        when(auditRepository.list(eq("tenant-1"), eq("key_1"), eq(List.of("createTenant")), eq(201),
                any(), any(), any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
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
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isEmpty())
                .andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    void listAuditLogs_limitClampedToMax100() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(101),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "500"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(101),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_limitClampedToMin1() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(2),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "0"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(2),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_emptyResult_nextCursorIsNull() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listAuditLogs_resultCountExceedsLimit_hasMoreTrueWithCursor() throws Exception {
        AuditLogEntry e1 = AuditLogEntry.builder()
                .logId("log_1").tenantId("tenant-1").operation("createTenant")
                .status(201).timestamp(Instant.now()).build();
        AuditLogEntry e2 = AuditLogEntry.builder()
                .logId("log_2").tenantId("tenant-1").operation("updateTenant")
                .status(200).timestamp(Instant.now()).build();
        AuditLogEntry e3 = AuditLogEntry.builder()
                .logId("log_3").tenantId("tenant-1").operation("deleteTenant")
                .status(200).timestamp(Instant.now()).build();
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(3),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(e1, e2, e3));

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
        when(auditRepository.list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(from), eq(to), isNull(), eq(51), any(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2025-12-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isEmpty());

        verify(auditRepository).list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(from), eq(to), isNull(), eq(51), any(), any(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull());
    }

    // --- Sort contract tests (spec v0.1.25.20 §V4) ---

    @Test
    void listAuditLogs_defaultsToTimestampDesc() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                captor.capture(), any(), any(), any(), any(), any(), any(), any());
        SortSpec sort = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("timestamp", sort.field());
        org.junit.jupiter.api.Assertions.assertEquals(SortDirection.DESC, sort.direction());
    }

    @Test
    void listAuditLogs_acceptsValidSortByAndDir() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "operation")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                captor.capture(), any(), any(), any(), any(), any(), any(), any());
        SortSpec sort = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("operation", sort.field());
        org.junit.jupiter.api.Assertions.assertEquals(SortDirection.ASC, sort.direction());
    }

    @Test
    void listAuditLogs_acceptsAllWhitelistedFields() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        for (String field : List.of("timestamp", "operation", "resource_type", "tenant_id", "key_id", "status")) {
            mockMvc.perform(get("/v1/admin/audit/logs")
                            .header("X-Admin-API-Key", ADMIN_KEY)
                            .param("sort_by", field))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void listAuditLogs_unknownSortBy_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_unknownSortDir_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "timestamp")
                        .param("sort_dir", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_searchOver128Chars_returns400() throws Exception {
        String over = "x".repeat(129);
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("search", over))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(auditRepository, never()).list(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // --- v0.1.25.24 filter DSL upgrade: error_code IN-list, error_code_exclude,
    // status_min/status_max range, operation+resource_type promoted to IN-list ---

    @Test
    void listAuditLogs_errorCodeSingle_passesThroughAsList() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), eq(List.of("BUDGET_EXCEEDED")), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("error_code", "BUDGET_EXCEEDED"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), eq(List.of("BUDGET_EXCEEDED")), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_errorCodeCommaSeparated_splitsAndDedupes() throws Exception {
        // Spring's @RequestParam List<String> pre-splits commas before parseCodeList
        // sees them; controller then dedupes. Pass "A,B,A" → expect ["A","B"].
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("error_code", "BUDGET_EXCEEDED,TENANT_SUSPENDED,BUDGET_EXCEEDED"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), eq(List.of("BUDGET_EXCEEDED", "TENANT_SUSPENDED")), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_errorCodeRepeatedParam_concatenates() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("error_code", "A,B")
                        .param("error_code", "C"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), eq(List.of("A", "B", "C")), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_errorCodeEmptyAfterTrim_treatedAsAbsent() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("error_code", " , , "))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), isNull(), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_errorCodeOver25_returns400() throws Exception {
        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            if (i > 0) over.append(',');
            over.append("CODE_").append(i);
        }
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("error_code", over.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        verify(auditRepository, never()).list(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_errorCodeExcludeSingle_passesThrough() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("error_code_exclude", "INTERNAL_ERROR"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), eq(List.of("INTERNAL_ERROR")), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_errorCodeExcludeOver25_returns400() throws Exception {
        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            if (i > 0) over.append(',');
            over.append("CODE_").append(i);
        }
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("error_code_exclude", over.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_statusMinMaxHappyPath_threadsThrough() throws Exception {
        when(auditRepository.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), eq(500), eq(599), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status_min", "500")
                        .param("status_max", "599"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), eq(500), eq(599), any(), any());
    }

    @Test
    void listAuditLogs_statusMinOnlyEqualityBoundary_treatedAsLowerBound() throws Exception {
        // Common auditor query: all-failures = status_min=400.
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status_min", "400"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), eq(400), isNull(), any(), any());
    }

    @Test
    void listAuditLogs_statusMinBelow100_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status_min", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_statusMinAbove599_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status_min", "600"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_statusMaxBelow100_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status_max", "42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_statusMaxAbove599_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status_max", "700"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_statusMinGreaterThanMax_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status_min", "500")
                        .param("status_max", "499"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_statusCombinedWithStatusMin_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status", "400")
                        .param("status_min", "400"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_statusCombinedWithStatusMax_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("status", "500")
                        .param("status_max", "599"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_operationInList_threadsThrough() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("operation", "createBudget,updateBudget"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), eq(List.of("createBudget", "updateBudget")),
                any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_resourceTypeInList_threadsThrough() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("resource_type", "tenant,budget"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), any(), any(),
                eq(List.of("tenant", "budget")), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_operationSingleValue_stillWorksBackCompat() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("operation", "createBudget"))
                .andExpect(status().isOk());

        verify(auditRepository).list(any(), any(), eq(List.of("createBudget")),
                any(), any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listAuditLogs_operationOver25_returns400() throws Exception {
        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            if (i > 0) over.append(',');
            over.append("op").append(i);
        }
        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("operation", over.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listAuditLogs_allNewFiltersCombined_threadsAll() throws Exception {
        stubAnyListReturns(auditRepository, List.of());

        mockMvc.perform(get("/v1/admin/audit/logs")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .param("operation", "createBudget,updateBudget")
                        .param("resource_type", "budget")
                        .param("error_code", "BUDGET_EXCEEDED")
                        .param("error_code_exclude", "INTERNAL_ERROR")
                        .param("status_min", "400")
                        .param("status_max", "499")
                        .param("search", "quota"))
                .andExpect(status().isOk());

        verify(auditRepository).list(eq("tenant-1"), any(),
                eq(List.of("createBudget", "updateBudget")), isNull(),
                eq(List.of("budget")), any(), any(), any(), any(), anyInt(),
                any(), eq("quota"),
                eq(List.of("BUDGET_EXCEEDED")), eq(List.of("INTERNAL_ERROR")),
                eq(400), eq(499), any(), any());
    }
}
