package io.runcycles.admin.api.integration;

import io.runcycles.admin.api.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack HTTP + Redis integration test. Drives the real controller →
 * service → Redis (Lua) path via {@code TestRestTemplate}, with every
 * response contract-validated against the pinned admin spec on
 * {@code cycles-protocol@main} via
 * {@link io.runcycles.admin.api.contract.ContractValidatingRestTemplateInterceptor}.
 *
 * <p>This test is deliberately broad rather than exhaustive — it exercises
 * a representative flow across the main admin resource families (tenant,
 * budget, policy, api-key, webhook, events, overview, balances) so the
 * interceptor validates at least one response from each family against
 * the real Redis-backed code path. Unit-level {@code *ControllerTest}s
 * cover per-endpoint edge cases with mocked repos; this test adds the
 * real-Redis-roundtrip coverage that mocks can't.
 *
 * <p>Excluded from the default unit-test run via the {@code *IntegrationTest}
 * filename pattern in {@code pom.xml} surefire {@code <excludes>}. Run via
 * {@code mvn verify -Pintegration-tests} locally (requires Docker for
 * Testcontainers) or via the dedicated CI {@code integration} job.
 */
class AdminFlowIntegrationTest extends BaseIntegrationTest {

    @Test
    void fullAdminFlow_createReadUpdate_acrossAllResourceFamilies() {
        // === ADMIN-PLANE OPERATIONS (X-Admin-API-Key) ===

        // --- 1. Create tenant ---
        ResponseEntity<Map> tenantResp = adminPost("/v1/admin/tenants", Map.of(
                "tenant_id", "tenant-integration",
                "name", "Integration Test Tenant"));
        assertThat(tenantResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(tenantResp.getBody()).containsKey("tenant_id");
        assertThat(tenantResp.getBody().get("status")).isEqualTo("ACTIVE");

        // --- 2. List tenants ---
        assertThat(adminGet("/v1/admin/tenants").getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 3. Get single tenant ---
        assertThat(adminGet("/v1/admin/tenants/tenant-integration").getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 4. Patch tenant status ---
        ResponseEntity<Map> patchTenant = adminPatch("/v1/admin/tenants/tenant-integration",
                Map.of("name", "Renamed Integration Tenant"));
        assertThat(patchTenant.getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 5. Create API key for the tenant (yields a tenant key_secret we'll use below) ---
        ResponseEntity<Map> apiKeyResp = adminPost("/v1/admin/api-keys", Map.of(
                "tenant_id", "tenant-integration",
                "name", "integration-key",
                "permissions", List.of(
                        "balances:read",
                        "budgets:read", "budgets:write",
                        "policies:read", "policies:write",
                        "webhooks:read", "webhooks:write",
                        "events:read")));
        assertThat(apiKeyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String keyId = (String) apiKeyResp.getBody().get("key_id");
        String keySecret = (String) apiKeyResp.getBody().get("key_secret");
        assertThat(keyId).isNotNull();
        assertThat(keySecret).isNotNull();

        // --- 6. List API keys ---
        assertThat(adminGet("/v1/admin/api-keys?tenant_id=tenant-integration").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // --- 7. Patch API key ---
        assertThat(adminPatch("/v1/admin/api-keys/" + keyId, Map.of("name", "renamed-key"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // === TENANT-PLANE OPERATIONS (X-Cycles-API-Key from step 5) ===

        HttpHeaders tenantH = tenantHeaders(keySecret);

        // --- 8. Create budget (tenant auth) ---
        ResponseEntity<Map> budgetResp = restTemplate.exchange(
                baseUrl() + "/v1/admin/budgets", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "scope", "tenant:tenant-integration",
                        "unit", "USD_MICROCENTS",
                        "allocated", Map.of("unit", "USD_MICROCENTS", "amount", 100_000_000)),
                        tenantH),
                Map.class);
        assertThat(budgetResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // --- 9. Lookup budget (admin-key allowlisted for this path) ---
        ResponseEntity<Map> lookupBudget = restTemplate.exchange(
                baseUrl() + "/v1/admin/budgets/lookup?scope=tenant:tenant-integration&unit=USD_MICROCENTS",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                Map.class);
        assertThat(lookupBudget.getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 10. List budgets (admin key allowlisted) ---
        assertThat(adminGet("/v1/admin/budgets?tenant_id=tenant-integration").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // --- 11. Fund the budget (admin key allowlisted) ---
        ResponseEntity<Map> fund = restTemplate.exchange(
                baseUrl() + "/v1/admin/budgets/fund?scope=tenant:tenant-integration&unit=USD_MICROCENTS&tenant_id=tenant-integration",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "operation", "CREDIT",
                        "amount", Map.of("unit", "USD_MICROCENTS", "amount", 50_000_000),
                        "reason", "top-up"),
                        adminHeaders()),
                Map.class);
        assertThat(fund.getStatusCode().is2xxSuccessful()).isTrue();

        // --- 12. Create policy (tenant auth) ---
        ResponseEntity<Map> policyResp = restTemplate.exchange(
                baseUrl() + "/v1/admin/policies", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "integration-policy",
                        "scope_pattern", "tenant:tenant-integration",
                        "description", "integration test policy"),
                        tenantH),
                Map.class);
        assertThat(policyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // --- 13. List policies (admin key allowlisted; requires tenant_id) ---
        assertThat(adminGet("/v1/admin/policies?tenant_id=tenant-integration").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // === ADMIN-PLANE WEBHOOKS + EVENTS + OVERVIEW + AUTH ===

        // --- 14. Create webhook subscription ---
        ResponseEntity<Map> webhookResp = adminPost("/v1/admin/webhooks", Map.of(
                "url", "https://example.com/webhook",
                "event_types", List.of("budget.created", "budget.funded")));
        assertThat(webhookResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String subscriptionId = (String) ((Map<?,?>) webhookResp.getBody().get("subscription")).get("subscription_id");

        // --- 15. Get webhook ---
        assertThat(adminGet("/v1/admin/webhooks/" + subscriptionId).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // --- 16. List webhook deliveries ---
        assertThat(adminGet("/v1/admin/webhooks/" + subscriptionId + "/deliveries")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 17. Delete webhook ---
        assertThat(adminDelete("/v1/admin/webhooks/" + subscriptionId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // --- 17b. Tenant-key introspect (v0.1.25.19 dual-auth path; spec v0.1.25.15) ---
        // Must happen BEFORE step 18 revokes the tenant key. Covers the
        // ApiKeyAuth branch of /v1/auth/introspect and the tenant-shape
        // AuthIntrospectResponse (auth_type=tenant, tenant_id, scope_filter,
        // NORMATIVE capability derivation). Contract validator catches any
        // schema drift on this shape against cycles-protocol@main.
        ResponseEntity<Map> tenantIntrospect = restTemplate.exchange(
                baseUrl() + "/v1/auth/introspect", HttpMethod.GET,
                new HttpEntity<>(tenantH),
                Map.class);
        assertThat(tenantIntrospect.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tenantIntrospect.getBody().get("auth_type")).isEqualTo("tenant");
        assertThat(tenantIntrospect.getBody().get("tenant_id")).isEqualTo("tenant-integration");
        Map<?,?> tenantCaps = (Map<?,?>) tenantIntrospect.getBody().get("capabilities");
        // Admin-plane caps MUST be false under tenant auth (NORMATIVE).
        assertThat(tenantCaps.get("view_tenants")).isEqualTo(false);
        assertThat(tenantCaps.get("view_api_keys")).isEqualTo(false);
        assertThat(tenantCaps.get("view_audit")).isEqualTo(false);
        assertThat(tenantCaps.get("view_overview")).isEqualTo(false);
        // Tenant-plane caps derived from the granted permissions.
        assertThat(tenantCaps.get("view_budgets")).isEqualTo(true);
        assertThat(tenantCaps.get("manage_budgets")).isEqualTo(true);
        assertThat(tenantCaps.get("view_webhooks")).isEqualTo(true);
        assertThat(tenantCaps.get("manage_webhooks")).isEqualTo(true);

        // --- 18. Revoke API key ---
        assertThat(adminDelete("/v1/admin/api-keys/" + keyId + "?reason=integration-test-cleanup")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 19. List events ---
        ResponseEntity<Map> events = adminGet("/v1/admin/events");
        assertThat(events.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(events.getBody().get("events")).asList().isNotEmpty();

        // --- 20. Admin overview ---
        ResponseEntity<Map> overview = adminGet("/v1/admin/overview");
        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(overview.getBody()).containsKeys("as_of", "tenant_counts", "budget_counts");

        // --- 21. Auth introspect ---
        ResponseEntity<Map> introspect = adminGet("/v1/auth/introspect");
        assertThat(introspect.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(introspect.getBody().get("auth_type")).isEqualTo("admin");

        // --- 22. Auth validate (admin key can hit; admin endpoint) ---
        ResponseEntity<Map> validate = adminPost("/v1/auth/validate",
                Map.of("key_secret", keySecret));
        // After step 18 the key was revoked, so validate returns 200 with valid=false.
        assertThat(validate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validate.getBody().get("valid")).isEqualTo(false);

        // --- 23. Audit logs ---
        assertThat(adminGet("/v1/admin/audit/logs").getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 24. Webhook security config get + put ---
        assertThat(adminGet("/v1/admin/config/webhook-security").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> putConfig = restTemplate.exchange(
                baseUrl() + "/v1/admin/config/webhook-security", HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "allow_http", false,
                        "blocked_cidr_ranges", List.of("10.0.0.0/8"),
                        "allowed_url_patterns", List.of("https://*.example.com/**")),
                        adminHeaders()),
                Map.class);
        assertThat(putConfig.getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- 25. v0.1.25.20: failure-path audit coverage ---
        // Deliberate failures exercise all three audit-on-failure sources:
        //   - AuthInterceptor (missing key → 401, unauthenticated tenant sentinel)
        //   - GlobalExceptionHandler (HttpMessageNotReadable → 400, authenticated tenant)
        //   - GlobalExceptionHandler (GovernanceException 404)
        // Every entry is contract-validated on the wire against the pinned
        // spec by ContractValidatingRestTemplateInterceptor.

        // 25a. Unauthenticated admin call — writeError path in AuthInterceptor.
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.set("Content-Type", "application/json");
        ResponseEntity<Map> unauth = restTemplate.exchange(
                baseUrl() + "/v1/admin/tenants", HttpMethod.GET,
                new HttpEntity<>(noAuth), Map.class);
        assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 25b. Malformed JSON under valid admin auth — handleMalformedJson branch.
        HttpHeaders adminRaw = adminHeaders();
        adminRaw.set("Content-Type", "application/json");
        ResponseEntity<Map> malformed = restTemplate.exchange(
                baseUrl() + "/v1/admin/tenants", HttpMethod.POST,
                new HttpEntity<>("this is not json", adminRaw),
                Map.class);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 25c. Nonexistent resource under valid admin auth — GovernanceException branch.
        ResponseEntity<Map> notFound = restTemplate.exchange(
                baseUrl() + "/v1/admin/tenants/does-not-exist", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                Map.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 25d. Query audit log and confirm each failure landed with the right
        // status + error_code + operation shape. Cursor-paginated, so fetch
        // a wide slice (200) to cover the whole flow.
        ResponseEntity<Map> auditAfter = adminGet("/v1/admin/audit/logs?limit=200");
        assertThat(auditAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>) auditAfter.getBody().get("logs");

        // Unauth 401 → tenant_id sentinel, operation=GET:/v1/admin/tenants,
        // error_code=UNAUTHORIZED.
        assertThat(logs).anyMatch(e ->
                Integer.valueOf(401).equals(e.get("status")) &&
                "UNAUTHORIZED".equals(e.get("error_code")) &&
                "GET:/v1/admin/tenants".equals(e.get("operation")) &&
                "<unauthenticated>".equals(e.get("tenant_id")));

        // 400 malformed JSON → authenticated admin (no tenant_id stamped;
        // sentinel still applies since admin key does NOT stamp
        // authenticated_tenant_id per AuthInterceptor contract).
        assertThat(logs).anyMatch(e ->
                Integer.valueOf(400).equals(e.get("status")) &&
                "INVALID_REQUEST".equals(e.get("error_code")) &&
                "POST:/v1/admin/tenants".equals(e.get("operation")));

        // 404 governance → error_code=TENANT_NOT_FOUND.
        assertThat(logs).anyMatch(e ->
                Integer.valueOf(404).equals(e.get("status")) &&
                "TENANT_NOT_FOUND".equals(e.get("error_code")) &&
                "GET:/v1/admin/tenants/does-not-exist".equals(e.get("operation")));

        // Sanity: success entries from steps 1-24 still present alongside
        // the new failure entries (single-write invariant preserved).
        assertThat(logs).anyMatch(e ->
                Integer.valueOf(201).equals(e.get("status")));

        // --- 26. v0.1.25.27: audit filter DSL end-to-end ---
        // Exercise the new listAuditLogs filter DSL through real Redis.
        // Contract interceptor validates every response shape against
        // the pinned spec (v0.1.25.24); assertions below confirm the
        // new predicates actually filter entries correctly in the full
        // stack — not just the MockMvc controller tests.

        // 26a. error_code IN-list — seeded entries include UNAUTHORIZED,
        // INVALID_REQUEST, TENANT_NOT_FOUND. Request two codes; response
        // MUST contain both and MUST NOT contain success (null-error_code)
        // rows. NULL-semantic: IN-list rejects null error_code.
        ResponseEntity<Map> errorCodeIn = adminGet(
                "/v1/admin/audit/logs?error_code=UNAUTHORIZED,TENANT_NOT_FOUND&limit=200");
        assertThat(errorCodeIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inLogs = (List<Map<String, Object>>) errorCodeIn.getBody().get("logs");
        assertThat(inLogs).isNotEmpty();
        assertThat(inLogs).allMatch(e -> {
            Object code = e.get("error_code");
            return "UNAUTHORIZED".equals(code) || "TENANT_NOT_FOUND".equals(code);
        });
        assertThat(inLogs).anyMatch(e -> "UNAUTHORIZED".equals(e.get("error_code")));
        assertThat(inLogs).anyMatch(e -> "TENANT_NOT_FOUND".equals(e.get("error_code")));

        // 26b. error_code_exclude — hiding UNAUTHORIZED must not silently
        // hide success rows (NULL-error_code asymmetry). Response MUST
        // contain 201 success rows AND other error codes, but no
        // UNAUTHORIZED entries.
        ResponseEntity<Map> errorCodeEx = adminGet(
                "/v1/admin/audit/logs?error_code_exclude=UNAUTHORIZED&limit=200");
        assertThat(errorCodeEx.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exLogs = (List<Map<String, Object>>) errorCodeEx.getBody().get("logs");
        assertThat(exLogs).noneMatch(e -> "UNAUTHORIZED".equals(e.get("error_code")));
        assertThat(exLogs).anyMatch(e -> Integer.valueOf(201).equals(e.get("status")));

        // 26c. status range 400..499 — must include all 4xx seeded entries
        // (400 malformed, 401 unauthorized, 404 not-found) and exclude any
        // 2xx success rows. Narrow-range slicing (e.g. 401..401) belongs
        // in AuditControllerTest, not the full-stack pass.
        ResponseEntity<Map> range4xx = adminGet(
                "/v1/admin/audit/logs?status_min=400&status_max=499&limit=200");
        assertThat(range4xx.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rangeLogs = (List<Map<String, Object>>) range4xx.getBody().get("logs");
        assertThat(rangeLogs).isNotEmpty();
        assertThat(rangeLogs).allMatch(e -> {
            Integer st = (Integer) e.get("status");
            return st != null && st >= 400 && st <= 499;
        });
        assertThat(rangeLogs).anyMatch(e -> Integer.valueOf(400).equals(e.get("status")));
        assertThat(rangeLogs).anyMatch(e -> Integer.valueOf(404).equals(e.get("status")));

        // 26d. operation IN-list (promoted scalar→array). Pass two
        // seeded operations as a comma-separated list; response MUST
        // contain entries for both and no others.
        ResponseEntity<Map> opIn = adminGet(
                "/v1/admin/audit/logs?operation=GET:/v1/admin/tenants,POST:/v1/admin/tenants&limit=200");
        assertThat(opIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opLogs = (List<Map<String, Object>>) opIn.getBody().get("logs");
        assertThat(opLogs).isNotEmpty();
        assertThat(opLogs).allMatch(e -> {
            Object op = e.get("operation");
            return "GET:/v1/admin/tenants".equals(op) || "POST:/v1/admin/tenants".equals(op);
        });

        // 26e. search match-set extension — v0.1.25.27 extends search
        // to error_code + operation. ?search=UNAUTHORIZED must find the
        // 401 entry via error_code substring (was unreachable pre-.27).
        ResponseEntity<Map> searchErrorCode = adminGet(
                "/v1/admin/audit/logs?search=UNAUTHORIZED&limit=200");
        assertThat(searchErrorCode.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> searchLogs = (List<Map<String, Object>>) searchErrorCode.getBody().get("logs");
        assertThat(searchLogs).anyMatch(e -> "UNAUTHORIZED".equals(e.get("error_code")));

        // 26f. Validation — status exact + status_min is mutex. Must 400.
        HttpHeaders admin = adminHeaders();
        ResponseEntity<Map> badMutex = restTemplate.exchange(
                baseUrl() + "/v1/admin/audit/logs?status=400&status_min=400",
                HttpMethod.GET, new HttpEntity<>(admin), Map.class);
        assertThat(badMutex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badMutex.getBody().get("error")).isEqualTo("INVALID_REQUEST");

        // 26g. Validation — status_min > status_max. Must 400.
        ResponseEntity<Map> badRange = restTemplate.exchange(
                baseUrl() + "/v1/admin/audit/logs?status_min=500&status_max=400",
                HttpMethod.GET, new HttpEntity<>(admin), Map.class);
        assertThat(badRange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRange.getBody().get("error")).isEqualTo("INVALID_REQUEST");
    }
}
