package io.runcycles.admin.api.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Provides a {@link SimpleMeterRegistry} bean for @WebMvcTest slice tests.
 *
 * <p>Spring Boot disables observability auto-configuration in slice tests by default
 * (via {@code DisableObservabilityContextCustomizer}), which means {@link MeterRegistry}
 * is not in the context. Services that depend on it (e.g. EventService, WebhookDispatchService)
 * fail to load. Import this configuration to supply an in-memory registry.
 */
@TestConfiguration
public class MetricsTestConfiguration {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
