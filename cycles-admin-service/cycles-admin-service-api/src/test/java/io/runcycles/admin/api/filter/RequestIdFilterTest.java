package io.runcycles.admin.api.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RequestIdFilterTest {

    private RequestIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new RequestIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    @Test
    void doFilterInternal_setsRequestIdAttribute() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        assertThat(requestId).isNotNull();
        assertThat(requestId.toString()).isNotBlank();
    }

    @Test
    void doFilterInternal_setsResponseHeader() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        String headerValue = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(headerValue).isNotNull().isNotBlank();
    }

    @Test
    void doFilterInternal_requestIdMatchesBetweenAttributeAndHeader() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        String attribute = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString();
        String header = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(attribute).isEqualTo(header);
    }

    @Test
    void doFilterInternal_callsFilterChain() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_generatesUniqueIdsPerRequest() throws Exception {
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        MockHttpServletResponse response2 = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request2, response2, filterChain);

        String id1 = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString();
        String id2 = request2.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void doFilterInternal_requestIdIsValidUuid() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString();
        assertThat(requestId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void doFilterInternal_honorsClientProvidedRequestId() throws Exception {
        request.addHeader("X-Request-Id", "client-trace-123");
        filter.doFilterInternal(request, response, filterChain);

        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString();
        assertThat(requestId).isEqualTo("client-trace-123");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-trace-123");
    }

    @Test
    void doFilterInternal_ignoresBlankClientRequestId() throws Exception {
        request.addHeader("X-Request-Id", "   ");
        filter.doFilterInternal(request, response, filterChain);

        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString();
        assertThat(requestId).isNotEqualTo("   ");
        assertThat(requestId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void constants_haveExpectedValues() {
        assertThat(RequestIdFilter.REQUEST_ID_HEADER).isEqualTo("X-Request-Id");
        assertThat(RequestIdFilter.REQUEST_ID_ATTRIBUTE).isEqualTo("requestId");
    }
}
