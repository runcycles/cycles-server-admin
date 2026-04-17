package io.runcycles.admin.api.zzz;

import io.runcycles.admin.api.contract.ContractSpecLoader;
import io.runcycles.admin.api.contract.SpecCoverageCollector;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * KNOWN-MISSING allowlist: spec operations declared by
 * cycles-governance-admin v0.1.25.22 that the server has not yet
 * implemented. Remove entries as the corresponding release lands.
 *
 * bulk-action endpoints → planned for server release 0.1.25.26
 * (see review-admin-0-1-25-spec-indexed-dewdrop.md, Gaps 2 and 3).
 */

/**
 * Phase-A coverage assertion. Runs LAST by filename-alphabetical order —
 * the {@code zzz} sub-package name sorts after every other
 * {@code io.runcycles.admin.api.*} test package (config, contract,
 * controller, exception, filter, service, support), so Surefire with
 * {@code <runOrder>alphabetical</runOrder>} executes this class after
 * all other api-module tests.
 *
 * <p>At that point, {@link SpecCoverageCollector} holds every
 * {@code "METHOD /path/template"} that any contract-validated MockMvc
 * request covered. This test compares the covered set against the spec's
 * declared operations and fails the build on any gap.
 *
 * <p>Purpose: prevent silent coverage gaps. An endpoint with no test
 * would otherwise pass every contract check (because the runtime
 * validator only fires on requests that actually happen). This test
 * makes absence visible.
 *
 * <p>Gated behind the same {@code contract.validation.enabled} flag as
 * the runtime validator — when validation is off, no requests are
 * recorded, so the report would show everything as missing; skip
 * instead.
 */
class SpecCoverageReportTest {

    private static final Set<String> KNOWN_MISSING = Set.of();

    @Test
    @EnabledIf(value = "io.runcycles.admin.api.contract.ContractValidationConfig#validationEnabled",
               disabledReason = "contract.validation.enabled=false — skipping spec coverage report")
    void everySpecOperation_hasAtLeastOneTest() {
        Set<String> declared = loadDeclaredOperations();
        Set<String> covered = SpecCoverageCollector.covered();
        TreeSet<String> missing = new TreeSet<>(declared);
        missing.removeAll(covered);
        missing.removeAll(KNOWN_MISSING);

        System.out.printf("%n[spec coverage] declared=%d covered=%d missing=%d%n",
                declared.size(), covered.size(), missing.size());

        assertTrue(missing.isEmpty(),
                "Spec operations with ZERO test coverage (add a test that hits these):%n  "
                        .formatted() + String.join("%n  ".formatted(),
                                new ArrayList<>(missing)));
    }

    private Set<String> loadDeclaredOperations() {
        String specYaml = ContractSpecLoader.loadSpec();
        ParseOptions opts = new ParseOptions();
        opts.setResolve(false);
        OpenAPI api = new OpenAPIV3Parser().readContents(specYaml, null, opts).getOpenAPI();
        TreeSet<String> set = new TreeSet<>();
        if (api == null || api.getPaths() == null) return set;
        api.getPaths().forEach((path, item) -> {
            if (item.getGet() != null)    set.add("GET " + path);
            if (item.getPost() != null)   set.add("POST " + path);
            if (item.getPut() != null)    set.add("PUT " + path);
            if (item.getPatch() != null)  set.add("PATCH " + path);
            if (item.getDelete() != null) set.add("DELETE " + path);
        });
        return set;
    }
}
