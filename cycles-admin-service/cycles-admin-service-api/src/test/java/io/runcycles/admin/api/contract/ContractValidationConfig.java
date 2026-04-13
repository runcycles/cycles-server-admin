package io.runcycles.admin.api.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
                .build();
        // Response-status coverage is now complete on the admin surface as of
        // cycles-protocol v0.1.25.12 (PR #35): 400 on all 43 operations (#33),
        // plus 404 on PATCH tenants/{id}, and 409 on POST policies + DELETE
        // api-keys/{id}. Every status the server can emit is documented, so the
        // validator rejects any undocumented status with full strictness —
        // no escape hatch.
        OpenApiInteractionValidator validator = OpenApiInteractionValidator
                .createForInlineApiSpecification(ContractSpecLoader.loadSpec())
                .withLevelResolver(levels)
                .build();
        // Parse the spec once to extract (method, path-template) pairs so we can map
        // actual request URIs back to their spec template and record coverage.
        List<SpecOperation> specOperations = loadSpecOperations(ContractSpecLoader.loadSpec());

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
            // Record coverage for any request that hit a spec-defined path, regardless
            // of whether the response has a body (204 No Content on DELETE still counts).
            String method = result.getRequest().getMethod();
            for (SpecOperation op : specOperations) {
                if (op.method.equalsIgnoreCase(method) && op.matches(path)) {
                    SpecCoverageCollector.record(op.method, op.pathTemplate);
                    break;
                }
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

    private static List<SpecOperation> loadSpecOperations(String specYaml) {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(false);
        OpenAPI api = new OpenAPIV3Parser().readContents(specYaml, null, opts).getOpenAPI();
        List<SpecOperation> ops = new ArrayList<>();
        if (api == null || api.getPaths() == null) return ops;
        api.getPaths().forEach((pathTemplate, item) -> {
            if (item.getGet() != null)    ops.add(new SpecOperation("GET",    pathTemplate));
            if (item.getPost() != null)   ops.add(new SpecOperation("POST",   pathTemplate));
            if (item.getPut() != null)    ops.add(new SpecOperation("PUT",    pathTemplate));
            if (item.getPatch() != null)  ops.add(new SpecOperation("PATCH",  pathTemplate));
            if (item.getDelete() != null) ops.add(new SpecOperation("DELETE", pathTemplate));
        });
        return ops;
    }

    private static final class SpecOperation {
        final String method;
        final String pathTemplate;
        final Pattern regex;

        SpecOperation(String method, String pathTemplate) {
            this.method = method;
            this.pathTemplate = pathTemplate;
            // Convert /v1/foo/{bar}/baz -> ^/v1/foo/[^/]+/baz$
            String re = "^" + pathTemplate.replaceAll("\\{[^}]+}", "[^/]+") + "$";
            this.regex = Pattern.compile(re);
        }

        boolean matches(String actualPath) {
            return regex.matcher(actualPath).matches();
        }
    }
}
