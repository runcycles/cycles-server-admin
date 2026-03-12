package io.runcycles.admin.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.assertj.core.api.Assertions.assertThat;
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
}
