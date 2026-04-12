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
        // Request-side validation is noisy in tests. @Valid already enforces real request
        // constraints in production, and many tests deliberately send bad requests to
        // verify error responses (missing auth → 401, malformed body → 400, etc.). The
        // contract we enforce here is RESPONSE shape — when the server responds, does the
        // body match the spec (success schema for 2xx, ErrorResponse for 4xx/5xx)?
        LevelResolver levels = LevelResolver.create()
                .withLevel("validation.request.parameter.schema.maximum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.minimum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.invalidJson", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.enum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.missing", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.required", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.invalidJson", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.enum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.pattern", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
                // Spec doesn't document 400 on most paths, but server correctly returns 400
                // on malformed input. Ignoring means un-documented-status responses pass
                // through unvalidated, but documented-status error responses still validate
                // against ErrorResponse. Spec-side follow-up to add 400 entries to cycles-protocol.
                .withLevel("validation.response.status.unknown", ValidationReport.Level.IGNORE)
                .build();
        OpenApiInteractionValidator validator = OpenApiInteractionValidator
                .createForInlineApiSpecification(ContractSpecLoader.loadSpec())
                .withLevelResolver(levels)
                .build();
        ResultMatcher full = new OpenApiMatchers().isValid(validator);
        // Validate every JSON response on spec-defined paths:
        //   - 2xx: body must match the operation's success response schema.
        //   - 4xx / 5xx with JSON body: body must match ErrorResponse schema
        //     (required: [error, message, request_id], additionalProperties:false,
        //     error must be a valid ErrorCode enum value).
        // Skip when:
        //   - Path is infrastructure (not in admin spec by design): /api-docs,
        //     /v3/api-docs, /swagger-ui, /actuator.
        //   - Response has no JSON body (e.g. 204 No Content, 401 with empty
        //     Spring-default body, non-JSON content types).
        ResultMatcher onSpecPaths = result -> {
            String path = result.getRequest().getRequestURI();
            if (path.startsWith("/api-docs")
                    || path.startsWith("/v3/api-docs")
                    || path.startsWith("/swagger-ui")
                    || path.startsWith("/actuator")) {
                return;
            }
            // Skip non-JSON responses — validator would misfire on empty/HTML bodies.
            String contentType = result.getResponse().getContentType();
            if (contentType == null || !contentType.contains("json")) return;
            if (result.getResponse().getContentLength() == 0
                    && (result.getResponse().getContentAsByteArray() == null
                        || result.getResponse().getContentAsByteArray().length == 0)) {
                return;
            }
            full.match(result);
        };
        return builder -> builder.alwaysExpect(onSpecPaths);
    }
}
