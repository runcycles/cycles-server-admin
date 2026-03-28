# Historical Audit Rounds (Archived)

> This file preserves audit rounds 1-4 against spec v0.1.23 for historical reference.
> All 19 issues identified across these rounds have been fixed.
> The current audit is in [AUDIT.md](./AUDIT.md).

---

# Round 1: Audit: Admin Implementation vs. YAMP API Spec (v0.1.23)

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
| `/v1/admin/budgets/fund?scope=&unit=` | POST | ApiKeyAuth | ApiKeyAuth | BudgetFundingRequest | BudgetFundingResponse / 404 / 409 | **PASS** |
| `/v1/admin/policies` | POST | ApiKeyAuth | ApiKeyAuth | PolicyCreateRequest | 201 Policy | **PASS** |
| `/v1/admin/policies` | GET | ApiKeyAuth | ApiKeyAuth | scope_pattern, status, cursor, limit | PolicyListResponse | **PASS** |
| `/v1/admin/api-keys` | POST | AdminKeyAuth | AdminKeyAuth | ApiKeyCreateRequest | 201 ApiKeyCreateResponse | **PASS** |
| `/v1/admin/api-keys` | GET | AdminKeyAuth | AdminKeyAuth | tenant_id (req), status, cursor, limit | ApiKeyListResponse | **PASS** |
| `/v1/admin/api-keys/{id}` | DELETE | AdminKeyAuth | AdminKeyAuth | path: key_id, query: reason | ApiKey | **WARN** (see #6) |
| `/v1/auth/validate` | POST | AdminKeyAuth | AdminKeyAuth | ApiKeyValidationRequest | ApiKeyValidationResponse | **WARN** (see #1) |
| `/v1/admin/audit/logs` | GET | AdminKeyAuth | AdminKeyAuth | tenant_id, key_id, operation, status, from, to, cursor, limit | AuditLogListResponse | **PASS** |
| `/v1/balances` | GET | ApiKeyAuth | ApiKeyAuth | tenant_id, scope_prefix, unit | BalanceQueryResponse | **PASS** |

## Issues Found (10)

| # | Severity | Issue |
|---|----------|-------|
| 1 | MEDIUM | `ApiKeyValidationResponse.tenant_id` required per spec but null when `valid=false` |
| 2 | MEDIUM | `BudgetCreateRequest` has `tenant_id` field not in spec |
| 3 | MEDIUM | `BUDGET_CLOSED` error code not in spec |
| 4 | MEDIUM | No tenant existence validation when creating API keys |
| 5 | MEDIUM | Default permissions use `reservations:*` wildcard not in spec enum |
| 6 | LOW | Revoke endpoint returns `ApiKeyResponse` instead of spec's `ApiKey` schema |
| 7 | LOW | No `minimum: 1` validation on `limit` query parameters |
| 8 | LOW | No `maxProperties: 32` validation on tenant metadata |
| 9 | LOW | No audit logging for write operations |
| 10 | LOW | `@JsonInclude(NON_NULL)` can omit spec-required fields |

---

# Round 2: Re-Audit (v0.1.23)

**Date**: 2026-03-09

**All 10 Round 1 issues fixed.** Re-audit found 4 new issues:

| # | Severity | Issue |
|---|----------|-------|
| 11 | MEDIUM | BudgetFundingResponse class-level NON_NULL on required fields |
| 12 | LOW | AuditLogEntry class-level NON_NULL on required fields |
| 13 | LOW | List responses class-level NON_NULL on required fields |
| 14 | LOW | `ApiKey.keyHash` exposed via `@JsonProperty` instead of `@JsonIgnore` |

---

# Round 3: Re-Audit (v0.1.23)

**Date**: 2026-03-09

**All 14 Round 1-2 issues fixed.** Re-audit found 5 new issues:

| # | Severity | Issue |
|---|----------|-------|
| 15 | CRITICAL | Budget fund endpoint allows cross-tenant budget modification |
| 16 | HIGH | Budget list endpoint allows cross-tenant budget enumeration |
| 17 | HIGH | Balance query endpoint allows cross-tenant balance reads |
| 18 | LOW | ErrorResponse.details serializes as null instead of being omitted |
| 19 | LOW | BalanceQueryResponse uses class-level NON_NULL |

---

# Round 4: Final Re-Audit (v0.1.23)

**Date**: 2026-03-09

**All 19 issues from Rounds 1-3 fixed. Zero new issues found.**

The implementation achieved full compliance with YAMP API spec v0.1.23 across all 15 endpoints, 27 schemas, 10 security requirements, and 15 behavioral constraints.
