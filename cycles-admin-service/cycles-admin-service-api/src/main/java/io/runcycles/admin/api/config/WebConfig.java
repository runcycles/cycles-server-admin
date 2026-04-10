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
        LOG.info("CORS configured for origins: {}", String.join(", ", origins));
        registry.addMapping("/v1/**")
            .allowedOrigins(origins)
            .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
            // Both auth headers must be allowlisted or browser preflight rejects them.
            // X-Request-Id lets clients propagate a correlation id through the filter chain.
            .allowedHeaders("X-Admin-API-Key", "X-Cycles-API-Key", "X-Request-Id", "Content-Type")
            // X-Request-Id is also exposed so browser clients can read the server-assigned id
            // when they didn't send one.
            .exposedHeaders("X-Request-Id")
            .maxAge(3600);
    }
}
