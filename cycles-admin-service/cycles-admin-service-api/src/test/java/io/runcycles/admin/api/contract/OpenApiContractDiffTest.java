package io.runcycles.admin.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.DiffResult;
import org.openapitools.openapidiff.core.model.Endpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 contract check — STRUCTURAL DIFF.
 *
 * <p>Diffs SpringDoc's generated {@code /api-docs} output (the server's
 * self-declared OpenAPI surface) against the authoritative admin spec on
 * {@code cycles-protocol@main}. Catches the drift class that runtime
 * validation can't see: endpoints that exist in the spec but aren't
 * implemented on the server (or vice versa), and operation-level drift
 * in paths both have.
 *
 * <p>Semantic mapping to openapi-diff:
 * <ul>
 *   <li>OLD = pinned spec (authoritative)</li>
 *   <li>NEW = server {@code /api-docs} (reality)</li>
 *   <li>{@code missingEndpoints} = in spec, missing on server → FAIL</li>
 *   <li>{@code newEndpoints} = on server, missing from spec → FAIL
 *       (undocumented surface)</li>
 *   <li>{@code changedOperations} = in both, but operation signature diverges → FAIL</li>
 * </ul>
 *
 * <p>Respects the same enablement gate as the runtime validator
 * ({@link ContractValidationConfig#validationEnabled()}). Skipped when
 * gate is OFF (offline dev).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // JedisPool isn't actually instantiated because we mock it, but
        // the property resolver runs at context build time, so provide
        // defaults to avoid "Could not resolve placeholder" failures.
        "redis.host=localhost",
        "redis.port=6379",
        "redis.password=",
        "admin.api-key=test-admin-key"
})
class OpenApiContractDiffTest {

    /*
     * KNOWN-MISSING allowlist: spec operations declared by v0.1.25.22 but
     * not yet implemented on the server. Remove entries as the
     * corresponding release lands. See SpecCoverageReportTest for the
     * mirrored list.
     *
     * v0.1.25.26 closed the bulk-action gap — allowlist is now empty.
     */
    private static final Set<String> KNOWN_MISSING = Set.of();

    @Autowired private MockMvc mockMvc;
    @MockitoBean private JedisPool jedisPool;

    @Test
    @EnabledIf(value = "io.runcycles.admin.api.contract.ContractValidationConfig#validationEnabled",
               disabledReason = "contract.validation.enabled=false — skipping structural diff")
    void serverApiDocs_structurallyMatchesPinnedSpec() throws Exception {
        // SpringDoc: /api-docs when springdoc.api-docs.path is overridden in
        // application.properties (prod config), or /v3/api-docs default.
        String serverJson = fetchApiDocs();

        String specYaml = ContractSpecLoader.loadSpec();
        ChangedOpenApi diff = OpenApiCompare.fromContents(specYaml, serverJson);

        List<Endpoint> missing = diff.getMissingEndpoints().stream()
                .filter(e -> !KNOWN_MISSING.contains(e.getMethod() + " " + e.getPathUrl()))
                .collect(Collectors.toList());
        List<Endpoint> extra = diff.getNewEndpoints();
        // Only fail on INCOMPATIBLE (spec-breaking) operation-level changes. SpringDoc's
        // auto-generated OpenAPI doesn't exactly match the hand-written spec at deep
        // $ref / description / parameter-styling level, so filtering noise on benign
        // (COMPATIBLE / METADATA) changes is essential. Deep response-schema drift is
        // the runtime validator's job anyway — structural diff's unique value is
        // catching surface-level issues: missing endpoints, extra endpoints, and
        // breaking signature changes on shared endpoints.
        List<ChangedOperation> breakingOps = diff.getChangedOperations().stream()
                .filter(op -> op.isCoreChanged() == DiffResult.INCOMPATIBLE)
                .collect(Collectors.toList());
        List<ChangedOperation> compatibleOps = diff.getChangedOperations().stream()
                .filter(op -> op.isCoreChanged() == DiffResult.COMPATIBLE)
                .collect(Collectors.toList());

        // Observability-only: log COMPATIBLE-level drift (non-breaking). These are
        // additions the server made that the spec hasn't absorbed yet — usually new
        // optional fields, new response codes, or SpringDoc rendering differences.
        // We don't fail the build on them; the runtime validator catches any actual
        // shape issues. Printing them gives reviewers visibility without noise.
        if (!compatibleOps.isEmpty()) {
            System.out.printf("%n[structural diff] %d non-breaking (COMPATIBLE) drift items — " +
                            "spec trailing server. No action required unless they indicate " +
                            "missing spec updates:%n", compatibleOps.size());
            compatibleOps.forEach(op ->
                    System.out.println("  [compat] " + op.getHttpMethod() + " " + op.getPathUrl()));
        }

        assertAll("structural diff between spec (cycles-protocol@main) and server /api-docs",
                () -> assertTrue(missing.isEmpty(),
                        "Spec operations MISSING on server (server doesn't implement these): " + formatEndpoints(missing)),
                () -> assertTrue(extra.isEmpty(),
                        "Server operations NOT in spec (undocumented admin surface): " + formatEndpoints(extra)),
                () -> assertTrue(breakingOps.isEmpty(),
                        "Operations with BREAKING signature divergence (spec requires X, server does Y): " + formatChanged(breakingOps))
        );
    }

