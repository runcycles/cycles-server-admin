package io.runcycles.admin.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private static final Logger LOG = LoggerFactory.getLogger(WebConfig.class);

    private final AuthInterceptor authInterceptor;

    /**
     * Comma-separated list of origins allowed to call the admin API from a browser.
     * Default is the local Vite dev server for the dashboard. In production, set
     * via {@code DASHBOARD_CORS_ORIGIN} (e.g. {@code https://dash.example.com}) or
     * a comma list (e.g. {@code https://dash.example.com,https://staging.example.com}).
     */
    @Value("${dashboard.cors.origin:http://localhost:5173}")
    private String dashboardOrigin;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/v1/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(dashboardOrigin.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
        LOG.info("Admin CORS configured: path=/v1/** origin_count={} origins={} allowed_methods=GET,POST,PUT,PATCH,DELETE,OPTIONS",
            origins.length, String.join(",", origins));
        registry.addMapping("/v1/**")
            .allowedOrigins(origins)
            // PUT must be in this list because PUT /v1/admin/config/webhook-security
            // is the spec-defined method for updating the webhook security config.
            // Without PUT here, the browser CORS preflight rejects the request
            // before it reaches the AuthInterceptor — the user sees a 403 with
            // zero admin-server logs (the rejection happens in Spring's CorsFilter,
            // not the application). See dashboard issue #30.
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            // Both auth headers must be allowlisted or browser preflight rejects them.
            // X-Request-Id lets clients propagate a correlation id through the filter chain.
            .allowedHeaders("X-Admin-API-Key", "X-Cycles-API-Key", "X-Request-Id",
                "X-Cycles-Trace-Id", "traceparent", "tracestate", "Content-Type")
            // Correlation and cascade-progress headers must be readable by browser operators.
            .exposedHeaders("X-Request-Id", "X-Cycles-Trace-Id",
                "X-Cycles-Cascade-Status", "Retry-After")
            .maxAge(3600);
    }
}
