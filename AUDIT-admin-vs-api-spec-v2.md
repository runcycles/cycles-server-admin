# Audit Report: Admin Implementation vs YAMP API Spec v0.1.23 (Round 2)

**Date**: 2026-03-09
**Spec**: `complete-budget-governance-v0.1.23.yaml`
**Implementation**: `cycles-admin-service` (Spring Boot 3 / Java 21)

## Executive Summary

After applying all 10 fixes from the Round 1 audit, the implementation is substantially compliant with the YAMP API spec v0.1.23. This re-audit found **4 new issues** (1 MEDIUM, 3 LOW) that were not covered in the first audit or were introduced during fixes.

### Previous Issues — Status

| # | Issue | Status |
|---|-------|--------|
| 1 | ApiKeyValidationResponse required fields not always serialized | **FIXED** — `valid` and `tenant_id` always serialized; optional fields use field-level `@JsonInclude(NON_NULL)` |
| 2 | BudgetCreateRequest contained `tenant_id` field (not in spec) | **FIXED** — `tenant_id` removed; derived from auth context in controller |
| 3 | ErrorCode enum contained `BUDGET_CLOSED` (not in spec) | **FIXED** — Removed; `budgetClosed()` now maps to `BUDGET_FROZEN` |
| 4 | API key creation didn't validate tenant existence | **FIXED** — Tenant lookup + status check before key creation |
| 5 | Default permissions used wildcards (`reservations:*`) | **FIXED** — Explicit enum values: `reservations:create`, `reservations:commit`, etc. |
| 6 | Revoke endpoint returned wrong schema | **FIXED** — Returns `ApiKeyResponse` (maps to spec's `ApiKey` schema) |
| 7 | `limit` parameter lacked `minimum: 1` enforcement | **FIXED** — `Math.max(1, Math.min(limit, 100))` on all list endpoints |
| 8 | Tenant metadata lacked `maxProperties: 32` | **FIXED** — `@Size(max = 32)` on create and update request DTOs |
| 9 | Missing audit logging on write operations | **FIXED** — Audit logging wired into all controllers for create/update/fund/revoke |
| 10 | Required fields could be omitted by class-level `@JsonInclude(NON_NULL)` | **FIXED** — Required fields always serialized; optional fields use field-level annotation |

---

## New Issues Found

### Issue 11 — [MEDIUM] BudgetFundingResponse required fields may be omitted when null

**Spec** (lines 428-448):
```yaml
BudgetFundingResponse:
  required: [operation, previous_allocated, new_allocated, previous_remaining, new_remaining]
```

**Implementation** (`BudgetFundingResponse.java:6`):
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetFundingResponse {
```

The class-level `@JsonInclude(NON_NULL)` means the 5 required fields (`operation`, `previous_allocated`, `new_allocated`, `previous_remaining`, `new_remaining`) could be omitted if null. The optional fields (`previous_debt`, `new_debt`, `timestamp`) should use field-level `@JsonInclude(NON_NULL)` instead.

**Risk**: Clients relying on required fields being always present will break if any are null.

**Fix**: Remove class-level `@JsonInclude(NON_NULL)` and add field-level annotations on `previous_debt`, `new_debt`, and `timestamp` only.

---

### Issue 12 — [LOW] AuditLogEntry required fields may be omitted when null

**Spec** (lines 767-773):
```yaml
AuditLogEntry:
  required: [log_id, timestamp, tenant_id, operation, status]
```

**Implementation** (`AuditLogEntry.java:9`):
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogEntry {
```

Same pattern as Issue 11. The 5 required fields (`log_id`, `timestamp`, `tenant_id`, `operation`, `status`) could be suppressed by the class-level `NON_NULL`. The remaining fields (`key_id`, `user_agent`, `source_ip`, `request_id`, `error_code`, `subject`, `action`, `amount`, `metadata`) are optional.

**Fix**: Remove class-level annotation; add field-level `@JsonInclude(NON_NULL)` on optional fields.

---

### Issue 13 — [LOW] AuditLogListResponse and other list responses use class-level NON_NULL on required `logs`/`tenants`/`ledgers` field

**Spec**: All list responses have their collection field as `required`:
- `TenantListResponse: required: [tenants]`
- `BudgetListResponse: required: [ledgers]`
- `PolicyListResponse: required: [policies]`
- `ApiKeyListResponse: required: [keys]`
- `AuditLogListResponse: required: [logs]`

**Implementation**: All five list response classes use `@JsonInclude(JsonInclude.Include.NON_NULL)` at class level, meaning the required collection field could be omitted if null.

In practice, controllers always populate the list field, so this is low risk. But for strict compliance, the required fields should always be serialized.

**Fix**: Either remove class-level `NON_NULL` from list responses, or add explicit always-include on the required collection field.

---

### Issue 14 — [LOW] `ApiKey` internal model exposes `key_hash` in serialization

**Spec** (lines 598-667): The `ApiKey` schema does **not** include a `key_hash` property. The spec explicitly states: "Full key is never returned after creation."

**Implementation** (`ApiKey.java:11`):
```java
@JsonProperty("key_hash") private String keyHash;
```

The internal `ApiKey` entity includes `key_hash` with a `@JsonProperty` annotation. While the API controllers use `ApiKeyResponse.from(key)` to convert before returning (which excludes `key_hash`), the raw `ApiKey` class is also used in the `list()` return type. In `ApiKeyController.list()`, keys are correctly converted via `ApiKeyResponse::from`. However, any future direct serialization of `ApiKey` would leak the hash.

**Risk**: Low — the controller layer correctly filters through `ApiKeyResponse`. But defense-in-depth suggests `key_hash` should use `@JsonIgnore` instead of `@JsonProperty`.

**Fix**: Change `@JsonProperty("key_hash")` to `@JsonIgnore` on `ApiKey.keyHash`.

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
| `/v1/admin/budgets` | GET | `BudgetController.list` | ApiKeyAuth | OK |
| `/v1/admin/budgets/fund?scope=&unit=` | POST | `BudgetController.fund` | ApiKeyAuth | OK |
| `/v1/admin/policies` | POST | `PolicyController.create` | ApiKeyAuth | OK |
| `/v1/admin/policies` | GET | `PolicyController.list` | ApiKeyAuth | OK |
| `/v1/admin/api-keys` | POST | `ApiKeyController.create` | AdminKeyAuth | OK |
| `/v1/admin/api-keys` | GET | `ApiKeyController.list` | AdminKeyAuth | OK |
| `/v1/admin/api-keys/{key_id}` | DELETE | `ApiKeyController.revoke` | AdminKeyAuth | OK |
| `/v1/auth/validate` | POST | `AuthController.validate` | AdminKeyAuth | OK |
| `/v1/admin/audit/logs` | GET | `AuditController.list` | AdminKeyAuth | OK |
| `/v1/balances` | GET | `BalanceController.query` | ApiKeyAuth | OK |

### Schemas

| Spec Schema | Implementation | Status |
|---|---|---|
| `UnitEnum` | `shared/UnitEnum.java` — 4 values match | OK |
| `Amount` | `shared/Amount.java` — `unit` + `amount` with validation | OK |
| `CommitOveragePolicy` | `shared/CommitOveragePolicy.java` — 3 values match | OK |
| `Caps` | `shared/Caps.java` | OK |
| `ErrorCode` | `shared/ErrorCode.java` — 22 values match spec exactly | OK |
| `ErrorResponse` | `shared/ErrorResponse.java` — `error`, `message`, `request_id`, `details` | OK |
| `Tenant` | `tenant/Tenant.java` — required fields always serialized | OK |
| `TenantCreateRequest` | `tenant/TenantCreateRequest.java` — pattern + size constraints | OK |
| `TenantListResponse` | `tenant/TenantListResponse.java` | **Issue 13** |
| `BudgetLedger` | `budget/BudgetLedger.java` — required fields always serialized | OK |
| `BudgetCreateRequest` | `budget/BudgetCreateRequest.java` — no tenant_id | OK |
| `BudgetFundingRequest` | `budget/BudgetFundingRequest.java` — constraints match | OK |
| `BudgetFundingResponse` | `budget/BudgetFundingResponse.java` | **Issue 11** |
| `BudgetListResponse` | `budget/BudgetListResponse.java` | **Issue 13** |
| `Policy` | `policy/Policy.java` — required fields always serialized | OK |
| `PolicyCreateRequest` | `policy/PolicyCreateRequest.java` | OK |
| `PolicyListResponse` | `policy/PolicyListResponse.java` | **Issue 13** |
| `ApiKey` | `auth/ApiKey.java` — internal model | **Issue 14** |
| `ApiKeyCreateRequest` | `auth/ApiKeyCreateRequest.java` | OK |
| `ApiKeyCreateResponse` | `auth/ApiKeyCreateResponse.java` — all required fields serialized | OK |
| `ApiKeyResponse` (= ApiKey for API) | `auth/ApiKeyResponse.java` — required fields always serialized | OK |
| `ApiKeyListResponse` | `auth/ApiKeyListResponse.java` | **Issue 13** |
| `ApiKeyValidationRequest` | `auth/ApiKeyValidationRequest.java` | OK |
| `ApiKeyValidationResponse` | `auth/ApiKeyValidationResponse.java` — required fields always serialized | OK |
| `AuditLogEntry` | `audit/AuditLogEntry.java` | **Issue 12** |
| `AuditLogListResponse` | `audit/AuditLogListResponse.java` | **Issue 13** |

### Security

| Spec Requirement | Implementation | Status |
|---|---|---|
| `AdminKeyAuth` via `X-Admin-API-Key` | `AuthInterceptor.validateAdminKey()` | OK |
| `ApiKeyAuth` via `X-Cycles-API-Key` | `AuthInterceptor.validateApiKey()` | OK |
| Admin endpoints require AdminKeyAuth | Correct routing in `requiresAdminKey()` | OK |
| Tenant-scoped endpoints require ApiKeyAuth | Correct routing in `requiresApiKey()` | OK |
| Key validation checks: exists, hash, status, expiry, tenant status | All 5 checks in `ApiKeyRepository.validate()` | OK |

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

---

## Summary

| Severity | Count | Issues |
|---|---|---|
| MEDIUM | 1 | #11 (BudgetFundingResponse NON_NULL on required fields) |
| LOW | 3 | #12, #13, #14 |
| **Total** | **4** | |

The implementation is well-aligned with the spec. The remaining issues are all related to serialization safety (`@JsonInclude` placement) and a defense-in-depth concern (`key_hash` exposure). None affect correct behavior in the current controller flow — they are about ensuring robustness if the code evolves.
