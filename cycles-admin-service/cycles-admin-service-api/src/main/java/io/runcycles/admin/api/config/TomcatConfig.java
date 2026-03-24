package io.runcycles.admin.api.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Tomcat to allow %2F (encoded forward slash) in URL paths.
 *
 * Budget, policy, and other endpoints use scope as a path variable (e.g.
 * /v1/admin/budgets/{scope}/{unit}/fund). Scopes like "tenant:acme/workspace:prod"
 * contain a forward slash that must be URL-encoded as %2F. Tomcat's default behavior
 * rejects %2F in paths with HTTP 400 to prevent directory traversal. Setting
 * encodedSolidusHandling to PASSTHROUGH keeps %2F as a literal in the raw path so
 * Spring's PathPatternParser treats it as part of a single path segment, then
 * URL-decodes it when extracting the @PathVariable value.
 */
@Configuration
public class TomcatConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedSlashCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector ->
            connector.setEncodedSolidusHandling("passthrough")
        );
    }
}
