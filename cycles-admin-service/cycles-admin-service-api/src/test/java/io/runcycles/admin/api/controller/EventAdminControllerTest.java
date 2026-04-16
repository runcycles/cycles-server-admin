package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.event.*;
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

@WebMvcTest(EventAdminController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})
class EventAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private EventService eventService;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void listEvents_returns200() throws Exception {
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    void listEvents_withFilters_passes() throws Exception {
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(eq("tenant-1"), eq("budget.created"), eq("budget"), eq("org/team1"),
                eq("corr_1"), any(), any(), any(), anyInt(), any()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "tenant-1")
                        .param("event_type", "budget.created")
                        .param("category", "budget")
                        .param("scope", "org/team1")
                        .param("correlation_id", "corr_1"))
                .andExpect(status().isOk());
    }

    @Test
    void listEvents_noAdminKey_returns401() throws Exception {
        mockMvc.perform(get("/v1/admin/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getEvent_returns200() throws Exception {
        Event event = Event.builder()
            .eventId("evt_1").eventType(EventType.BUDGET_CREATED)
            .category(EventCategory.BUDGET).tenantId("tenant-1")
            .timestamp(Instant.now()).source("admin").build();
        when(eventService.findById("evt_1")).thenReturn(event);

        mockMvc.perform(get("/v1/admin/events/evt_1")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value("evt_1"))
                .andExpect(jsonPath("$.event_type").value("budget.created"));
    }

    @Test
    void listEvents_clampsLimitTo100() throws Exception {
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(100), any()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(eventService).list(any(), any(), any(), any(), any(), any(), any(), any(), eq(100), any());
    }

    @Test
    void getEvent_notFound_returns404() throws Exception {
        when(eventService.findById("evt_missing"))
            .thenThrow(GovernanceException.eventNotFound("evt_missing"));

        mockMvc.perform(get("/v1/admin/events/evt_missing")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("EVENT_NOT_FOUND"));
    }

    // --- Sort contract tests (spec v0.1.25.20 §V4) ---

    @Test
    void listEvents_defaultsToTimestampDesc() throws Exception {
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(eventService).list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), captor.capture());
        SortSpec sort = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("timestamp", sort.field());
        org.junit.jupiter.api.Assertions.assertEquals(SortDirection.DESC, sort.direction());
    }

    @Test
    void listEvents_acceptsValidSortByAndDir() throws Exception {
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "event_type")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<SortSpec> captor = ArgumentCaptor.forClass(SortSpec.class);
        verify(eventService).list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), captor.capture());
        SortSpec sort = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("event_type", sort.field());
        org.junit.jupiter.api.Assertions.assertEquals(SortDirection.ASC, sort.direction());
    }

    @Test
    void listEvents_acceptsAllWhitelistedFields() throws Exception {
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(response);

        for (String field : List.of("event_type", "category", "scope", "tenant_id", "timestamp")) {
            mockMvc.perform(get("/v1/admin/events")
                            .header("X-Admin-API-Key", ADMIN_KEY)
                            .param("sort_by", field))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void listEvents_unknownSortBy_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void listEvents_unknownSortDir_returns400() throws Exception {
        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("sort_by", "timestamp")
                        .param("sort_dir", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}
