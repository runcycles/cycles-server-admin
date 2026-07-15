package io.runcycles.admin.api.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TraceContextFilterTest {

    private static final Pattern TRACE_ID_32_HEX = Pattern.compile("^[0-9a-f]{32}$");
    private final TraceContextFilter filter = new TraceContextFilter();

    @Test
    void validTraceparent_wins_and_flagsPreserved() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE))
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE)).isEqualTo("01");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(true);
        assertThat(res.getHeader(TraceContextFilter.TRACE_ID_HEADER))
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        verify(chain).doFilter(req, res);
    }

    @Test
    void sampledOffFlag_isPreserved() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE)).isEqualTo("00");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(true);
    }

    @Test
    void malformedTraceparent_fallsThroughToCyclesHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER, "garbage-not-a-real-header");
        req.addHeader(TraceContextFilter.TRACE_ID_HEADER, "abcdef0123456789abcdef0123456789");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE))
                .isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE))
                .isEqualTo(TraceContextFilter.DEFAULT_TRACE_FLAGS);
        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void allZeroTraceparent_isRejected_andFallsThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
                "00-00000000000000000000000000000000-b7ad6b7169203331-01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        String traceId = (String) req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE);
        assertThat(traceId).matches(TRACE_ID_32_HEX);
        assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void allZeroSpanId_isRejected_andFallsThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
                "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void uppercaseTraceparent_isRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
                "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void wrongVersionByte_isRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
                "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void cyclesHeaderOnly_used_withDefaultFlags() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACE_ID_HEADER, "deadbeefdeadbeefdeadbeefdeadbeef");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE))
                .isEqualTo("deadbeefdeadbeefdeadbeefdeadbeef");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE)).isEqualTo("01");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void allZeroCyclesHeader_isRejected_andGenerated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACE_ID_HEADER, "00000000000000000000000000000000");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        String traceId = (String) req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE);
        assertThat(traceId).matches(TRACE_ID_32_HEX);
        assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void malformedCyclesHeader_isRejected_andGenerated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACE_ID_HEADER, "not-a-trace-id");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        String traceId = (String) req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE);
        assertThat(traceId).matches(TRACE_ID_32_HEX);
    }

    @Test
    void noHeaders_generatesFreshTraceId_32LowercaseHexNonZero() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        String traceId = (String) req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE);
        assertThat(traceId).matches(TRACE_ID_32_HEX);
        assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
        assertThat(res.getHeader(TraceContextFilter.TRACE_ID_HEADER)).isEqualTo(traceId);
        assertThat(req.getAttribute(TraceContextFilter.TRACE_FLAGS_ATTRIBUTE)).isEqualTo("01");
        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void generatedTraceIds_areUnique() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, mock(FilterChain.class));
            seen.add((String) req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE));
        }
        assertThat(seen).hasSize(50);
    }

    @Test
    void validTraceparent_andValidCyclesHeader_traceparentWins() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        req.addHeader(TraceContextFilter.TRACE_ID_HEADER, "deadbeefdeadbeefdeadbeefdeadbeef");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE))
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void responseHeader_emittedEvenWithoutInboundHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(res.getHeader(TraceContextFilter.TRACE_ID_HEADER)).isNotNull();
        assertThat(res.getHeader(TraceContextFilter.TRACE_ID_HEADER)).matches(TRACE_ID_32_HEX);
    }

    @Test
    void blankTraceparent_fallsThroughToCyclesHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER, "   ");
        req.addHeader(TraceContextFilter.TRACE_ID_HEADER, "abcdef0123456789abcdef0123456789");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE))
            .isEqualTo("abcdef0123456789abcdef0123456789");
    }

    @Test
    void malformedSpanId_isRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
            "00-0af7651916cd43dd8448eb211c80319c-NOTHEX0000000000-01");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void malformedTraceFlags_areRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceContextFilter.TRACEPARENT_HEADER,
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-zz");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(req.getAttribute(TraceContextFilter.TRACE_PARENT_VALID_ATTRIBUTE)).isEqualTo(false);
    }
}
