package io.runcycles.admin.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * W3C Trace Context inbound/outbound handling.
 *
 * <p>Extracts a 32-hex trace id from the first valid header in precedence order:
 * <ol>
 *   <li>{@code traceparent} (W3C Trace Context §3.2, version 00, non-all-zero trace-id and span-id)
 *   <li>{@code X-Cycles-Trace-Id} (32 lowercase hex chars, non-all-zero)
 *   <li>Server-generated (128 random bits, all-zero re-rolled)
 * </ol>
 *
 * <p>Malformed correlation headers MUST be treated as absent — never reject the request.
 * If both {@code traceparent} and {@code X-Cycles-Trace-Id} are valid but disagree,
 * {@code traceparent} wins (OpenTelemetry interop).
 *
 * <p>Also captures inbound trace-flags (preserved on outbound webhooks when the inbound
 * {@code traceparent} was valid; default {@code 01} otherwise) so downstream code can
 * construct conformant outbound headers without re-parsing.
 *
 * <p>Emits {@code X-Cycles-Trace-Id} on every response (2xx / 4xx / 5xx).
 *
 * <p>Ordered AFTER {@link RequestIdFilter} so that {@code request_id} is available first
 * — both correlation identifiers coexist on the request and response.
 *
 * @since 0.1.25.31
 */
@Component
@Order(2)
public class TraceContextFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Cycles-Trace-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";

    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    public static final String TRACE_FLAGS_ATTRIBUTE = "traceFlags";
    public static final String TRACE_PARENT_VALID_ATTRIBUTE = "traceparentInboundValid";

    /** Default trace-flags byte when no valid inbound traceparent was provided. */
    public static final String DEFAULT_TRACE_FLAGS = "01";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern FLAGS_PATTERN = Pattern.compile("^[0-9a-f]{2}$");
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_SPAN_ID = "0000000000000000";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Extracted extracted = extract(request);
        request.setAttribute(TRACE_ID_ATTRIBUTE, extracted.traceId);
        request.setAttribute(TRACE_FLAGS_ATTRIBUTE, extracted.traceFlags);
        request.setAttribute(TRACE_PARENT_VALID_ATTRIBUTE, extracted.traceparentInboundValid);
        response.setHeader(TRACE_ID_HEADER, extracted.traceId);
        chain.doFilter(request, response);
    }

    private Extracted extract(HttpServletRequest request) {
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        ParsedTraceparent parsed = parseTraceparent(traceparent);
        if (parsed != null) {
            return new Extracted(parsed.traceId, parsed.flags, true);
        }
        String fromHeader = request.getHeader(TRACE_ID_HEADER);
        if (isValidTraceId(fromHeader)) {
            return new Extracted(fromHeader, DEFAULT_TRACE_FLAGS, false);
        }
        return new Extracted(generateTraceId(), DEFAULT_TRACE_FLAGS, false);
    }

    private static boolean isValidTraceId(String candidate) {
        return candidate != null
                && TRACE_ID_PATTERN.matcher(candidate).matches()
                && !ALL_ZERO_TRACE_ID.equals(candidate);
    }

    private static ParsedTraceparent parseTraceparent(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String[] parts = header.trim().split("-");
        if (parts.length != 4) {
            return null;
        }
        String version = parts[0];
        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];
        if (!"00".equals(version)) {
            return null;
        }
        if (!TRACE_ID_PATTERN.matcher(traceId).matches() || ALL_ZERO_TRACE_ID.equals(traceId)) {
            return null;
        }
        if (!SPAN_ID_PATTERN.matcher(spanId).matches() || ALL_ZERO_SPAN_ID.equals(spanId)) {
            return null;
        }
        if (!FLAGS_PATTERN.matcher(flags).matches()) {
            return null;
        }
        return new ParsedTraceparent(traceId, flags);
    }

    private static String generateTraceId() {
        byte[] bytes = new byte[16];
        String encoded;
        do {
            RANDOM.nextBytes(bytes);
            encoded = toHex(bytes);
        } while (ALL_ZERO_TRACE_ID.equals(encoded));
        return encoded;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private record Extracted(String traceId, String traceFlags, boolean traceparentInboundValid) {}

    private record ParsedTraceparent(String traceId, String flags) {}
}
