# Audit: Admin Implementation vs. API Spec (v0.1.23)

**Date:** 2026-03-09
**Spec:** `complete-budget-governance-v0.1.23.yaml` (OpenAPI 3.1.0)
**Impl:** `cycles-admin-service` (Spring Boot 3 / Java 21)

---

## CRITICAL Issues

### 1. No Authentication/Authorization Enforcement
**Severity: CRITICAL**

None of the 15 implemented endpoints validate the `X-Admin-API-Key` or `X-Cycles-API-Key` headers. All admin and runtime endpoints are completely open to unauthenticated access.

| Spec Security | Affected Endpoints |
|---|---|
| `AdminKeyAuth` (X-Admin-API-Key) | POST/GET tenants, GET/PATCH tenants/{id}, POST/GET api-keys, DELETE api-keys/{id}, POST auth/validate, GET audit/logs |
| `ApiKeyAuth` (X-Cycles-API-Key) | POST/GET budgets, POST fund, POST/GET policies, GET balances |

**Files:** All controllers in `cycles-admin-service-api/.../controller/`

### 2. Validate API Key Skips Tenant Status Check
**Severity: CRITICAL**

Spec requires validation step 5: *"Tenant is ACTIVE (not SUSPENDED or CLOSED)"*. The implementation in `ApiKeyRepository.validate()` checks key status and expiry but never loads or checks the tenant's status.

**File:** `ApiKeyRepository.java:100-135`

---

## HIGH Issues

### 3. Tenant Create Not Idempotent (Spec Violation)
**Severity: HIGH**

Spec says: *"Safe to retry with same tenant_id. Returns existing tenant if already created."* and defines both `200` (idempotent) and `201` (created) responses.

Implementation throws `409 DUPLICATE_RESOURCE` on any duplicate `tenant_id`, even with identical data.

**File:** `TenantRepository.java:25-53`, `TenantController.java:18-20`

### 4. BudgetCreateRequest Has Extra `tenant_id` Field
**Severity: HIGH**

The spec's `BudgetCreateRequest` schema does not include `tenant_id`. The budget endpoint uses `ApiKeyAuth`, meaning the tenant should be derived from the authenticated API key. The implementation adds `tenant_id` as a `@NotBlank` required field in the request body.

**Files:** `BudgetCreateRequest.java:11`, spec line 365-398

### 5. BudgetLedger Includes Extra `tenant_id` Field
**Severity: HIGH**

The spec's `BudgetLedger` schema does not include a `tenant_id` property. The implementation adds it. While useful for internal routing, this deviates from the spec response contract.

**File:** `BudgetLedger.java:12`

### 6. Policy Model Missing `reservation_ttl_override` and `rate_limits`
**Severity: HIGH**

The spec defines two structured sub-objects on `Policy` and `PolicyCreateRequest`:

- `reservation_ttl_override`: `{default_ttl_ms, max_ttl_ms, max_extensions}`
- `rate_limits`: `{max_reservations_per_minute, max_commits_per_minute}`

Neither field exists in the Java model or is persisted.

**Files:** `Policy.java`, `PolicyCreateRequest.java`

### 7. Tenant Update Missing Status Transition Validation
**Severity: HIGH**

Spec documents explicit status transitions:
- `ACTIVE → SUSPENDED`: Blocks new reservations
- `SUSPENDED → ACTIVE`: Resume operations
- `* → CLOSED`: Irreversible

Implementation accepts any status value without transition validation. It also does not set `suspended_at` when transitioning to SUSPENDED or `closed_at` when transitioning to CLOSED.

**File:** `TenantRepository.java:89-103`

### 8. Tenant Create Missing `default_commit_overage_policy` Default
**Severity: HIGH**

Spec says the tenant's `default_commit_overage_policy` should default to `REJECT`. The implementation does not set this field, leaving it `null`.

**File:** `TenantRepository.java:29-38`

---

## MEDIUM Issues

### 9. `BudgetFundingRequest.idempotency_key` Required vs. Optional
**Severity: MEDIUM**

Spec defines `idempotency_key` as optional (not in the `required` array). Implementation marks it `@NotBlank`, making it required. This will reject valid requests that omit the key.

**File:** `BudgetFundingRequest.java:13`

### 10. DEBIT Failure Returns 422, Spec Says 409
**Severity: MEDIUM**

Spec defines DEBIT insufficient funds as `409` (*"Insufficient funds for DEBIT"*). Implementation returns `422` via `GovernanceException.insufficientFunds()`.

**File:** `GovernanceException.java:31-32`

### 11. Budget FROZEN/CLOSED Returns 422, Not Spec-Defined
**Severity: MEDIUM**

For frozen/closed budgets during funding, the implementation returns `422`. The spec only defines `200`, `404`, and `409` responses for the fund endpoint. Frozen should probably map to `409` to align with the spec.

**File:** `GovernanceException.java:34-38`

