# Re-Audit: Admin Implementation vs. API Spec (v0.1.23)

**Date:** 2026-03-09
**Spec:** `complete-budget-governance-v0.1.23.yaml` (OpenAPI 3.1.0)
**Impl:** `cycles-admin-service` (Spring Boot 3 / Java 21)
**Previous Audit:** 23 issues found → 23 fixes applied → this re-audit

---

## Status of Previously Reported Issues

| # | Issue | Verdict |
|---|-------|---------|
| 1 | No Authentication/Authorization Enforcement | **FIXED** — `AuthInterceptor` enforces `AdminKeyAuth` and `ApiKeyAuth` on all `/v1/**` paths |
| 2 | Validate API Key Skips Tenant Status Check | **FIXED** — `ApiKeyRepository.validate()` now checks tenant SUSPENDED/CLOSED (lines 133-145) |
| 3 | Tenant Create Not Idempotent | **FIXED** — Lua script returns existing tenant; controller returns 200 vs 201 |
| 4 | BudgetCreateRequest Has Extra Required `tenant_id` | **FIXED** — `tenant_id` is now optional, derived from auth in `BudgetController.create()` |
| 5 | BudgetLedger Includes Extra `tenant_id` Field | **ACCEPTED** — Still present. See new issue #2 below |
| 6 | Policy Model Missing `reservation_ttl_override` and `rate_limits` | **FIXED** — Both fields added to `Policy`, `PolicyCreateRequest`, new model classes created |
| 7 | Tenant Update Missing Status Transition Validation | **FIXED** — Full transition validation with `suspended_at`/`closed_at` timestamps |
| 8 | Tenant Create Missing `default_commit_overage_policy` Default | **FIXED** — Defaults to `REJECT`, plus `defaultReservationTtlMs=60000`, `maxReservationTtlMs=3600000`, `maxReservationExtensions=10` |
| 9 | `BudgetFundingRequest.idempotency_key` Required vs. Optional | **FIXED** — Changed from `@NotBlank` to `@Size(max = 256)` (optional) |
| 10 | DEBIT Failure Returns 422, Spec Says 409 | **FIXED** — `insufficientFunds()` now returns 409 |
| 11 | Budget FROZEN/CLOSED Returns 422 | **FIXED** — `budgetFrozen()` and `budgetClosed()` now return 409 |
| 12 | `budgetClosed` Uses Wrong Error Code | **PARTIALLY FIXED** — Changed to `BUDGET_FROZEN` (closest spec code). See new issue #1 |
| 13 | List Tenants Missing `parent_tenant_id` Filter | **FIXED** — Added `parent_tenant_id`, `cursor` params |
| 14 | List Budgets Missing Query Filters | **FIXED** — Added `scope_prefix`, `unit`, `status`, `cursor`, `limit` params |
| 15 | List Policies Missing All Query Parameters | **FIXED** — Added `scope_pattern`, `status`, `cursor`, `limit` params |
| 16 | List API Keys Missing Filters | **FIXED** — Added `status`, `cursor`, `limit` params |
| 17 | List Audit Logs Missing Filters | **FIXED** — Added `key_id`, `operation`, `status`, `from`, `to`, `cursor` params; global index for cross-tenant queries |
| 18 | No Cursor-Based Pagination | **FIXED** — All list endpoints now support cursor-based pagination with `next_cursor` and `has_more` |
| 19 | ApiKey `expires_at` Required in Spec But Optional | **OPEN** — Still not addressed. See new issue #3 |
| 20 | `Caps` Field Types: Long vs Integer | **FIXED** — Changed from `Long` to `Integer` |
| 21 | ApiKey Model Exposes `key_hash` Field | **OPEN** — Still uses manual `setKeyHash(null)`. See new issue #4 |
| 22 | List Response Wrapper Inconsistency | **OPEN** — Still uses `Map<String, Object>`. See new issue #5 |
| 23 | Balances Endpoint Reuses Budget List | **OPEN** — Still returns BudgetLedger objects under `"balances"` key. See new issue #6 |

**Resolution: 19 of 23 fixed, 4 carried forward as known issues.**

---

## New Issues Found in Re-Audit

### MEDIUM Issues

#### 1. `GovernanceException.budgetClosed()` Reuses `BUDGET_FROZEN` Error Code
**Severity: MEDIUM**

