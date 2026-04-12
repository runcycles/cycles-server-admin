package io.runcycles.admin.api.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Test configuration that attaches a Swagger Request Validator matcher to
 * every MockMvc request. When enabled, any 2xx response whose body doesn't
 * conform to the pinned admin spec fails the test.
 *
 * <p>Import from controller tests via {@code @Import(ContractValidationConfig.class)}
 * alongside the existing {@code MetricsTestConfiguration}.
 *
 * <p><b>Enablement gate:</b> ON by default as of v0.1.25.11. Disable
 * with {@code -Dcontract.validation.enabled=false} (or env
 * {@code CONTRACT_VALIDATION_ENABLED=false}) for offline / air-gapped
 * dev where the cycles-protocol@main fetch would fail.
 *
 * <p>When the gate is OFF the customizer is a no-op — no spec fetch, no
 * matcher attached — so tests are unaffected by network or cache state.
 *
 * <p>The spec itself is fetched by {@link ContractSpecLoader} from
 * cycles-protocol@main and cached to {@code target/contract/}.
 */
@TestConfiguration
public class ContractValidationConfig {

    /**
     * Defaults to true. Disable for offline dev with
     * -Dcontract.validation.enabled=false or env CONTRACT_VALIDATION_ENABLED=false.
     */
    public static boolean validationEnabled() {
        String prop = System.getProperty("contract.validation.enabled");
        if (prop != null) return Boolean.parseBoolean(prop);
        String env = System.getenv("CONTRACT_VALIDATION_ENABLED");
        if (env != null) return Boolean.parseBoolean(env);
        return true;
    }

    @Bean
    public MockMvcBuilderCustomizer contractValidatingCustomizer() {
        if (!validationEnabled()) {
            // No-op: don't fetch the spec, don't attach a matcher. Keeps
            // default test runs hermetic and independent of cycles-protocol
            // network availability.
            return builder -> { };
        }
        // Request-side validation is noisy in tests: @Valid already enforces real request
        // constraints in production, and several tests deliberately send out-of-range query
        // params to verify server clamping. The contract we're enforcing here is the
        // RESPONSE shape — when the server says 200, does the body match the spec?
        LevelResolver levels = LevelResolver.create()
                .withLevel("validation.request.parameter.schema.maximum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.minimum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.invalidJson", ValidationReport.Level.IGNORE)
                .build();
        OpenApiInteractionValidator validator = OpenApiInteractionValidator
                .createForInlineApiSpecification(ContractSpecLoader.loadSpec())
                .withLevelResolver(levels)
                .build();
        ResultMatcher full = new OpenApiMatchers().isValid(validator);
        // Only validate 2xx responses. 4xx/5xx are error paths — the server correctly
        // rejected a bad request, and re-validating the deliberately-broken request is
        // noise. What we DO catch: 2xx responses whose body shape drifts from the spec,
        // and 2xx requests whose body violates spec constraints.
        ResultMatcher onlyOnSuccess = result -> {
            int status = result.getResponse().getStatus();
            if (status >= 200 && status < 300) {
                full.match(result);
            }
        };
        return builder -> builder.alwaysExpect(onlyOnSuccess);
    }
}
