package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.event.*;
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

@WebMvcTest(EventAdminController.class)
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
        when(eventService.list(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
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
        when(eventService.list(eq("t1"), eq("budget.created"), eq("budget"), eq("org/team1"),
                eq("corr_1"), any(), any(), any(), anyInt()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("tenant_id", "t1")
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
            .category(EventCategory.BUDGET).tenantId("t1")
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
        when(eventService.list(any(), any(), any(), any(), any(), any(), any(), any(), eq(100)))
            .thenReturn(response);

        mockMvc.perform(get("/v1/admin/events")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .param("limit", "999"))
                .andExpect(status().isOk());

        verify(eventService).list(any(), any(), any(), any(), any(), any(), any(), any(), eq(100));
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
}