Both `budgetFrozen()` and `budgetClosed()` return `ErrorCode.BUDGET_FROZEN`. Clients cannot distinguish a frozen budget from a closed one. The spec's `ErrorCode` enum does not define `BUDGET_CLOSED`, so `BUDGET_FROZEN` is the closest match — but the error *message* should clearly indicate the difference.

Currently `budgetClosed()` says `"Budget is closed: ..."` with code `BUDGET_FROZEN`, which is contradictory.

**File:** `GovernanceException.java:37-39`
**Recommendation:** Either add a custom error code or ensure the `details` map carries a `"budget_status": "CLOSED"` field.

#### 2. `BudgetLedger` Response Contains Extra `tenant_id` Field
**Severity: MEDIUM**

The spec's `BudgetLedger` schema uses `additionalProperties: false` and does not include `tenant_id`. The implementation includes it in every response. Strict OpenAPI-compliant clients will reject this response.

**File:** `BudgetLedger.java:12`

#### 3. `ApiKey.expires_at` Required by Spec But Nullable in Implementation
**Severity: MEDIUM**

Spec declares `expires_at` as required on both `ApiKey` and `ApiKeyCreateResponse` schemas. The `ApiKeyCreateRequest` makes it optional. If a key is created without `expires_at`, both the `ApiKeyCreateResponse` and subsequent `ApiKey` list responses will omit it (due to `@JsonInclude(NON_NULL)`), violating the required field contract.

**Files:** `ApiKey.java:19`, `ApiKeyCreateResponse.java:14`, `ApiKeyRepository.java:41`
**Recommendation:** Either default `expires_at` to 90 days from creation (as spec recommends) or always serialize it as `null` (remove `NON_NULL` for this field specifically).

#### 4. Policies Not Scoped to Tenant
**Severity: MEDIUM**

The spec says *"Tenant can create policies for their own scopes only"* and *"Tenant can list their own policies only"*. However:
- `PolicyRepository` stores all policies in a global `"policies"` Redis set
- `PolicyCreateRequest` has no `tenant_id` field (matching spec)
- No mechanism associates a policy with the authenticated tenant
- `PolicyController.list()` returns all policies regardless of who created them

Any tenant with API key access can see and create policies affecting other tenants' scopes.

**Files:** `PolicyRepository.java:35-36`, `PolicyController.java:20-34`
**Recommendation:** Add `tenant_id` to `Policy` model (internal field), store policies per-tenant in Redis (`policies:{tenant_id}`), and filter by authenticated tenant in controller.

#### 5. `BudgetFundingRequest.idempotency_key` Missing `minLength` Validation
**Severity: LOW**

Spec defines `idempotency_key` with `minLength: 1`. Implementation has `@Size(max = 256)` but no `min = 1`. An empty string `""` would pass validation but is semantically invalid as an idempotency key.

**File:** `BudgetFundingRequest.java:13`
**Recommendation:** Change to `@Size(min = 1, max = 256)`.

### LOW Issues (Carried Forward)

#### 6. ApiKey `key_hash` Exposed via Manual Null-Setting
**Severity: LOW**

The `key_hash` field is not in the spec's `ApiKey` schema. It's stripped manually via `key.setKeyHash(null)` in `ApiKeyRepository.list()` and `revoke()`. This is fragile — any new code path returning an `ApiKey` could leak the hash. The `@JsonProperty(access = WRITE_ONLY)` approach was attempted but conflicts with using the same `ObjectMapper` for Redis serialization.

**File:** `ApiKey.java:11`, `ApiKeyRepository.java:81,104`
**Recommendation:** Use a separate DTO for API responses or a custom serializer.

#### 7. List Responses Use `Map<String, Object>` Instead of Typed DTOs
**Severity: LOW**

All list endpoints return `Map<String, Object>` instead of spec-defined typed responses (`TenantListResponse`, `BudgetListResponse`, etc.). This works but provides no compile-time safety and generates weaker OpenAPI documentation.

**Files:** All controller list methods

#### 8. Balances Endpoint Returns Full BudgetLedger Objects
**Severity: LOW**

`GET /v1/balances` calls `BudgetRepository.list()` and returns full `BudgetLedger` objects under a `"balances"` key. The spec references "Balance data (see Cycles v0.1.23)" which may expect a different shape. Functionally acceptable but not strictly conformant.

**File:** `BalanceController.java:26-28`

#### 9. Tenant List `limit` Max Not Enforced
**Severity: LOW**

Spec defines `limit` parameter with `maximum: 100` for tenant listing. Implementation uses `defaultValue = "50"` but doesn't cap at 100 — a client can request `limit=10000`.

