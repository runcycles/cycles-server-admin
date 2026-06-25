package io.runcycles.admin.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
@Order(3)
public class OperationalEndpointAuthFilter extends OncePerRequestFilter {
    private static final String ADMIN_KEY_HEADER = "X-Admin-API-Key";

    @Value("${admin.api-key:}")
    private String adminApiKey;

    private final ObjectMapper objectMapper;

    public OperationalEndpointAuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!requiresProtection(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (adminApiKey == null || adminApiKey.isBlank()) {
            writeError(request, response, 500, ErrorCode.INTERNAL_ERROR,
                "Server misconfiguration: admin API key not set");
            return;
        }

        String presented = request.getHeader(ADMIN_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            writeError(request, response, 401, ErrorCode.UNAUTHORIZED,
                "Missing " + ADMIN_KEY_HEADER + " header");
            return;
        }

        if (!MessageDigest.isEqual(
                adminApiKey.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            writeError(request, response, 401, ErrorCode.UNAUTHORIZED, "Invalid admin API key");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresProtection(String path) {
        if (path == null) {
            return false;
        }
        if ("/actuator/health/liveness".equals(path) || "/actuator/health/readiness".equals(path)) {
            return false;
        }
        return path.startsWith("/actuator")
            || path.startsWith("/api-docs")
            || path.startsWith("/v3/api-docs")
            || path.startsWith("/swagger");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            int status, ErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse error = ErrorResponse.builder()
            .error(code)
            .message(message)
            .requestId(resolveRequestId(request))
            .traceId(resolveTraceId(request))
            .build();
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (attr != null) {
            return attr.toString();
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, generated);
        return generated;
    }

    private String resolveTraceId(HttpServletRequest request) {
        Object attr = request.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE);
        return attr != null ? attr.toString() : null;
    }
}