    /**
     * v0.1.25.27 contract spot-check. The {@code COMPATIBLE}-level filter in
     * the structural diff above lets through parameter-count drift on
     * existing operations (a server missing an optional query param is a
     * non-breaking diff). That's the hole this test closes for the specific
     * params the v0.1.25.24 spec added to {@code listAuditLogs}: if a
     * future refactor drops an {@code @RequestParam} annotation, the
     * structural diff stays green but this test goes red.
     *
     * <p>Asserts the SpringDoc {@code /api-docs} output lists every new
     * query parameter (plus the promoted scalar→array types) on
     * {@code GET /v1/admin/audit/logs}.
     */
    @Test
    @EnabledIf(value = "io.runcycles.admin.api.contract.ContractValidationConfig#validationEnabled",
               disabledReason = "contract.validation.enabled=false — skipping api-docs param check")
    void listAuditLogs_exposesAllV25_27FilterParams_onApiDocs() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(fetchApiDocs());
        JsonNode listAuditLogs = root.path("paths").path("/v1/admin/audit/logs").path("get");
        assertNotNull(listAuditLogs, "listAuditLogs operation missing from /api-docs");
        assertEquals("listAuditLogs", listAuditLogs.path("operationId").asText(),
                "operationId drift on /v1/admin/audit/logs GET");

        JsonNode paramsNode = listAuditLogs.path("parameters");
        assertTrue(paramsNode.isArray() && paramsNode.size() > 0,
                "listAuditLogs exposes no parameters in /api-docs");

        Set<String> declaredNames = new HashSet<>();
        List<JsonNode> paramList = new ArrayList<>();
        paramsNode.forEach(p -> {
            declaredNames.add(p.path("name").asText());
            paramList.add(p);
        });

        // The four new filter params introduced in v0.1.25.24.
        Set<String> required = Set.of(
                "error_code", "error_code_exclude", "status_min", "status_max");
        List<String> missing = required.stream()
                .filter(name -> !declaredNames.contains(name))
                .collect(Collectors.toList());
        assertTrue(missing.isEmpty(),
                "listAuditLogs missing v0.1.25.24 query params on /api-docs: " + missing);

        // operation and resource_type were promoted scalar→array; confirm
        // SpringDoc reflects the array shape so consumers regenerating
        // clients from /api-docs get List<String> signatures.
        assertEquals("array", paramTypeOf(paramList, "operation"),
                "operation param must be array (promoted scalar→array in v0.1.25.24)");
        assertEquals("array", paramTypeOf(paramList, "resource_type"),
                "resource_type param must be array (promoted scalar→array in v0.1.25.24)");

        // status_min / status_max MUST be integer with [100, 599] bounds.
        assertIntegerWithBounds(paramList, "status_min");
        assertIntegerWithBounds(paramList, "status_max");
    }

    private static String paramTypeOf(List<JsonNode> params, String name) {
        return params.stream()
                .filter(p -> name.equals(p.path("name").asText()))
                .findFirst()
                .map(p -> p.path("schema").path("type").asText())
                .orElseThrow(() -> new AssertionError(name + " param missing on listAuditLogs"));
    }

    private static void assertIntegerWithBounds(List<JsonNode> params, String name) {
        JsonNode schema = params.stream()
                .filter(p -> name.equals(p.path("name").asText()))
                .findFirst()
                .map(p -> p.path("schema"))
                .orElseThrow(() -> new AssertionError(name + " missing on listAuditLogs"));
        assertEquals("integer", schema.path("type").asText(), name + " must be integer");
        assertEquals(100, schema.path("minimum").asInt(), name + " minimum must be 100");
        assertEquals(599, schema.path("maximum").asInt(), name + " maximum must be 599");
    }

    private String fetchApiDocs() throws Exception {
        for (String path : new String[]{"/api-docs", "/v3/api-docs"}) {
            var result = mockMvc.perform(get(path)).andReturn();
            if (result.getResponse().getStatus() == 200) {
                return result.getResponse().getContentAsString();
            }
        }
        throw new AssertionError("SpringDoc /api-docs and /v3/api-docs both unavailable");
    }

    private static String formatEndpoints(List<Endpoint> endpoints) {
        if (endpoints.isEmpty()) return "[]";
        return endpoints.stream()
                .map(e -> e.getMethod() + " " + e.getPathUrl())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String formatChanged(List<ChangedOperation> ops) {
        if (ops.isEmpty()) return "[]";
        return ops.stream()
                .map(o -> o.getHttpMethod() + " " + o.getPathUrl())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
