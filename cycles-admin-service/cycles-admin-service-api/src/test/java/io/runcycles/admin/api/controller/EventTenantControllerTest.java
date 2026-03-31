package io.runcycles.admin.api.controller;

import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
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

@WebMvcTest(EventTenantController.class)
class EventTenantControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private EventService eventService;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private void setupApiKeyAuth() {
        when(apiKeyRepository.validate("valid-api-key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("events:read")).build());
    }

    @Test
    void listEvents_autoScopedToTenant_returns200() throws Exception {
        setupApiKeyAuth();
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(eq("t1"), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/events")
                        .header("X-Cycles-API-Key", "valid-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray());

        verify(eventService).list(eq("t1"), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void listEvents_noApiKey_returns401() throws Exception {
        mockMvc.perform(get("/v1/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listEvents_withFilters_passes() throws Exception {
        setupApiKeyAuth();
        EventListResponse response = EventListResponse.builder()
            .events(List.of()).hasMore(false).build();
        when(eventService.list(eq("t1"), eq("budget.created"), eq("BUDGET"), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(response);

        mockMvc.perform(get("/v1/events")
                        .header("X-Cycles-API-Key", "valid-api-key")
                        .param("event_type", "budget.created")
                        .param("category", "BUDGET"))
                .andExpect(status().isOk());

        verify(eventService).list(eq("t1"), eq("budget.created"), eq("BUDGET"), any(), any(), any(), any(), any(), anyInt());
    }
}