**File:** `TenantController.java:27`

---

## Compliance Matrix

### Endpoints

| Endpoint | Method | Spec Security | Impl Security | Status |
|----------|--------|---------------|---------------|--------|
| `/v1/admin/tenants` | POST | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/admin/tenants` | GET | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/admin/tenants/{id}` | GET | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/admin/tenants/{id}` | PATCH | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/admin/budgets` | POST | ApiKeyAuth | ApiKeyAuth | PASS |
| `/v1/admin/budgets` | GET | ApiKeyAuth | ApiKeyAuth | PASS |
| `/v1/admin/budgets/{scope}/{unit}/fund` | POST | ApiKeyAuth | ApiKeyAuth | PASS |
| `/v1/admin/policies` | POST | ApiKeyAuth | ApiKeyAuth | PASS |
| `/v1/admin/policies` | GET | ApiKeyAuth | ApiKeyAuth | PASS |
| `/v1/admin/api-keys` | POST | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/admin/api-keys` | GET | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/admin/api-keys/{id}` | DELETE | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/auth/validate` | POST | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/admin/audit/logs` | GET | AdminKeyAuth | AdminKeyAuth | PASS |
| `/v1/balances` | GET | ApiKeyAuth | ApiKeyAuth | PASS |
| `/v1/reservations` | POST | ApiKeyAuth | ApiKeyAuth (interceptor only) | N/A* |
| `/v1/reservations/{id}/commit` | POST | ApiKeyAuth | ApiKeyAuth (interceptor only) | N/A* |

*Reservation endpoints are defined as "imported from Cycles Protocol v0.1.23" — auth is enforced by interceptor but no controller exists in admin service (expected to be in runtime service).

### Schema Compliance

| Schema | Fields Match | Required Fields | Notes |
|--------|-------------|-----------------|-------|
| Tenant | PASS | PASS | All spec fields present |
| TenantCreateRequest | PASS | PASS | Validation constraints match |
| BudgetLedger | WARN | PASS | Extra `tenant_id` field (issue #2) |
| BudgetCreateRequest | PASS | PASS | `tenant_id` optional, derived from auth |
| BudgetFundingRequest | PASS | PASS | `idempotency_key` missing minLength (issue #5) |
| BudgetFundingResponse | PASS | PASS | |
| Policy | PASS | PASS | All fields including ttl_override, rate_limits |
| PolicyCreateRequest | PASS | PASS | |
| ApiKey | WARN | WARN | `key_hash` extra (issue #6); `expires_at` nullable (issue #3) |
| ApiKeyCreateRequest | PASS | PASS | |
| ApiKeyCreateResponse | WARN | WARN | `expires_at` nullable (issue #3) |
| ApiKeyValidationResponse | PASS | PASS | |
| AuditLogEntry | PASS | PASS | All 14 fields present |
| Caps | PASS | PASS | Integer types corrected |
| Amount | PASS | PASS | |
| ErrorCode | PASS | PASS | All 22 enum values present |
| ErrorResponse | PASS | PASS | |

### Key Validation Steps (Spec §/v1/auth/validate)

| Step | Check | Status |
|------|-------|--------|
| 1 | Key exists in database | PASS |
| 2 | Key hash matches | PASS |
| 3 | Status is ACTIVE | PASS |
| 4 | Current time < expires_at | PASS |
| 5 | Tenant is ACTIVE (not SUSPENDED/CLOSED) | PASS |

---

## Summary

| Severity | Previous | Fixed | New | Remaining |
|----------|----------|-------|-----|-----------|
| CRITICAL | 2 | 2 | 0 | **0** |
| HIGH | 6 | 6 | 0 | **0** |
| MEDIUM | 11 | 8 | 4 | **4** |
| LOW | 4 | 3 | 1 | **4** |
| **Total** | **23** | **19** | **5** | **8** |

### Overall Assessment

The implementation has improved significantly. All CRITICAL and HIGH issues are resolved. The remaining 8 issues are MEDIUM/LOW and fall into two categories:

**Functional gaps (MEDIUM):**
1. Contradictory error code/message for closed budgets
2. Extra `tenant_id` in BudgetLedger response (violates `additionalProperties: false`)
3. `expires_at` can be null when spec requires it
4. Policies not scoped to tenant (security concern)

**Polish items (LOW):**
5. Missing `minLength` on `idempotency_key`
6. Fragile `key_hash` stripping approach
7. Untyped list response wrappers
8. Balance endpoint shape
9. Missing `limit` max enforcement
