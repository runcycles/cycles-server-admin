package io.runcycles.admin.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebConfigTest {

    @Mock private AuthInterceptor authInterceptor;
    @Mock private InterceptorRegistry registry;
    @Mock private InterceptorRegistration registration;

    @Test
    void addInterceptors_registersAuthInterceptorForV1Paths() {
        when(registry.addInterceptor(authInterceptor)).thenReturn(registration);
        when(registration.addPathPatterns(anyString())).thenReturn(registration);

        WebConfig config = new WebConfig(authInterceptor);
        config.addInterceptors(registry);

        verify(registry).addInterceptor(authInterceptor);
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(registration).addPathPatterns(pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/v1/**");
    }

    @Test
    void addCorsMappings_allowlistsApiKeyAndRequestIdHeaders() {
        WebConfig config = new WebConfig(authInterceptor);
        ReflectionTestUtils.setField(config, "dashboardOrigin", "https://dash.example.com");

        CorsRegistry corsRegistry = mock(CorsRegistry.class);
        CorsRegistration corsReg = mock(CorsRegistration.class, RETURNS_SELF);
        when(corsRegistry.addMapping("/v1/**")).thenReturn(corsReg);

        config.addCorsMappings(corsRegistry);

        // Verify the single origin was parsed and passed through.
        verify(corsReg).allowedOrigins("https://dash.example.com");

        // Capture the headers passed to allowedHeaders — both auth headers and
        // X-Request-Id must be allowlisted, otherwise browser preflight blocks them.
        ArgumentCaptor<String[]> headers = ArgumentCaptor.forClass(String[].class);
        verify(corsReg).allowedHeaders(headers.capture());
        assertThat(headers.getValue())
            .containsExactlyInAnyOrder("X-Admin-API-Key", "X-Cycles-API-Key", "X-Request-Id", "Content-Type");

        // X-Request-Id must be exposed so browser clients can read server-assigned ids.
        ArgumentCaptor<String[]> exposed = ArgumentCaptor.forClass(String[].class);
        verify(corsReg).exposedHeaders(exposed.capture());
        assertThat(exposed.getValue()).containsExactly("X-Request-Id");

        verify(corsReg).allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS");
        verify(corsReg).maxAge(3600L);
    }

    @Test
    void addCorsMappings_parsesCommaSeparatedOriginList() {
        WebConfig config = new WebConfig(authInterceptor);
        ReflectionTestUtils.setField(config, "dashboardOrigin",
            "https://dash.example.com, https://staging.example.com ,https://dev.example.com");

        CorsRegistry corsRegistry = mock(CorsRegistry.class);
        CorsRegistration corsReg = mock(CorsRegistration.class, RETURNS_SELF);
        when(corsRegistry.addMapping(any())).thenReturn(corsReg);

        config.addCorsMappings(corsRegistry);

        // All three origins, whitespace trimmed.
        verify(corsReg).allowedOrigins("https://dash.example.com", "https://staging.example.com", "https://dev.example.com");
    }

    @Test
    void addCorsMappings_ignoresEmptyEntriesInOriginList() {
        WebConfig config = new WebConfig(authInterceptor);
        ReflectionTestUtils.setField(config, "dashboardOrigin", "https://dash.example.com,,  ,https://other.example.com");

        CorsRegistry corsRegistry = mock(CorsRegistry.class);
        CorsRegistration corsReg = mock(CorsRegistration.class, RETURNS_SELF);
        when(corsRegistry.addMapping(any())).thenReturn(corsReg);

        config.addCorsMappings(corsRegistry);

        verify(corsReg).allowedOrigins("https://dash.example.com", "https://other.example.com");
    }
}
