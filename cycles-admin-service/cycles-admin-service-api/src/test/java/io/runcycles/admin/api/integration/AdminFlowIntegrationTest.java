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
    }
}
