# Audit Report: Admin Implementation vs YAMP API Spec v0.1.23 (Round 3)

**Date**: 2026-03-09
**Spec**: `complete-budget-governance-v0.1.23.yaml`
**Implementation**: `cycles-admin-service` (Spring Boot 3 / Java 21)

## Executive Summary

After applying all 14 fixes from Rounds 1 and 2, a comprehensive re-audit found **5 new issues** (1 CRITICAL, 2 HIGH, 2 LOW). The CRITICAL and HIGH issues are **authorization bypass vulnerabilities** where ApiKeyAuth-scoped endpoints fail to validate that the requested tenant matches the authenticated tenant, allowing cross-tenant data access and modification.

### Previous Issues — Status

| # | Issue | Status |
|---|-------|--------|
| 1–10 | Round 1 fixes (serialization, validation, permissions, etc.) | **ALL FIXED** |
| 11 | BudgetFundingResponse class-level NON_NULL on required fields | **FIXED** |
| 12 | AuditLogEntry class-level NON_NULL on required fields | **FIXED** |
| 13 | List responses class-level NON_NULL on required fields | **FIXED** |
| 14 | ApiKey.keyHash exposed via @JsonProperty | **FIXED** — @JsonIgnore + serializeKey/deserializeKey helpers |

---

## New Issues Found

### Issue 15 — [CRITICAL] Budget fund endpoint allows cross-tenant budget modification

**Spec** (lines 1142-1176):
```yaml
/v1/admin/budgets/fund?scope=&unit=:
  post:
    security:
      - ApiKeyAuth: []
    description: >-
      AUTHORIZATION:
      - Tenant can fund their own budgets only
```

**Implementation** (`BudgetController.java:47-58`):
```java
@PostMapping("/fund")
public ResponseEntity<BudgetFundingResponse> fund(@RequestParam String scope, @RequestParam UnitEnum unit,
        @Valid @RequestBody BudgetFundingRequest request, HttpServletRequest httpRequest) {
    BudgetFundingResponse response = repository.fund(scope, unit, request);
    // ...
}
```

The `scope` path parameter is passed directly to `repository.fund()` without verifying that the scope belongs to the authenticated tenant. Redis key is `budget:{scope}:{unit}`, which is globally accessible. An attacker with a valid API key for tenant A can CREDIT/DEBIT/RESET/REPAY_DEBT budgets belonging to tenant B by specifying their scope.

**Attack vector**: `POST /v1/admin/budgets/fund?scope=tenant:victim-corp&unit=USD_MICROCENTS` with `X-Cycles-API-Key` belonging to attacker-corp.

**Risk**: Unauthorized financial modification — attacker can drain or inflate another tenant's budget.

**Fix**: Validate that the budget's `tenant_id` (from the Redis hash) matches `httpRequest.getAttribute("authenticated_tenant_id")` before executing the fund operation. Alternatively, prefix the budget key lookup with the authenticated tenant_id.

---

### Issue 16 — [HIGH] Budget list endpoint allows cross-tenant budget enumeration

**Spec** (lines 1092-1140):
```yaml
/v1/admin/budgets:
  get:
    security:
      - ApiKeyAuth: []
    description: >-
      AUTHORIZATION:
      - Tenant can list their own budgets only (scoped by ApiKeyAuth)
    parameters:
      - name: tenant_id
        in: query
        required: true
```

**Implementation** (`BudgetController.java:30-46`):
```java
@GetMapping
public ResponseEntity<BudgetListResponse> list(
        @RequestParam(required = true) String tenant_id, ...) {
    var ledgers = repository.list(tenant_id, scope_prefix, unit, status, cursor, effectiveLimit);
    // ...
}
```

The user-supplied `tenant_id` query parameter is passed directly to the repository without checking it matches the authenticated tenant from the API key. A user can enumerate all budgets of any tenant.

**Risk**: Information disclosure — full budget details (allocated, remaining, debt, etc.) of any tenant.

**Fix**: Either ignore the `tenant_id` parameter and always use `httpRequest.getAttribute("authenticated_tenant_id")`, or validate that it matches the authenticated tenant before querying.

---

### Issue 17 — [HIGH] Balance query endpoint allows cross-tenant balance reads

**Spec** (lines 1573-1589):
```yaml
/v1/balances:
  get:
    security:
      - ApiKeyAuth: []
    description: >-
      GOVERNANCE INTEGRATION:
      - Scoped to effective tenant
```

**Implementation** (`BalanceController.java:15-31`):
```java
@GetMapping
public ResponseEntity<BalanceQueryResponse> query(
        @RequestParam(required = false) String tenant_id, ..., HttpServletRequest httpRequest) {
    String effectiveTenantId = tenant_id;
    if (effectiveTenantId == null || effectiveTenantId.isBlank()) {
        effectiveTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
    }
    var ledgers = repository.list(effectiveTenantId, ...);
}
```

