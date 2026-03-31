package io.runcycles.admin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookSecurityConfigController.class)
class WebhookSecurityConfigControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebhookSecurityConfigRepository repository;
    @MockitoBean private AuditRepository auditRepository;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void getConfig_returns200() throws Exception {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder()
            .allowHttp(false)
            .blockedCidrRanges(List.of("10.0.0.0/8", "127.0.0.0/8"))
            .allowedUrlPatterns(List.of("https://*.example.com/*"))
            .build();
        when(repository.get()).thenReturn(config);

        mockMvc.perform(get("/v1/admin/config/webhook-security")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allow_http").value(false))
                .andExpect(jsonPath("$.blocked_cidr_ranges").isArray())
                .andExpect(jsonPath("$.allowed_url_patterns[0]").value("https://*.example.com/*"));
    }

    @Test
    void getConfig_noAdminKey_returns401() throws Exception {
        mockMvc.perform(get("/v1/admin/config/webhook-security"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateConfig_returns200() throws Exception {
        WebhookSecurityConfig updated = WebhookSecurityConfig.builder()
            .allowHttp(true)
            .blockedCidrRanges(List.of("10.0.0.0/8"))
            .build();
        when(repository.get()).thenReturn(updated);

        mockMvc.perform(put("/v1/admin/config/webhook-security")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allow_http\":true,\"blocked_cidr_ranges\":[\"10.0.0.0/8\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allow_http").value(true));

        verify(repository).save(any());
    }

    @Test
    void updateConfig_noAdminKey_returns401() throws Exception {
        mockMvc.perform(put("/v1/admin/config/webhook-security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allow_http\":true}"))
                .andExpect(status().isUnauthorized());
    }
}
