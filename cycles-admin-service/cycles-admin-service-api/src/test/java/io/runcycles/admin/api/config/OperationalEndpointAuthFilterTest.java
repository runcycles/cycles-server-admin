package io.runcycles.admin.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalEndpointAuthFilterTest {

    private OperationalEndpointAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new OperationalEndpointAuthFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "adminApiKey", "admin-secret-key");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void livenessProbe_withoutAdminKey_passesThrough() throws Exception {
        request.setRequestURI("/actuator/health/liveness");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void readinessProbe_withoutAdminKey_passesThrough() throws Exception {
        request.setRequestURI("/actuator/health/readiness");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void prometheus_withoutAdminKey_returns401() throws Exception {
        request.setRequestURI("/actuator/prometheus");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"error\":\"UNAUTHORIZED\"");
    }

    @Test
    void apiDocs_withInvalidAdminKey_returns401() throws Exception {
        request.setRequestURI("/api-docs");
        request.addHeader("X-Admin-API-Key", "wrong-key");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void actuatorInfo_withValidAdminKey_passesThrough() throws Exception {
        request.setRequestURI("/actuator/info");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void protectedEndpoint_withMissingConfiguredAdminKey_returns500() throws Exception {
        ReflectionTestUtils.setField(filter, "adminApiKey", "");
        request.setRequestURI("/actuator/info");
        request.addHeader("X-Admin-API-Key", "admin-secret-key");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("Server misconfiguration");
    }

    @Test
    void nullConfiguredKeyAndBlankPresentedKeyAreRejected() throws Exception {
        ReflectionTestUtils.setField(filter, "adminApiKey", null);
        request.setRequestURI("/actuator/info");
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(500);

        ReflectionTestUtils.setField(filter, "adminApiKey", "admin-secret-key");
        request.addHeader("X-Admin-API-Key", " ");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void errorResponsePreservesExistingRequestAndTraceIdentifiers() throws Exception {
        request.setRequestURI("/swagger/index.html");
        request.setAttribute("requestId", "req-existing");
        request.setAttribute("traceId", "trace-existing");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getContentAsString()).contains("req-existing", "trace-existing");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "requiresProtection", (Object) null))
            .isFalse();
    }
}
