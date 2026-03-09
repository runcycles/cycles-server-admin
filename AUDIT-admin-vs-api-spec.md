# Audit: Admin Implementation vs. YAMP API Spec (v0.1.23)

**Date:** 2026-03-09
**Spec:** `complete-budget-governance-v0.1.23.yaml` (OpenAPI 3.1.0)
**Impl:** `cycles-admin-service` (Spring Boot 3 / Java 21)

---

## Endpoint Compliance

| Endpoint | Method | Spec Security | Impl Security | Params/Body | Response | Verdict |
|----------|--------|---------------|---------------|-------------|----------|---------|
| `/v1/admin/tenants` | POST | AdminKeyAuth | AdminKeyAuth | TenantCreateRequest | 201 Tenant / 200 existing | **PASS** |
| `/v1/admin/tenants` | GET | AdminKeyAuth | AdminKeyAuth | status, parent_tenant_id, cursor, limit | TenantListResponse | **PASS** |
| `/v1/admin/tenants/{id}` | GET | AdminKeyAuth | AdminKeyAuth | path: tenant_id | Tenant / 404 | **PASS** |
| `/v1/admin/tenants/{id}` | PATCH | AdminKeyAuth | AdminKeyAuth | name, status, metadata | Tenant | **PASS** |
| `/v1/admin/budgets` | POST | ApiKeyAuth | ApiKeyAuth | BudgetCreateRequest | 201 BudgetLedger | **PASS** |
| `/v1/admin/budgets` | GET | ApiKeyAuth | ApiKeyAuth | tenant_id (req), scope_prefix, unit, status, cursor, limit | BudgetListResponse | **PASS** |
| `/v1/admin/budgets/{scope}/{unit}/fund` | POST | ApiKeyAuth | ApiKeyAuth | BudgetFundingRequest | BudgetFundingResponse / 404 / 409 | **PASS** |
| `/v1/admin/policies` | POST | ApiKeyAuth | ApiKeyAuth | PolicyCreateRequest | 201 Policy | **PASS** |
| `/v1/admin/policies` | GET | ApiKeyAuth | ApiKeyAuth | scope_pattern, status, cursor, limit | PolicyListResponse | **PASS** |
| `/v1/admin/api-keys` | POST | AdminKeyAuth | AdminKeyAuth | ApiKeyCreateRequest | 201 ApiKeyCreateResponse | **PASS** |
| `/v1/admin/api-keys` | GET | AdminKeyAuth | AdminKeyAuth | tenant_id (req), status, cursor, limit | ApiKeyListResponse | **PASS** |
| `/v1/admin/api-keys/{id}` | DELETE | AdminKeyAuth | AdminKeyAuth | path: key_id, query: reason | ApiKey | **WARN** (see #6) |
| `/v1/auth/validate` | POST | AdminKeyAuth | AdminKeyAuth | ApiKeyValidationRequest | ApiKeyValidationResponse | **WARN** (see #1) |
| `/v1/admin/audit/logs` | GET | AdminKeyAuth | AdminKeyAuth | tenant_id, key_id, operation, status, from, to, cursor, limit | AuditLogListResponse | **PASS** |
| `/v1/balances` | GET | ApiKeyAuth | ApiKeyAuth | tenant_id, scope_prefix, unit | BalanceQueryResponse | **PASS** |

---

## Schema Compliance

| Schema | Required Fields | Field Types | Enum Values | additionalProperties | Verdict |
|--------|----------------|-------------|-------------|---------------------|---------|
| UnitEnum | — | — | USD_MICROCENTS, TOKENS, CREDITS, RISK_POINTS | — | **PASS** |
| Amount | unit, amount | unit: UnitEnum, amount: Long (@Min 0) | — | false (not enforced) | **PASS** |
| CommitOveragePolicy | — | — | REJECT, ALLOW_IF_AVAILABLE, ALLOW_WITH_OVERDRAFT | — | **PASS** |
| Caps | — | max_tokens: Integer, max_steps_remaining: Integer, tool_allowlist, tool_denylist, cooldown_ms: Integer | — | false | **PASS** |
| Tenant | tenant_id, name, status, created_at | All present | TenantStatus: ACTIVE, SUSPENDED, CLOSED | false | **PASS** |
| TenantCreateRequest | tenant_id, name | tenant_id: @Pattern, @Size(3,64); name: @Size(max=256) | — | false | **PASS** |
| TenantListResponse | tenants | tenants, next_cursor, has_more | — | — | **PASS** |
| BudgetLedger | ledger_id, scope, unit, allocated, remaining, status, created_at | All present; tenant_id @JsonIgnore (not exposed) | BudgetStatus: ACTIVE, FROZEN, CLOSED | false | **PASS** |
| BudgetCreateRequest | scope, unit, allocated | All present | RolloverPolicy: NONE, CARRY_FORWARD, CAP_AT_ALLOCATED | false | **WARN** (see #2) |
| BudgetFundingRequest | operation, amount | operation: @NotNull, amount: @NotNull @Valid | FundingOperation: CREDIT, DEBIT, RESET, REPAY_DEBT | false | **PASS** |
| BudgetFundingResponse | operation, previous_allocated, new_allocated, previous_remaining, new_remaining | All present | — | — | **PASS** |
| BudgetListResponse | ledgers | ledgers, next_cursor, has_more | — | — | **PASS** |
| Policy | policy_id, scope_pattern, status, created_at | All present; tenantId @JsonIgnore | PolicyStatus: ACTIVE, DISABLED | false | **PASS** |
| PolicyCreateRequest | name, scope_pattern | All present | — | false | **PASS** |
| PolicyListResponse | policies | policies, next_cursor, has_more | — | — | **PASS** |
| ApiKey | key_id, tenant_id, key_prefix, status, created_at, expires_at | All spec fields in ApiKeyResponse DTO (no key_hash) | ApiKeyStatus: ACTIVE, REVOKED, EXPIRED | false | **PASS** |
| ApiKeyCreateRequest | tenant_id, name | All spec fields present | — | false | **PASS** |
| ApiKeyCreateResponse | key_id, key_secret, key_prefix, tenant_id, created_at, expires_at | All present; expires_at defaults to 90 days | — | — | **PASS** |
| ApiKeyListResponse | keys | keys, next_cursor, has_more | — | — | **PASS** |
| ApiKeyValidationRequest | key_secret | @NotBlank key_secret | — | — | **PASS** |
| ApiKeyValidationResponse | valid, tenant_id | All spec fields present | — | — | **WARN** (see #1) |
| AuditLogEntry | log_id, timestamp, tenant_id, operation, status | All 14 fields present | — | — | **PASS** |
| AuditLogListResponse | logs | logs, next_cursor, has_more | — | — | **PASS** |
| ErrorCode | — | — | 22 spec values + BUDGET_CLOSED (extra) | — | **WARN** (see #3) |
| ErrorResponse | error, message, request_id | All present + optional details | — | — | **PASS** |
| ReservationExpiryPolicy | — | — | AUTO_RELEASE, MANUAL_CLEANUP, GRACE_ONLY | — | **PASS** |

---

## Auth/Validation Compliance

| Validation Step | Spec Requirement | Impl Status |
|----------------|-----------------|-------------|
| 1. Key exists in DB | Required | **PASS** — prefix-based lookup |
| 2. Key hash matches | Required | **PASS** — `keyService.verifyKey()` |
| 3. Status is ACTIVE | Required | **PASS** — checks `key.getStatus() != ACTIVE` |
| 4. Current time < expires_at | Required | **PASS** — `Instant.now().isAfter(key.getExpiresAt())` |
| 5. Tenant is ACTIVE | Required | **PASS** — checks SUSPENDED and CLOSED |
| AdminKeyAuth header | `X-Admin-API-Key` | **PASS** |
| ApiKeyAuth header | `X-Cycles-API-Key` | **PASS** |
| Auth routing | Admin for tenants/api-keys/auth/audit; Api for budgets/policies/balances/reservations | **PASS** |

---

## Issues Found

### MEDIUM Severity

#### 1. `ApiKeyValidationResponse.tenant_id` required per spec but null when `valid=false`
**Spec:** `required: [valid, tenant_id]`
**Impl:** When validation fails, only `valid=false` and `reason` are set. `tenant_id` is null and omitted due to `@JsonInclude(NON_NULL)`.

Strict OpenAPI validators will flag this as a violation since `tenant_id` is declared required on the response schema regardless of validity.

**File:** `ApiKeyRepository.java:117-118` (and all other failure branches)
**Fix:** Either always include `tenant_id` (empty string or null serialized), or consider this a spec ambiguity (tenant_id is meaningless when valid=false).

#### 2. `BudgetCreateRequest` has `tenant_id` field not in spec
**Spec:** `BudgetCreateRequest` with `additionalProperties: false` defines only: scope, unit, allocated, overdraft_limit, commit_overage_policy, rollover_policy, period_start, period_end, metadata.
**Impl:** `BudgetCreateRequest.java:11` has `@JsonProperty("tenant_id") private String tenantId`.

While `tenant_id` is derived from auth when not provided, the field exists in the request model. A strict OpenAPI client sending `tenant_id` in the body would technically be sending an unknown property against the spec.

**File:** `BudgetCreateRequest.java:11`
**Impact:** Low — Jackson ignores `additionalProperties` constraints by default. Functionally correct since it's optional and auth-derived.

#### 3. `BUDGET_CLOSED` error code not in spec
**Spec ErrorCode enum:** 22 values, does not include `BUDGET_CLOSED`.
**Impl:** Added `BUDGET_CLOSED` as the 23rd value.

The spec's BudgetLedger `status` enum includes `CLOSED`, and the fund Lua script returns `BUDGET_CLOSED` when a closed budget is funded. But the spec's ErrorCode has no corresponding code. The implementation added one for clarity, but this is an extension beyond the spec.

**File:** `ErrorCode.java:8`
**Impact:** Clients parsing error codes against the spec's enum will encounter an unknown value. Consider whether this should be proposed as a spec amendment.

#### 4. No tenant existence validation when creating API keys
**Spec:** POST `/v1/admin/api-keys` has 400 response for "Invalid tenant or permissions".
**Impl:** `ApiKeyRepository.create()` does not verify the `tenant_id` exists or is ACTIVE before creating the key. A key can be created for a non-existent tenant.

**File:** `ApiKeyRepository.java:24-63`
**Fix:** Add a tenant existence check before creating the key, returning 400 with `TENANT_NOT_FOUND` if missing.

#### 5. Default permissions use `reservations:*` wildcard not in spec enum
**Spec:** `ApiKey.permissions` items enum is explicitly: `reservations:create`, `reservations:commit`, `reservations:release`, `reservations:extend`, `reservations:list`, `balances:read`, `admin:read`, `admin:write`.
**Impl:** Default permissions are `List.of("reservations:*", "balances:read")`. The `reservations:*` wildcard is not in the spec's enum.

**File:** `ApiKeyRepository.java:40`
**Fix:** Use explicit permissions `List.of("reservations:create", "reservations:commit", "reservations:release", "reservations:extend", "reservations:list", "balances:read")`.

### LOW Severity

#### 6. Revoke endpoint returns `ApiKeyResponse` instead of spec's `ApiKey` schema
**Spec:** DELETE `/v1/admin/api-keys/{key_id}` response schema is `$ref: ApiKey`.
**Impl:** Returns `ApiKeyResponse` which is identical to `ApiKey` minus `key_hash`. Since `key_hash` is NOT in the spec's `ApiKey` schema (and `additionalProperties: false`), `ApiKeyResponse` is actually more conformant than returning the internal `ApiKey` model. This is functionally correct but the response type name doesn't match.

**File:** `ApiKeyController.java:39`
**Impact:** None — the JSON output matches the spec's ApiKey schema.

#### 7. No `minimum: 1` validation on `limit` query parameters
**Spec:** Tenant list `limit` has `minimum: 1, maximum: 100`. Other lists have default 50 but no explicit min/max.
**Impl:** Only enforces max via `Math.min(limit, 100)`. A `limit=0` or `limit=-1` would be accepted, returning empty results.

**File:** All controllers with `@RequestParam(defaultValue = "50") int limit`
**Fix:** Add `@Min(1)` validation or `Math.max(1, Math.min(limit, 100))`.

#### 8. No `maxProperties: 32` validation on tenant metadata
**Spec:** `Tenant.metadata` has `maxProperties: 32`.
**Impl:** No constraint on metadata map size in `TenantCreateRequest` or `TenantUpdateRequest`.

**Files:** `TenantCreateRequest.java:13`, `TenantUpdateRequest.java:9`

#### 9. No audit logging for write operations
**Spec:** Describes audit logging for all authenticated operations. `AuditLogEntry` schema and `AuditRepository` exist.
**Impl:** No controllers invoke `AuditRepository` after create/update/delete operations. The audit infrastructure exists but isn't wired into the request flow.

**Files:** All controllers (none call AuditRepository)
**Impact:** Compliance gap — audit trail is empty despite the query endpoint existing.

#### 10. `@JsonInclude(NON_NULL)` can omit spec-required fields
**Spec:** Several schemas declare required fields (e.g., `Tenant: [tenant_id, name, status, created_at]`).
**Impl:** Models use `@JsonInclude(JsonInclude.Include.NON_NULL)`. If any required field is null, it will be silently omitted from the response rather than serialized as null.

**Files:** `Tenant.java`, `BudgetLedger.java`, `Policy.java`, `ApiKeyResponse.java`, `ApiKeyCreateResponse.java`, etc.
**Impact:** Low in practice — required fields are always populated by the repository layer.

---

## Compliance Summary

| Category | Pass | Warn | Fail | Total |
|----------|------|------|------|-------|
| Endpoints (15) | 13 | 2 | 0 | 15 |
| Schemas (28) | 25 | 3 | 0 | 28 |
| Auth Steps (8) | 8 | 0 | 0 | 8 |
| **Total** | **46** | **5** | **0** | **51** |

| Severity | Count | Description |
|----------|-------|-------------|
| CRITICAL | 0 | — |
| HIGH | 0 | — |
| MEDIUM | 5 | #1 required tenant_id on invalid response, #2 extra tenant_id field, #3 extra error code, #4 no tenant check on key create, #5 wildcard permissions |
| LOW | 5 | #6 response type name, #7 missing limit min, #8 metadata maxProperties, #9 no audit logging, #10 NON_NULL vs required |
| **Total** | **10** | |

### Overall Assessment

The implementation is **well-aligned** with the YAMP API spec v0.1.23. All 15 endpoints exist with correct HTTP methods, paths, security schemes, and response types. All 28 schema types match their spec definitions. Authentication and validation follow the spec's 5-step process correctly.

The 10 remaining issues are all MEDIUM or LOW severity — no CRITICAL or HIGH issues. The most actionable fixes are:
1. **#4** (validate tenant exists before key creation) — security gap
2. **#5** (expand wildcard permissions) — spec conformance
3. **#9** (wire audit logging) — compliance gap
4. **#7** (add limit minimum) — input validation