### 12. `budgetClosed` Uses Wrong Error Code
**Severity: MEDIUM**

`GovernanceException.budgetClosed()` uses `ErrorCode.BUDGET_NOT_FOUND` but the budget does exist — it's just closed. There is no `BUDGET_CLOSED` error code in the spec, but `BUDGET_FROZEN` exists and a similar code should be used or the behavior should return `404` as if the budget doesn't exist.

**File:** `GovernanceException.java:37-39`

### 13. List Tenants Missing `parent_tenant_id` Filter
**Severity: MEDIUM**

Spec defines `parent_tenant_id` as a query parameter for `GET /v1/admin/tenants`. Implementation only supports `status` and `limit`.

**File:** `TenantController.java:22-25`

### 14. List Budgets Missing Query Filters
**Severity: MEDIUM**

Spec defines: `scope_prefix`, `unit`, `status`, `cursor`, `limit` query parameters. Implementation only accepts `tenant_id`.

**File:** `BudgetController.java:22-25`

### 15. List Policies Missing All Query Parameters
**Severity: MEDIUM**

Spec defines: `scope_pattern`, `status`, `cursor`, `limit` parameters. Implementation accepts no parameters.

**File:** `PolicyController.java:20-22`

### 16. List API Keys Missing Filters
**Severity: MEDIUM**

Spec defines: `status`, `cursor`, `limit` query parameters. Implementation only accepts `tenant_id`.

**File:** `ApiKeyController.java:21-23`

### 17. List Audit Logs Missing Filters
**Severity: MEDIUM**

Spec defines: `key_id`, `operation`, `status` (integer), `from` (datetime), `to` (datetime), `cursor` parameters. Implementation only accepts `tenant_id` and `limit`. Also defaults to `"SYSTEM"` when `tenant_id` is null rather than listing across all tenants.

**File:** `AuditController.java:12-16`

### 18. No Cursor-Based Pagination on Any List Endpoint
**Severity: MEDIUM**

All list response schemas define `next_cursor` and `has_more` fields. Implementation hardcodes `has_more` to `false` or uses a size heuristic, and never returns `next_cursor`. No cursor-based pagination is implemented.

**Files:** All controllers returning list responses

### 19. ApiKey `expires_at` Required in Spec Response But Optional in Create
**Severity: MEDIUM**

Spec marks `expires_at` as required in both `ApiKey` and `ApiKeyCreateResponse` schemas. Implementation allows null `expires_at` (no default expiry set). Clients expecting a non-null field will break.

**Files:** `ApiKeyCreateRequest.java:14`, `ApiKeyRepository.java:41`

---

## LOW Issues

### 20. `Caps` Field Types: Long vs Integer
**Severity: LOW**

Spec defines `max_tokens`, `max_steps_remaining`, and `cooldown_ms` as `integer` (32-bit). Implementation uses `Long` (64-bit). Functionally compatible but diverges from spec intent.

**File:** `Caps.java:7-11`

### 21. ApiKey Model Exposes `key_hash` Field
**Severity: LOW**

The `ApiKey` model includes `key_hash` which is not in the spec schema. The implementation strips it on list/revoke operations (`key.setKeyHash(null)`), but relying on manual stripping is fragile. Consider using `@JsonIgnore` or a DTO that excludes it.

**File:** `ApiKey.java:12`

### 22. List Response Wrapper Inconsistency
**Severity: LOW**

List endpoints return `Map<String, Object>` instead of typed response DTOs. The spec defines `TenantListResponse`, `BudgetListResponse`, `PolicyListResponse`, `ApiKeyListResponse`, `AuditLogListResponse` schemas. Using typed classes would provide compile-time safety and automatic OpenAPI doc generation.

**Files:** All controller list methods

### 23. Balances Endpoint Reuses Budget List
**Severity: LOW**

`GET /v1/balances` reuses `BudgetRepository.list()` directly, returning full ledger objects. The spec says this should return "Balance data" which may have a different shape. Also, the response wraps data under `"balances"` key but returns `BudgetLedger` objects.

**File:** `BalanceController.java:12-15`

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 2 |
| HIGH | 6 |
| MEDIUM | 11 |
| LOW | 4 |
| **Total** | **23** |

### Recommended Priority

1. **Implement authentication** (X-Admin-API-Key / X-Cycles-API-Key header validation) — this is the single most important gap
2. **Add tenant status check to key validation** flow
3. **Fix tenant create idempotency** to return 200 on duplicate
4. **Add missing Policy fields** (reservation_ttl_override, rate_limits)
5. **Fix status transition validation** on tenant update
6. **Add missing query filters and pagination** across all list endpoints
7. **Fix HTTP status codes** for DEBIT failures and frozen budgets
8. **Set default_commit_overage_policy** to REJECT on tenant create
9. **Reconcile tenant_id** on budget create/response (derive from auth vs. explicit)
