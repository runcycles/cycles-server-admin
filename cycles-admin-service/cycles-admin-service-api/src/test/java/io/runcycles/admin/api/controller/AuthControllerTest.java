package io.runcycles.admin.api.controller;

import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private JedisPool jedisPool;

    private static final String ADMIN_KEY = "test-admin-key";

    @Test
    void validate_validKey_returns200WithValid() throws Exception {
        when(apiKeyRepository.validate("gov_valid_key")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(true).tenantId("t1").keyId("key_1")
                        .permissions(List.of("balances:read"))
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"gov_valid_key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.tenant_id").value("t1"))
                .andExpect(jsonPath("$.key_id").value("key_1"));
    }

    @Test
    void validate_invalidKey_returns200WithInvalid() throws Exception {
        when(apiKeyRepository.validate("invalid")).thenReturn(
                ApiKeyValidationResponse.builder()
                        .valid(false).tenantId("").reason("KEY_NOT_FOUND")
                        .build());

        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"invalid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("KEY_NOT_FOUND"));
    }

    @Test
    void validate_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validate_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/v1/auth/validate")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validate_noAdminKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_secret\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }
}