When `tenant_id` is provided as a query parameter, it overrides the authenticated tenant. The spec states this endpoint must be "scoped to effective tenant" (the tenant derived from the API key), but the implementation allows any tenant_id.

**Risk**: Information disclosure — read all balance data for any tenant.

**Fix**: Always use the authenticated tenant_id from the request attribute. Ignore or validate the `tenant_id` query parameter.

---

### Issue 18 — [LOW] ErrorResponse.details serializes as null instead of being omitted

**Spec** (lines 865-877):
```yaml
ErrorResponse:
  required: [error, message, request_id]
  properties:
    details:
      type: object
      additionalProperties: true
```

**Implementation** (`ErrorResponse.java:10`):
```java
@JsonProperty("details") private Map<String, Object> details;
```

The `details` field is optional per spec but has no `@JsonInclude(NON_NULL)` annotation. When details are not provided (most error responses), the field serializes as `"details": null` rather than being omitted entirely.

**Risk**: Cosmetic — no functional impact, but adds unnecessary null field to every error response.

**Fix**: Add `@JsonInclude(JsonInclude.Include.NON_NULL)` to the `details` field.

---

### Issue 19 — [LOW] BalanceQueryResponse uses class-level NON_NULL

**Implementation** (`BalanceQueryResponse.java:5-9`):
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class BalanceQueryResponse {
    @JsonProperty("balances") private List<BudgetLedger> balances;
    @JsonProperty("has_more") private boolean hasMore;
}
```

Same pattern as the fixed Issue 13 — the class-level `@JsonInclude(NON_NULL)` could suppress the `balances` field if null. The `/v1/balances` endpoint defers its response schema to Cycles Protocol, so strict compliance is hard to assess, but for consistency with the fixes applied to other list responses, this should use field-level annotations or remove class-level NON_NULL.

**Risk**: Low — `balances` is always populated by the controller, and `has_more` is a primitive boolean (never null).

**Fix**: Remove class-level `@JsonInclude(NON_NULL)` for consistency.

---

## Compliance Matrix

### Endpoints

| Spec Endpoint | Method | Controller | Auth | Status |
|---|---|---|---|---|
| `/v1/admin/tenants` | POST | `TenantController.create` | AdminKeyAuth | OK |
| `/v1/admin/tenants` | GET | `TenantController.list` | AdminKeyAuth | OK |
| `/v1/admin/tenants/{tenant_id}` | GET | `TenantController.get` | AdminKeyAuth | OK |
| `/v1/admin/tenants/{tenant_id}` | PATCH | `TenantController.update` | AdminKeyAuth | OK |
| `/v1/admin/budgets` | POST | `BudgetController.create` | ApiKeyAuth | OK |
| `/v1/admin/budgets` | GET | `BudgetController.list` | ApiKeyAuth | **Issue 16** |
| `/v1/admin/budgets/fund?scope=&unit=` | POST | `BudgetController.fund` | ApiKeyAuth | **Issue 15** |
| `/v1/admin/policies` | POST | `PolicyController.create` | ApiKeyAuth | OK |
| `/v1/admin/policies` | GET | `PolicyController.list` | ApiKeyAuth | OK |
| `/v1/admin/api-keys` | POST | `ApiKeyController.create` | AdminKeyAuth | OK |
| `/v1/admin/api-keys` | GET | `ApiKeyController.list` | AdminKeyAuth | OK |
| `/v1/admin/api-keys/{key_id}` | DELETE | `ApiKeyController.revoke` | AdminKeyAuth | OK |
| `/v1/auth/validate` | POST | `AuthController.validate` | AdminKeyAuth | OK |
| `/v1/admin/audit/logs` | GET | `AuditController.list` | AdminKeyAuth | OK |
| `/v1/balances` | GET | `BalanceController.query` | ApiKeyAuth | **Issue 17** |

### Schemas

| Spec Schema | Implementation | Status |
|---|---|---|
| `UnitEnum` | `shared/UnitEnum.java` — 4 values match | OK |
| `Amount` | `shared/Amount.java` — @NotNull unit + @NotNull @Min(0) amount | OK |
| `CommitOveragePolicy` | `shared/CommitOveragePolicy.java` — 3 values match | OK |
| `Caps` | `shared/Caps.java` — all optional, class-level NON_NULL appropriate | OK |
| `ErrorCode` | `shared/ErrorCode.java` — 22 values match spec exactly | OK |
| `ErrorResponse` | `shared/ErrorResponse.java` | **Issue 18** |
| `Tenant` | `tenant/Tenant.java` — required fields always serialized | OK |
| `TenantCreateRequest` | `tenant/TenantCreateRequest.java` — pattern + size constraints | OK |
| `TenantListResponse` | `tenant/TenantListResponse.java` — required fields always serialized | OK |
| `BudgetLedger` | `budget/BudgetLedger.java` — required fields always serialized | OK |
| `BudgetCreateRequest` | `budget/BudgetCreateRequest.java` — constraints match | OK |
| `BudgetFundingRequest` | `budget/BudgetFundingRequest.java` — constraints match | OK |
| `BudgetFundingResponse` | `budget/BudgetFundingResponse.java` — required fields always serialized | OK |
| `BudgetListResponse` | `budget/BudgetListResponse.java` — required fields always serialized | OK |
| `Policy` | `policy/Policy.java` — required fields always serialized | OK |
| `PolicyCreateRequest` | `policy/PolicyCreateRequest.java` — constraints match | OK |
| `PolicyListResponse` | `policy/PolicyListResponse.java` — required fields always serialized | OK |
| `ApiKey` | `auth/ApiKey.java` — keyHash @JsonIgnore, helpers in repository | OK |
| `ApiKeyCreateRequest` | `auth/ApiKeyCreateRequest.java` — constraints match | OK |
| `ApiKeyCreateResponse` | `auth/ApiKeyCreateResponse.java` — all required fields serialized | OK |
| `ApiKeyResponse` (= ApiKey for API) | `auth/ApiKeyResponse.java` — required fields always serialized | OK |
| `ApiKeyListResponse` | `auth/ApiKeyListResponse.java` — required fields always serialized | OK |
| `ApiKeyValidationRequest` | `auth/ApiKeyValidationRequest.java` — @NotBlank key_secret | OK |
| `ApiKeyValidationResponse` | `auth/ApiKeyValidationResponse.java` — required fields always serialized | OK |
| `AuditLogEntry` | `audit/AuditLogEntry.java` — required fields always serialized | OK |
| `AuditLogListResponse` | `audit/AuditLogListResponse.java` — required fields always serialized | OK |

### Security

| Spec Requirement | Implementation | Status |
|---|---|---|
| `AdminKeyAuth` via `X-Admin-API-Key` | `AuthInterceptor.validateAdminKey()` | OK |
| `ApiKeyAuth` via `X-Cycles-API-Key` | `AuthInterceptor.validateApiKey()` | OK |
| Admin endpoints require AdminKeyAuth | Correct routing in `requiresAdminKey()` | OK |
| Tenant-scoped endpoints require ApiKeyAuth | Correct routing in `requiresApiKey()` | OK |
| Key validation checks: exists, hash, status, expiry, tenant status | All 5 checks in `ApiKeyRepository.validate()` | OK |
| **Tenant scoping on ApiKeyAuth endpoints** | **Budget list, fund, and balance do not enforce** | **Issues 15-17** |

### Behavioral Compliance

| Spec Behavior | Implementation | Status |
|---|---|---|
| Tenant create idempotency (200 vs 201) | Lua script returns existing; controller checks `result.created()` | OK |
| Budget create 409 on duplicate | Lua script atomic EXISTS check | OK |
| Funding idempotency via `idempotency_key` | Redis cache with 24h TTL | OK |
| CREDIT/DEBIT/RESET/REPAY_DEBT operations | Lua script handles all 4 | OK |
| Key revocation is permanent (ACTIVE → REVOKED) | `ApiKeyRepository.revoke()` sets status and timestamp | OK |
| Status transitions: ACTIVE↔SUSPENDED, *→CLOSED (irreversible) | `TenantRepository.update()` validates transitions | OK |
| Cursor-based pagination | Implemented in all list endpoints | OK |
| Limit clamping (min 1, max 100, default 50) | `Math.max(1, Math.min(limit, 100))` in all controllers | OK |
| Audit logging on write operations | All write endpoints log to `AuditRepository` | OK |
| Tenant existence check before API key creation | `ApiKeyRepository.create()` validates tenant | OK |
| Budget fund tenant scoping | **Not enforced** | **Issue 15** |
| Budget list tenant scoping | **Not enforced** | **Issue 16** |
| Balance query tenant scoping | **Not enforced** | **Issue 17** |

---

## Summary

| Severity | Count | Issues |
|---|---|---|
| CRITICAL | 1 | #15 (cross-tenant budget modification via fund endpoint) |
| HIGH | 2 | #16 (cross-tenant budget enumeration), #17 (cross-tenant balance read) |
| LOW | 2 | #18 (ErrorResponse.details null serialization), #19 (BalanceQueryResponse class-level NON_NULL) |
| **Total** | **5** | |

The most significant finding is that **3 of 5 ApiKeyAuth-scoped endpoints do not enforce tenant isolation**. While the `AuthInterceptor` correctly validates the API key and extracts the tenant, the `BudgetController` (list and fund) and `BalanceController` (query) accept user-supplied tenant IDs without checking them against the authenticated tenant. This creates a direct authorization bypass where any authenticated user can read or modify budgets belonging to other tenants.

Recommended priority: Fix issues 15-17 immediately before any production deployment.
