[![CI](https://github.com/runcycles/cycles-server-admin/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server-admin/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server-admin/actions)

# RunCycles Server Admin

Administrative API for the Complete Budget Governance System, aligned with [Cycles Protocol v0.1.25](complete-budget-governance-v0.1.25.yaml).

## Overview

This service implements a budget governance system built on four integrated pillars:

| Pillar | Plane | Purpose |
|--------|-------|---------|
| **Tenant & Budget Management** | Configuration | Tenant lifecycle, budget ledgers, policy configuration |
| **Authentication & Authorization** | Identity | API key validation, permission enforcement, audit logging |
| **Runtime Enforcement** | Reservation | Budget reservations, commits, balance queries (Cycles Protocol v0.1.24) |
| **Events & Webhooks** | Observability | Event emission, webhook subscriptions, delivery with HMAC signing |

## Architecture

```
cycles-admin-service/
├── cycles-admin-service-model   # Shared domain models, DTOs, enums
├── cycles-admin-service-data    # Redis repositories, key service
└── cycles-admin-service-api     # REST controllers, auth interceptor, Spring Boot app
```

- **Language:** Java 21
- **Framework:** Spring Boot 3.5.11
- **Data Store:** Redis (via Jedis 5.2.0)
- **API Docs:** SpringDoc OpenAPI (Swagger UI)
- **Testing:** JUnit 5 + TestContainers (Redis)

## Quick Start with Docker

The fastest way to run the admin server — no Java or Maven required:

```bash
# Using pre-built image from GHCR
docker compose -f docker-compose.prod.yml up -d
```

The server starts at `http://localhost:7979`. Swagger UI: http://localhost:7979/swagger-ui.html

To run the full stack (Admin + Runtime + Events + Redis):

```bash
# Generate encryption key for webhook signing secrets (shared across all services)
export WEBHOOK_SECRET_ENCRYPTION_KEY=$(openssl rand -base64 32)

# Development (builds from source)
docker compose -f docker-compose.full-stack.yml up

# Production (pre-built images)
docker compose -f docker-compose.full-stack.prod.yml up -d
```

| Service | Port | Purpose |
|---------|------|---------|
| Redis | 6379 | Shared state store |
| Admin (`cycles-server-admin`) | 7979 | Tenant/budget/webhook CRUD, event persistence |
| Runtime (`cycles-server`) | 7878 | Reserve/commit/release, sub-10ms enforcement |
| Events (`cycles-server-events`) | 7980 | Async webhook delivery with HMAC signing |

The events service is optional — if not deployed, admin and runtime continue operating normally. Events and deliveries accumulate in Redis (with TTL) until the events service is started.

> For the complete deployment walkthrough including tenant setup, API key creation, and budget allocation, see the [full stack deployment guide](https://runcycles.io/quickstart/deploying-the-full-cycles-stack).

## Prerequisites (for building from source)

- Java 21+
- Maven 3.9+
- Redis 7+ (or Docker for TestContainers)

## Building from Source

### Build

```bash
cd cycles-admin-service
mvn clean install
```

### Run

```bash
cd cycles-admin-service/cycles-admin-service-api
mvn spring-boot:run
```

The server starts at `http://localhost:7979`. Swagger UI is available at `/swagger-ui.html`.

### Run with Integration Tests

Integration tests use TestContainers to spin up a Redis instance automatically. Docker must be running.

```bash
cd cycles-admin-service
mvn verify -P integration-tests
```

## Authentication

The API uses two authentication schemes:

| Scheme | Header | Use |
|--------|--------|-----|
| **AdminKeyAuth** | `X-Admin-API-Key` | System administration (tenant/key management, audit) |
| **ApiKeyAuth** | `X-Cycles-API-Key` | Tenant-scoped operations (budgets, reservations, balances) |

API keys use the format `cyc_live_{random}` (production) or `cyc_test_{random}` (test), where the random part is 32 cryptographically random characters. Keys are stored as bcrypt hashes; the full secret is only returned once at creation time. Recommended expiry: 90 days.

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `REDIS_HOST` | Yes | — | Redis hostname |
| `REDIS_PORT` | Yes | — | Redis port |
| `REDIS_PASSWORD` | Yes | — | Redis password (empty for no auth) |
| `ADMIN_API_KEY` | Yes | — | Master admin API key for `X-Admin-API-Key` header |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | No | (empty) | AES-256-GCM encryption key for webhook signing secrets at rest. Base64-encoded 32 bytes. If empty, secrets stored in plaintext (dev mode). |

### Webhook Secret Encryption

Webhook signing secrets are encrypted at rest in Redis using AES-256-GCM. Both `cycles-server-admin` (writes) and `cycles-server-events` (reads) must share the same encryption key.

**Generate a key:**
```bash
openssl rand -base64 32
```

**Configure in docker-compose or environment:**
```bash
export WEBHOOK_SECRET_ENCRYPTION_KEY=$(openssl rand -base64 32)
```

**How it works:**
1. Admin encrypts the signing secret before storing in Redis: `webhook:secret:{id}` = `enc:<base64(IV + ciphertext + auth_tag)>`
2. Events service decrypts on read before computing HMAC-SHA256 signatures
3. Backward compatible: existing plaintext secrets (no `enc:` prefix) are returned as-is
4. If key is not set, both services operate in pass-through mode (no encryption)

**Key management:**
- Store the key in a secrets manager (Vault, AWS Secrets Manager, etc.) — not in git
- Rotating the key requires re-encrypting all existing secrets
- Both services must be restarted with the new key simultaneously

## API Endpoints

### Pillar 1: Tenant & Budget Management

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/admin/tenants` | Create tenant | Admin |
| `GET` | `/v1/admin/tenants` | List tenants | Admin |
| `GET` | `/v1/admin/tenants/{tenant_id}` | Get tenant | Admin |
| `PATCH` | `/v1/admin/tenants/{tenant_id}` | Update tenant | Admin |
| `POST` | `/v1/admin/budgets` | Create budget ledger | ApiKey |
| `GET` | `/v1/admin/budgets` | List budget ledgers | ApiKey |
| `PATCH` | `/v1/admin/budgets?scope={scope}&unit={unit}` | Update budget | ApiKey |
| `POST` | `/v1/admin/budgets/fund?scope={scope}&unit={unit}` | Fund/adjust budget | ApiKey |
| `POST` | `/v1/admin/policies` | Create policy | ApiKey |
| `GET` | `/v1/admin/policies` | List policies | ApiKey |
| `PATCH` | `/v1/admin/policies/{policy_id}` | Update policy | ApiKey |

### Pillar 2: Authentication & Authorization

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/admin/api-keys` | Create API key | Admin |
| `GET` | `/v1/admin/api-keys` | List API keys | Admin |
| `DELETE` | `/v1/admin/api-keys/{key_id}` | Revoke API key | Admin |
| `POST` | `/v1/auth/validate` | Validate key & resolve tenant | Admin |
| `GET` | `/v1/admin/audit/logs` | Query audit logs | Admin |

### Pillar 3: Runtime Enforcement (Cycles Protocol v0.1.24)

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/reservations` | Create budget reservation | ApiKey |
| `POST` | `/v1/reservations/{reservation_id}/commit` | Commit actual spend | ApiKey |
| `GET` | `/v1/balances` | Query budget balances | ApiKey |

### Pillar 4: Events & Webhooks (v0.1.25)

**Admin webhook management** (`X-Admin-API-Key`):

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/admin/webhooks` | Create webhook subscription | Admin |
| `GET` | `/v1/admin/webhooks` | List subscriptions | Admin |
| `GET` | `/v1/admin/webhooks/{id}` | Get subscription | Admin |
| `PATCH` | `/v1/admin/webhooks/{id}` | Update subscription | Admin |
| `DELETE` | `/v1/admin/webhooks/{id}` | Delete subscription | Admin |
| `POST` | `/v1/admin/webhooks/{id}/test` | Test webhook | Admin |
| `GET` | `/v1/admin/webhooks/{id}/deliveries` | List deliveries | Admin |
| `POST` | `/v1/admin/webhooks/{id}/replay` | Replay events | Admin |
| `GET` | `/v1/admin/events` | Query events | Admin |
| `GET` | `/v1/admin/events/{id}` | Get event | Admin |
| `GET` | `/v1/admin/config/webhook-security` | Get URL policy | Admin |
| `PUT` | `/v1/admin/config/webhook-security` | Update URL policy | Admin |

**Tenant self-service** (`X-Cycles-API-Key`, requires `webhooks:read/write` or `events:read`):

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/webhooks` | Create tenant webhook | ApiKey |
| `GET` | `/v1/webhooks` | List tenant webhooks | ApiKey |
| `GET` | `/v1/webhooks/{id}` | Get tenant webhook | ApiKey |
| `PATCH` | `/v1/webhooks/{id}` | Update tenant webhook | ApiKey |
| `DELETE` | `/v1/webhooks/{id}` | Delete tenant webhook | ApiKey |
| `POST` | `/v1/webhooks/{id}/test` | Test tenant webhook | ApiKey |
| `GET` | `/v1/webhooks/{id}/deliveries` | List tenant deliveries | ApiKey |
| `GET` | `/v1/events` | Query tenant events | ApiKey |

Tenants can subscribe to `budget.*`, `reservation.*`, `tenant.*` (26 of 40 event types). Admin-only: `api_key.*`, `policy.*`, `system.*`.

**40 event types** across 6 categories: budget (15), reservation (5), tenant (6), api_key (6), policy (3), system (5).

**Webhook features:** HMAC-SHA256 signing, at-least-once delivery, exponential backoff retry, auto-disable on consecutive failures, event replay, SSRF prevention (private IP blocking by default).

## Core Concepts

### Tenants

Tenants are the top-level isolation boundary. All budgets, keys, and reservations are scoped to a tenant.

- **ID format:** kebab-case, `^[a-z0-9-]+$`, 3-64 chars (e.g., `acme-corp`, `demo-tenant`)
- **Status lifecycle:** `ACTIVE` → `SUSPENDED` ↔ `ACTIVE`, or `* → CLOSED` (irreversible)
  - `SUSPENDED` blocks new reservations; existing active reservations can still commit/release
- Supports hierarchical tenants via `parent_tenant_id` (enables budget delegation and consolidated billing)
- **Tenant creation is idempotent** — retrying with the same `tenant_id` returns the existing tenant (200) rather than failing

**Tenant-level reservation configuration:**

| Property | Default | Range | Description |
|----------|---------|-------|-------------|
| `default_commit_overage_policy` | `ALLOW_IF_AVAILABLE` | — | Default overage policy for all scopes |
| `default_reservation_ttl_ms` | `60000` (60s) | 1s – 24h | Default TTL when not specified per-reservation |
| `max_reservation_ttl_ms` | `3600000` (1h) | 1s – 24h | Maximum allowed TTL; requests exceeding this are capped |
| `max_reservation_extensions` | `10` | 0+ | Max TTL extensions per reservation (prevents zombie reservations) |
| `reservation_expiry_policy` | `AUTO_RELEASE` | — | How expired reservations are handled |

**Reservation expiry policies:**

| Policy | Behavior |
|--------|----------|
| `AUTO_RELEASE` | Expired reservations auto-release after grace period |
| `MANUAL_CLEANUP` | Require explicit release or cleanup job |
| `GRACE_ONLY` | Allow commits during grace period, then mark `EXPIRED` |

### Budget Ledgers

A budget ledger tracks finances for a specific `(scope, unit)` pair. Each ledger maintains:

| Field | Description |
|-------|-------------|
| `allocated` | Total budget cap |
| `remaining` | Available for new reservations (can go negative with overdraft) |
| `reserved` | Locked by active reservations |
| `spent` | Successfully committed |
| `debt` | Overdraft amount from `ALLOW_WITH_OVERDRAFT` commits |
| `overdraft_limit` | Maximum allowed debt (0 = no overdraft) |
| `is_over_limit` | `true` when `debt > overdraft_limit` |
| `commit_overage_policy` | Per-ledger overage policy override; inherits from tenant if unset |

**Supported units:** `USD_MICROCENTS`, `TOKENS`, `CREDITS`, `RISK_POINTS`

**Amount values** are `int64` integers (not floating-point). All `Amount` objects carry an explicit `unit` field to prevent mismatches.

**Ledger status:** `ACTIVE` (normal operations) | `FROZEN` (read-only, no new reservations or commits) | `CLOSED` (archived)

**Budget periods:** Ledgers support optional `period_start` / `period_end` with a rollover policy:

| Rollover Policy | Behavior |
|-----------------|----------|
| `NONE` | No rollover (default) |
| `CARRY_FORWARD` | Unused budget carries to next period |
| `CAP_AT_ALLOCATED` | Remaining is capped at the allocated amount |

#### Scopes

Scopes use hierarchical paths: `tenant:acme-corp/workspace:eng/agent:summarizer`

**Scope hierarchy semantics:**
- Each scope is **independent** — no automatic propagation between parent/child
- Parent scopes do NOT automatically aggregate child scope charges
- Hierarchical validation: if `tenant:acme/workspace:eng` has a budget, both `tenant:acme` and `tenant:acme/workspace:eng` must exist
- Operations on a scope do NOT affect parent/child scopes unless explicitly specified via multi-scope reservation

**Initial state** when a budget is created: `remaining = allocated`, `reserved = spent = debt = 0`. A budget ledger must exist for a scope before any reservations can be made against it.

### Funding Operations

Use `POST /v1/admin/budgets/fund?scope={scope}&unit={unit}` with one of:

| Operation | Effect |
|-----------|--------|
| `CREDIT` | `allocated += amount`, `remaining += amount` |
| `DEBIT` | `allocated -= amount`, `remaining -= amount` (fails if remaining would go negative) |
| `RESET` | `allocated = amount`, `remaining = amount - reserved - spent - debt` |
| `REPAY_DEBT` | `debt -= amount` (uses remaining if debt < amount) |

All funding operations support `idempotency_key` (prevents double-funding; replayed requests return original response) and an optional `reason` field for the audit trail.

### Commit Overage Policies

Controls behavior when actual spend exceeds the reserved amount:

| Policy | Behavior |
|--------|----------|
| `REJECT` | Fail if actual > reserved |
| `ALLOW_IF_AVAILABLE` | Charge delta from remaining budget if available |
| `ALLOW_WITH_OVERDRAFT` | Create debt up to `overdraft_limit` if budget exhausted |

Policy resolution order (highest priority wins): **Reservation** > **Policy** > **Budget Ledger** > **Tenant default**

### Policies

Policies define caps, rate limits, and behavioral rules matched by scope patterns:

- `tenant:acme-corp` — exact match
- `tenant:acme-corp/*` — all descendant scopes
- `agent:*` — all agents across all tenants
- `*/agent:summarizer` — specific agent across all tenants

**Policy status:** `ACTIVE` | `DISABLED`

Policies support:
- **Priority ordering** — higher priority policies evaluated first; highest priority wins on conflict
- **Effective date windows** — `effective_from` / `effective_until` for time-bounded policies (trials, temporary restrictions)
- **Rate limits** — `max_reservations_per_minute`, `max_commits_per_minute`
- **TTL overrides** — `default_ttl_ms`, `max_ttl_ms`, `max_extensions` per matching scope
- **Commit overage policy override** — overrides both tenant and budget ledger defaults

### Caps (Soft-Landing Constraints)

From Cycles Protocol v0.1.24:

```json
{
  "max_tokens": 4096,
  "max_steps_remaining": 10,
  "tool_allowlist": ["read_file", "search"],
  "tool_denylist": ["execute_command"],
  "cooldown_ms": 5000
}
```

### Permissions

API keys carry granular permissions:

| Permission | Description |
|------------|-------------|
| `reservations:create` | Create budget reservations |
| `reservations:commit` | Commit actual spend |
| `reservations:release` | Release unused reservations |
| `reservations:extend` | Extend reservation TTL |
| `reservations:list` | List reservations |
| `balances:read` | Query budget balances |
| `admin:read` | Read admin resources |
| `admin:write` | Modify admin resources |

**Defaults:** Tenant keys get `[reservations:*, balances:read]`. Admin keys get `[admin:*, reservations:*, balances:*]`.

Keys can be further restricted to specific scopes via `scope_filter` (e.g., `["workspace:eng", "agent:*"]`).

**Key revocation** (`DELETE /v1/admin/api-keys/{key_id}`) is permanent and cannot be undone. Revoked keys remain in the database for audit trail. Active reservations created with a revoked key can still be committed/released, but no new operations are permitted.

### API Key Validation

`POST /v1/auth/validate` performs these checks in order:

1. Key exists in database
2. Key hash matches
3. Status is `ACTIVE` (not `REVOKED` or `EXPIRED`)
4. Current time < `expires_at`
5. Tenant is `ACTIVE` (not `SUSPENDED` or `CLOSED`)

On success, returns `tenant_id`, `permissions`, and `scope_filter`. On failure, returns `valid: false` with a `reason` (e.g., `"REVOKED"`, `"EXPIRED"`). Results should be cached with a short TTL (~60s) and invalidated on key revocation.

### Reservation Governance Flow

When `POST /v1/reservations` is called, the governance layer executes:

1. Validate `X-Cycles-API-Key` via `/v1/auth/validate`
2. Derive effective tenant from key
3. Validate `Subject.tenant` matches effective tenant (403 if mismatch)
4. Check tenant status (block if `SUSPENDED` or `CLOSED`)
5. Check budget ledger exists for scope
6. Apply matching policies (caps, rate limits)
7. Execute Cycles reservation logic
8. Log operation to audit trail

**Commit governance** (`POST /v1/reservations/{id}/commit`): validates key and tenant as above, applies `ALLOW_WITH_OVERDRAFT` policy (creates debt up to `overdraft_limit`), and logs to audit trail.

**Balance governance** (`GET /v1/balances`): returns full ledger state including `debt`, `overdraft_limit`, and `is_over_limit`; scoped to the effective tenant; supports scope filtering via query params.

### Audit Logs

`GET /v1/admin/audit/logs` provides a compliance-ready audit trail (SOC2, GDPR).

**Query filters:** `tenant_id`, `key_id`, `operation`, `status` (HTTP code), `from`/`to` (datetime range)

**Retention recommendation:** 90 days hot storage, 1 year cold storage.

### Pagination

All list endpoints use **cursor-based pagination**:

| Parameter | Description |
|-----------|-------------|
| `cursor` | Opaque cursor from previous response's `next_cursor` |
| `limit` | Page size (default: 50, max: 200) |

Responses include `next_cursor` and `has_more` fields.

## Error Handling

All errors return a standard `ErrorResponse`:

```json
{
  "error": "BUDGET_EXCEEDED",
  "message": "Remaining budget insufficient for reservation",
  "request_id": "req_abc123",
  "details": {}
}
```

**Cycles v0.1.24 error codes:**
`INVALID_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `BUDGET_EXCEEDED`, `RESERVATION_EXPIRED`, `RESERVATION_FINALIZED`, `IDEMPOTENCY_MISMATCH`, `UNIT_MISMATCH`, `OVERDRAFT_LIMIT_EXCEEDED`, `DEBT_OUTSTANDING`, `INTERNAL_ERROR`

**Governance error codes:**
`TENANT_NOT_FOUND`, `TENANT_SUSPENDED`, `TENANT_CLOSED`, `BUDGET_NOT_FOUND`, `BUDGET_FROZEN`, `POLICY_VIOLATION`, `INSUFFICIENT_PERMISSIONS`, `KEY_REVOKED`, `KEY_EXPIRED`, `DUPLICATE_RESOURCE`

## Deployment Models

| Model | Description |
|-------|-------------|
| **Single-service** | All four pillars in one deployment |
| **Split-plane** | Admin + events separate from runtime enforcement |
| **Full stack** | Admin (7979) + Runtime (7878) + Events (7980) + Redis |

## Protocol Specification

The full OpenAPI 3.1.0 specification is in [`complete-budget-governance-v0.1.25.yaml`](complete-budget-governance-v0.1.25.yaml).

v0.1.25 adds Pillar 4 (Events & Webhooks): 40 event types, 20 webhook endpoints, HMAC-SHA256 signing, at-least-once delivery, and webhook secret encryption at rest.

## Documentation

- [Cycles Documentation](https://runcycles.io) — full docs site
- [Deploy the Full Stack](https://runcycles.io/quickstart/deploying-the-full-cycles-stack) — deployment guide with admin server setup
- [Tenant Management](https://runcycles.io/how-to/tenant-creation-and-management-in-cycles) — tenant creation and lifecycle
- [Budget Allocation and Management](https://runcycles.io/how-to/budget-allocation-and-management-in-cycles) — budget configuration
- [API Key Management](https://runcycles.io/how-to/api-key-management-in-cycles) — API key lifecycle

## License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
