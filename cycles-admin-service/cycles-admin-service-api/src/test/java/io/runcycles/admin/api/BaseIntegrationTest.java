package io.runcycles.admin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.api.contract.ContractValidatingRestTemplateInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;

/**
 * Base class for full-stack integration tests that drive real HTTP through
 * Spring Boot (RANDOM_PORT) against a Testcontainers Redis. Every
 * {@code TestRestTemplate} call is wrapped with
 * {@link ContractValidatingRestTemplateInterceptor}, so responses are
 * contract-validated against the pinned admin spec on
 * {@code cycles-protocol@main} — exactly like the MockMvc unit layer.
 *
 * <p>Why: MockMvc tests use mocked repositories; integration tests drive
 * the full controller → service → Redis → Lua path, producing the widest
 * variety of response shapes in the build. This base class ensures every
 * shape they produce gets validated with zero per-test changes.
 *
 * <p>Admin tests use the admin API key (header {@code X-Admin-API-Key})
 * rather than tenant API keys. Tenant resources (tenants, budgets,
 * api-keys, policies, webhooks) are created via the admin REST API in
 * each test rather than pre-seeded in Redis — that way the Create
 * endpoints themselves get validated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // @Value("${admin.api-key:}") is bound at bean creation time, so use
        // @TestPropertySource (not @DynamicPropertySource) to ensure it's present
        // when AuthInterceptor is instantiated.
        "admin.api-key=test-admin-integration-key",
        "webhook.secret.encryption-key="
})
public abstract class BaseIntegrationTest {

    protected static final String ADMIN_KEY = "test-admin-integration-key";

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void adminProperties(DynamicPropertyRegistry registry) {
        registry.add("redis.host", REDIS::getHost);
        registry.add("redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("redis.password", () -> "");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JedisPool jedisPool;

    protected final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void attachContractValidator() {
        // Integration tests go through real controller/service/Redis/Lua paths, so
        // wiring the interceptor here validates every response against the pinned
        // spec with zero per-test changes. Idempotent guard — adding the same
        // interceptor twice would work but we keep the list small.
        var interceptors = restTemplate.getRestTemplate().getInterceptors();
        boolean present = interceptors.stream()
                .anyMatch(i -> i instanceof ContractValidatingRestTemplateInterceptor);
        if (!present) {
            interceptors.add(new ContractValidatingRestTemplateInterceptor());
        }
    }

    @BeforeEach
    void flushRedis() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
        }
    }

    // ---- HTTP helpers ----

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Headers authenticated with the admin key (for {@code /v1/admin/*} paths). */
    protected HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-API-Key", ADMIN_KEY);
        return headers;
    }

    /** Headers authenticated with a tenant API key (for {@code /v1/*} tenant-plane paths). */
    protected HttpHeaders tenantHeaders(String apiKeySecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Cycles-API-Key", apiKeySecret);
        return headers;
    }

    protected ResponseEntity<Map> adminPost(String path, Map<String, Object> body) {
        return restTemplate.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()), Map.class);
    }

    protected ResponseEntity<Map> adminPatch(String path, Map<String, Object> body) {
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PATCH,
                new HttpEntity<>(body, adminHeaders()), Map.class);
    }

    protected ResponseEntity<Map> adminGet(String path) {
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), Map.class);
    }

    protected ResponseEntity<Map> adminDelete(String path) {
        return restTemplate.exchange(baseUrl() + path, HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()), Map.class);
    }
}
