[![CI](https://github.com/runcycles/cycles-server-admin/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server-admin/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server-admin/actions)

# Runcycles Admin Server

Administrative API for the Complete Budget Governance System, aligned with [Cycles Protocol v0.1.25.14](https://github.com/runcycles/cycles-protocol/blob/main/cycles-governance-admin-v0.1.25.yaml).

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
| **AdminKeyAuth** | `X-Admin-API-Key` | System administration (tenant/key management, audit, dashboard) |
| **ApiKeyAuth** | `X-Cycles-API-Key` | Tenant-scoped operations (budgets, reservations, balances) |

AdminKeyAuth is also accepted as an alternative to ApiKeyAuth on an explicit per-operation allowlist — the authoritative list is the union of operations whose `security:` block declares `AdminKeyAuth` in the [governance spec](https://github.com/runcycles/cycles-protocol/blob/main/cycles-governance-admin-v0.1.25.yaml). This allowlist has grown over several revisions (v0.1.25.5 read ops; v0.1.25.6 fund; v0.1.25.13 createBudget / createPolicy / updatePolicy; v0.1.25.14 six tenant-scoped webhook ops) — consulting per-operation security blocks avoids drift between this prose and the actual wiring. On list and fund endpoints, a tenant scoping parameter (`tenant_id` or `tenant` depending on the operation — see the spec) is required for admin callers; 400 `INVALID_REQUEST` if missing. Lookup-style endpoints that uniquely identify a resource by non-tenant key (e.g. `GET /v1/admin/budgets/lookup`, where `(scope, unit)` is unique) do not require a tenant parameter.

API keys use the format `cyc_live_{random}` (production) or `cyc_test_{random}` (test), where the random part is 32 cryptographically random characters. Keys are stored as bcrypt hashes; the full secret is only returned once at creation time. Recommended expiry: 90 days.

### API Key Permissions (27 total)

| Category | Permissions | Notes |
|---|---|---|
| **Runtime (10 defaults)** | `reservations:create/commit/release/extend/list`, `balances:read`, `budgets:read/write`, `policies:read/write` | Assigned by default when no permissions specified |
| **Webhooks (3, v0.1.25)** | `webhooks:write`, `webhooks:read`, `events:read` | For tenant self-service at `/v1/webhooks` and `/v1/events` |
| **Admin wildcards (2)** | `admin:read`, `admin:write` | Wildcards: `admin:write` satisfies any `*:write`, `admin:read` satisfies any `*:read` |
| **Admin granular (12, v0.1.25)** | `admin:tenants:read/write`, `admin:budgets:read/write`, `admin:policies:read/write`, `admin:apikeys:read/write`, `admin:webhooks:read/write`, `admin:events:read`, `admin:audit:read` | Finer-grained alternative to wildcards |

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `REDIS_HOST` | Yes | — | Redis hostname |
| `REDIS_PORT` | Yes | — | Redis port |
| `REDIS_PASSWORD` | Yes | — | Redis password (empty for no auth) |
| `ADMIN_API_KEY` | Yes | — | Master admin API key for `X-Admin-API-Key` header |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | No | (empty) | AES-256-GCM encryption key for webhook signing secrets at rest. Base64-encoded 32 bytes. If empty, secrets stored in plaintext (dev mode). |
| `LOG_LEVEL` | No | `INFO` | Application logging level (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `SWAGGER_ENABLED` | No | `false` | Enable Swagger UI at `/swagger-ui.html` |
| `EVENT_TTL_DAYS` | No | `90` | Event retention in Redis (days) |
| `DELIVERY_TTL_DAYS` | No | `14` | Webhook delivery retention in Redis (days) |
| `DASHBOARD_CORS_ORIGIN` | No | `http://localhost:5173` | Comma-separated list of origins allowed to call `/v1/**` from a browser. **Must be set in production** to your dashboard URL (e.g. `https://dash.example.com`). The default is the Vite dev server and will NOT work for prod dashboard deployments. |

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

### Webhook Security (SSRF Protection)

Webhook URLs are validated on creation and update to prevent SSRF attacks:

| Check | Default | Description |
|-------|---------|-------------|
| HTTPS required | `allow_http: false` | Only HTTPS URLs accepted. Set `true` for local dev. |
| Private IP blocking | RFC 1918 ranges blocked | Resolved IPs checked against `blocked_cidr_ranges` |
| URL patterns | (none) | Optional allowlist via `allowed_url_patterns` |

**Default blocked CIDRs:** `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16`, `::1/128`, `fc00::/7`

**Manage via API:**

```bash
# View current config
curl http://localhost:7979/v1/admin/config/webhook-security \
  -H "X-Admin-API-Key: $ADMIN_API_KEY"

# Update config
curl -X PUT http://localhost:7979/v1/admin/config/webhook-security \
  -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"allow_http": true, "blocked_cidr_ranges": []}'
```

**Local development with Docker:** When running the full stack via `docker-compose`, webhook receivers on `localhost` or Docker gateway IPs are blocked by default. To test webhooks locally:

1. Enable HTTP and clear CIDR blocks:
   ```bash
   curl -X PUT http://localhost:7979/v1/admin/config/webhook-security \
     -H "X-Admin-API-Key: $ADMIN_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{"allow_http": true, "blocked_cidr_ranges": []}'
   ```
2. Use `host.docker.internal` (macOS/Windows) or the Docker bridge IP as the webhook URL
3. Or run your webhook receiver as a container on the same Docker network

> **Production:** Always keep `allow_http: false` and the default CIDR blocks enabled.

## API Endpoints

### Pillar 1: Tenant & Budget Management

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/admin/tenants` | Create tenant | Admin |
| `GET` | `/v1/admin/tenants` | List tenants | Admin |
| `GET` | `/v1/admin/tenants/{tenant_id}` | Get tenant | Admin |
| `PATCH` | `/v1/admin/tenants/{tenant_id}` | Update tenant | Admin |
| `POST` | `/v1/admin/budgets` | Create budget ledger | ApiKey |
| `GET` | `/v1/admin/budgets` | List budget ledgers | ApiKey / Admin |
| `GET` | `/v1/admin/budgets/lookup?scope={scope}&unit={unit}` | Exact budget lookup | ApiKey / Admin |
| `PATCH` | `/v1/admin/budgets?scope={scope}&unit={unit}` | Update budget | Admin |
| `POST` | `/v1/admin/budgets/fund?scope={scope}&unit={unit}` | Fund/adjust budget | ApiKey / Admin |
| `POST` | `/v1/admin/policies` | Create policy | ApiKey |
| `GET` | `/v1/admin/policies` | List policies | ApiKey / Admin |
| `PATCH` | `/v1/admin/policies/{policy_id}` | Update policy | ApiKey |

### Pillar 2: Authentication & Authorization

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/admin/api-keys` | Create API key | Admin |
| `GET` | `/v1/admin/api-keys` | List API keys | Admin |
| `PATCH` | `/v1/admin/api-keys/{key_id}` | Update key properties | Admin |
| `DELETE` | `/v1/admin/api-keys/{key_id}` | Revoke API key | Admin |
| `POST` | `/v1/auth/validate` | Validate key & resolve tenant | Admin |
| `GET` | `/v1/auth/introspect` | Introspect auth & capabilities | Admin |
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
| `POST` | `/v1/admin/webhooks/{id}/replay` | Replay events (202, 409 if in progress) | Admin |
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

### Budget Operations (v0.1.25.6)

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/admin/budgets/freeze` | Freeze budget (ACTIVE → FROZEN) | Admin |
| `POST` | `/v1/admin/budgets/unfreeze` | Unfreeze budget (FROZEN → ACTIVE) | Admin |

`POST /v1/admin/budgets/fund` also accepts AdminKeyAuth (dual-auth, `tenant_id` required).

### Dashboard (v0.1.25.5)

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `GET` | `/v1/admin/overview` | Operational health overview | Admin |
| `GET` | `/v1/auth/introspect` | Auth introspection & capabilities | Admin |

The overview endpoint returns a single-request aggregated payload with tenant/budget/webhook counts, top-offender arrays (over-limit, debt, failing webhooks), and recent event summaries. The introspect endpoint returns effective capabilities for dashboard UI gating.

**40 event types** across 6 categories: budget (15), reservation (5), tenant (6), api_key (6), policy (3), system (5).

**Webhook features:** HMAC-SHA256 signing, at-least-once delivery, exponential backoff retry, auto-disable on consecutive failures, event replay with distributed lock (prevents duplicate replays), SSRF prevention (private IP blocking by default).

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

**Ledger status:** `ACTIVE` (normal operations) | `FROZEN` (no new reservations, commits, or funding; property updates still allowed) | `CLOSED` (archived)

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
| `CREDIT` | `allocated += amount`, `remaining += amount`. Preserves spent / reserved / debt. |
| `DEBIT` | `allocated -= amount`, `remaining -= amount` (fails if remaining would go negative). Preserves spent / reserved / debt. |
| `RESET` | `allocated = amount`, `remaining = amount - reserved - spent - debt`. Preserves spent / reserved / debt. Use for **resizing the ceiling** (plan changes, limit adjustments). NOT for billing-period boundaries — use `RESET_SPENT`. |
| `RESET_SPENT` (v0.1.25.18+) | `allocated = amount`, `spent = request.spent` (defaults to 0), `remaining = allocated - spent - reserved - debt`. Preserves reserved (active reservations straddle the period) and debt (use `REPAY_DEBT` to clear). Use for **starting a new billing period**, migrations, prorated signups, and consumption corrections. Optional `spent` field (Amount, must be ≥ 0, unit must match) lets the operator set a specific starting consumption — emits `budget.reset_spent` event with `spent_override_provided` flag for audit. |
| `REPAY_DEBT` | `debt -= amount` (uses remaining if debt < amount). |

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

**Defaults:** Tenant keys get the 10 default permissions listed in the table above. `admin:read` and `admin:write` act as wildcards for backward compatibility (see table).

Keys can be further restricted to specific scopes via `scope_filter` (e.g., `["workspace:eng", "agent:*"]`).

**Key update** (`PATCH /v1/admin/api-keys/{key_id}`) allows changing permissions, scope_filter, name, description, and metadata without rotating the key secret.

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

**Query filters:** `tenant_id`, `key_id`, `operation`, `status` (HTTP code), `resource_type`, `resource_id`, `from`/`to` (datetime range)

**Retention recommendation:** 90 days hot storage, 1 year cold storage.

### Pagination

All list endpoints use **cursor-based pagination**:

| Parameter | Description |
|-----------|-------------|
| `cursor` | Opaque cursor from previous response's `next_cursor` |
| `limit` | Page size (default: 50, max: 100) |

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
`TENANT_NOT_FOUND`, `TENANT_SUSPENDED`, `TENANT_CLOSED`, `BUDGET_NOT_FOUND`, `BUDGET_FROZEN`, `BUDGET_CLOSED`, `POLICY_VIOLATION`, `INSUFFICIENT_PERMISSIONS`, `KEY_REVOKED`, `KEY_EXPIRED`, `DUPLICATE_RESOURCE`, `WEBHOOK_NOT_FOUND`, `WEBHOOK_URL_INVALID`, `EVENT_NOT_FOUND`, `REPLAY_IN_PROGRESS` (409 — concurrent replay on same subscription)

## Deployment Models

| Model | Description |
|-------|-------------|
| **Single-service** | All four pillars in one deployment |
| **Split-plane** | Admin + events separate from runtime enforcement |
| **Full stack** | Admin (7979) + Runtime (7878) + Events (7980) + Redis |

## Observability

**Health endpoints** (Spring Boot Actuator):

| Endpoint | Use |
|----------|-----|
| `GET /actuator/health` | Aggregate health (use for debugging) |
| `GET /actuator/health/liveness` | K8s liveness probe — is the JVM alive? |
| `GET /actuator/health/readiness` | K8s readiness probe — is the app ready to serve traffic? |
| `GET /actuator/info` | Build info (version, git commit) |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

Docker healthchecks hit `/actuator/health/liveness` (not the aggregate endpoint) so a degraded readiness state doesn't restart the container.

**Metrics:**

Spring Boot auto-publishes `http_server_requests_seconds_*` (latency/count/errors per URI + method + status), JVM, Jedis pool, and logback counters. The admin service adds two custom counters for domain operations:

| Metric | Tags | Description |
|--------|------|-------------|
| `cycles_admin_events_emitted_total` | `type`, `result` | Events emitted (`result` = `success` \| `failure`) |
| `cycles_admin_webhook_dispatched_total` | `result` | Webhook delivery enqueue attempts (`result` = `queued` \| `failure`) |

All metrics are tagged with `application=cycles-admin-service` for multi-service Prometheus/Grafana dashboards.

**CORS:**

Browser clients (the dashboard) call `/v1/**` via CORS. The server allowlists:

| | Values |
|---|---|
| Methods | `GET`, `POST`, `PATCH`, `DELETE`, `OPTIONS` |
| Request headers | `X-Admin-API-Key`, `X-Cycles-API-Key`, `X-Request-Id`, `Content-Type` |
| Exposed response headers | `X-Request-Id` (for correlation) |
| Origins | `DASHBOARD_CORS_ORIGIN` env var (comma-separated), default `http://localhost:5173` |

Multiple origins are supported for staging + prod deployments behind the same server: `DASHBOARD_CORS_ORIGIN=https://dash.example.com,https://staging.example.com`.

## Protocol Specification

The full OpenAPI 3.1.0 specification is [`cycles-governance-admin-v0.1.25.yaml`](https://github.com/runcycles/cycles-protocol/blob/main/cycles-governance-admin-v0.1.25.yaml) in the [cycles-protocol](https://github.com/runcycles/cycles-protocol) repository.

v0.1.25 adds Pillar 4 (Events & Webhooks): 40 event types, 20 webhook endpoints, HMAC-SHA256 signing, at-least-once delivery, and webhook secret encryption at rest.

v0.1.25.4 enforces strict spec compliance: `additionalProperties: false` on all request and response models, range/size constraints on all fields per spec, distributed replay lock (409 `REPLAY_IN_PROGRESS`), and cascading `@Valid` on nested objects.

v0.1.25.5 adds admin dashboard support: dual-auth allowlist (AdminKeyAuth on budget/policy reads), exact budget lookup, server-aggregated overview endpoint, auth introspection with capabilities, and strict dashboard response schemas.

v0.1.25.6 adds budget freeze/unfreeze action endpoints, dual-auth on fund, and granular tenant permissions (`budgets:read/write`, `policies:read/write`).

v0.1.25.7 adds backward-compatible wildcard fallback (`admin:write` satisfies any `*:write`, `admin:read` any `*:read`), `PATCH /v1/admin/api-keys/{key_id}` for updating key permissions/metadata without secret rotation, reusable `Permission` enum schema, full 401 coverage on all 45 endpoints, centralized FROZEN semantics, detailed webhook test error messages, and rich audit entries with `resource_type`, `resource_id`, and contextual `metadata` on all mutating endpoints.

v0.1.25.8 adds dashboard and observability hardening for v0.1.26 readiness: `EventDataReservationDenied` extensibility (`policy_id`, `deny_detail`, open-string `reason_code`), `AdminOverviewResponse` enrichments (`recent_denials_by_reason` auto-populated on v0.1.25.x, plus `quota_health`, `access_control_stats`, `tenant_counts.in_observe_mode` reserved for v0.1.26 extensions), and accept-and-ignore query params on `listTenants` (`observe_mode`) and `listPolicies` (`has_action_quotas`, `references_action_kind`). All additions are backward compatible — `@JsonInclude(NON_NULL)` keeps responses unchanged when new fields are null.

v0.1.25.9 is a patch release with operational hardening only — **no API surface changes**, spec stays at v0.1.25.8. Adds: Micrometer/Prometheus metrics at `/actuator/prometheus` with custom counters (`cycles_admin_events_emitted_total`, `cycles_admin_webhook_dispatched_total`); Kubernetes liveness/readiness probes at `/actuator/health/liveness` and `/actuator/health/readiness` (docker-compose healthchecks switched to liveness); CORS fixes — `X-Cycles-API-Key` and `X-Request-Id` are now allowlisted (both were previously blocked at preflight, breaking browser dashboards using tenant auth) and `X-Request-Id` is exposed for correlation; multi-origin CORS support via comma-separated `DASHBOARD_CORS_ORIGIN` env var.

v0.1.25.10 is a patch release hardening spec compliance for `cycles-governance-admin-v0.1.25` (no API surface changes). (1) **`Permission` enum now modeled** — the 27 spec permission values are a typed Java enum with Jackson `@JsonValue`/`@JsonCreator`; inbound `POST /v1/admin/api-keys` and `PATCH /v1/admin/api-keys/{key_id}` now reject unknown permission strings (e.g. typos like `"budgets:wirte"`) at deserialization with a 400 instead of silently storing them. Wire format unchanged. (2) **`AuthIntrospectResponse.capabilities` structurally typed** — replaced the `Map<String, Boolean>` with a dedicated `Capabilities` class exposing the eight required booleans (`view_overview`, `view_budgets`, `view_events`, `view_webhooks`, `view_audit`, `view_tenants`, `view_api_keys`, `view_policies`) per spec's "all fields always present" contract. JSON shape identical.

v0.1.25.11 turns on **fail-hard contract testing by default** — no API surface changes, build-time only. Every 2xx response from the 13 admin controllers is now validated against the authoritative `cycles-governance-admin-v0.1.25.yaml` spec fetched from `cycles-protocol@main` on each build. Any future drift between server and spec — missing required fields, extra fields violating `additionalProperties: false`, type/enum/min-max mismatches — fails the build. Depends on v0.1.25.10 (server-side `Permission`/`Capabilities` fixes), cycles-protocol spec v0.1.25.10 (spec-side `SignedAmount`, `BalanceListResponse`, strict PATCH bodies), and test fixture rename (tenant_id `minLength:3`). All three landed; the flip is a one-line default change. Disable for offline dev with `-Dcontract.validation.enabled=false` or `CONTRACT_VALIDATION_ENABLED=false`. See [docs/contract-testing.md](docs/contract-testing.md) for the runbook.

v0.1.25.12 is a spec-compliance + observability release. **One wire change:** outbound webhook event payloads with enum fields now emit spec-compliant values — `EventDataBudgetLifecycle.operation` was emitting lowercase `"create"` / `"update"` where the spec requires UPPERCASE `CREATE` / `UPDATE` / `STATUS_CHANGE`; the server now emits correct values. Consumers that were case-sensitively matching lowercase need to update (the lowercase values were never spec-documented). **New Prometheus metric:** `cycles_admin_events_payload_invalid_total{type, expected_class}` at `/actuator/prometheus` — counts every event emit whose `data` payload doesn't round-trip through its spec-assigned `EventData*` class. Nonzero = producer regression; alert-able. **Build-time only (no runtime impact):** full structural diff of SpringDoc `/v3/api-docs` against the pinned spec on every CI run (catches missing/extra endpoints); 4xx/5xx error responses now validated against `ErrorResponse` schema; every `EventData*` payload typed with `@JsonIgnoreProperties(ignoreUnknown=false)` so malformed payloads fail fast at serialization; spec-coverage assertion fails the build on any spec endpoint with zero tests; `BudgetOperation` / `ThresholdDirection` / `RateSpikeMetric` enums replace raw `String` fields on EventData classes; runtime `EventService.emit` logs a WARN when payload shape doesn't match spec expectation. Aligns with `cycles-protocol@main` which gained 400-response documentation on all 43 operations (cycles-protocol v0.1.25.11). Total: 13 real drifts found and fixed across spec + server + fixtures during the compliance push — see AUDIT.md.

v0.1.25.13 fixes a CORS preflight regression on `PUT /v1/admin/config/webhook-security` ([dashboard #30](https://github.com/runcycles/cycles-dashboard/issues/30)). `WebConfig.addCorsMappings` lacked `PUT` in `allowedMethods`, so any cross-origin dashboard (e.g. Vite dev server hitting admin directly) saw `403 Forbidden` from Spring's CorsFilter before `AuthInterceptor` ran — no application logs, silent rejection. This is the only spec-defined `PUT` endpoint in the admin API; every other mutation uses POST/PATCH/DELETE. Added regression test locking in the expected methods list. Same-origin deployments (standard nginx-proxied prod stack) were unaffected. No spec change.

v0.1.25.14 implements **Stage 1 of admin-on-behalf-of dual-auth** (spec side: [cycles-protocol#36](https://github.com/runcycles/cycles-protocol/pull/36), v0.1.25.13). Closes a long-standing dashboard gap: admin operators previously could not create budgets or policies because those endpoints accepted only `ApiKeyAuth` (X-Cycles-API-Key) and the dashboard authenticates exclusively with `X-Admin-API-Key`. **Dual-auth added** to `POST /v1/admin/budgets` (createBudget), `POST /v1/admin/policies` (createPolicy), and `PATCH /v1/admin/policies/{policy_id}` (updatePolicy). `BudgetCreateRequest` and `PolicyCreateRequest` gain an optional `tenant_id` field; admin callers MUST send it, tenant callers MUST NOT (strict bidirectional validation — prevents tenants spoofing creates). Audit records tag `actor_type=admin_on_behalf_of` (new `ActorType` enum value with `@JsonValue = "admin_on_behalf_of"`) for admin-driven calls vs `api_key` for tenant self-service. Also includes defense-in-depth path-traversal guard in `AuthInterceptor.preHandle` (short-circuits `/../`, `/./`, trailing `/..` with 400) and switches the dual-auth path matcher to `getServletPath()` for normalization-consistency with Spring dispatcher.

v0.1.25.15 adds **canonical scope validation**. User reported creating a budget with `scope=tenant:acme/agentic:codex` succeeded ("agentic" is a typo for the canonical kind `agent`); probing revealed the server was doing essentially no scope validation. Per `cycles-protocol-v0.yaml` NORMATIVE ordering (`tenant → workspace → app → workflow → agent → toolset`), non-canonical scopes silently break downstream enforcement (reservations can't match them) and pollute audit trails. **New `ScopeValidator`** enforces: first segment must be `tenant:<id>`; only canonical kinds accepted; kinds appear in canonical order with no duplicates; each id non-empty, ≤128 chars, matches `[A-Za-z0-9._-]+`; and scope's tenant must match request `tenant_id`. Called from `BudgetController.create` and `PolicyController.create`/`update`. Policy `scope_pattern` allows terminal-wildcard `*` / id-wildcard `agent:*` per spec; budget scopes stay concrete. Rejections return 400 `INVALID_REQUEST` with a specific message identifying which rule failed on which segment. Spec compliance unchanged — this tightens enforcement of what the spec already described as canonical.

v0.1.25.16 is **Stage 3 (final) of admin-on-behalf-of dual-auth**, for tenant-scoped webhook ops (spec: [cycles-protocol#40](https://github.com/runcycles/cycles-protocol/pull/40), v0.1.25.14). Ops use case: a tenant's webhook endpoint flapping in production, ops gets paged, needs to pause / force-delete / inspect without waiting on the tenant to rotate keys. **Dual-auth added** to 6 endpoints under `WebhookTenantController`: `GET /v1/webhooks` (new REQUIRED `tenant` query param under admin; MUST NOT be set under ApiKey), `GET /v1/webhooks/{id}`, `PATCH /v1/webhooks/{id}`, `DELETE /v1/webhooks/{id}`, `POST /v1/webhooks/{id}/test`, `GET /v1/webhooks/{id}/deliveries`. **Intentionally NOT dual-auth:** `POST /v1/webhooks` (create) — URL, signing secret, and event-type choices are tenant policy; and `POST /v1/webhooks/{id}/replay` which is already admin-only at the `/v1/admin/webhooks/{id}/replay` path. Audit entries for admin-driven mutate/test tag `actor_type=admin_on_behalf_of`; audit subject is the subscription's owning tenant (not the admin caller). `ADMIN_ALLOWED_PREFIXES` gains 4 entries; `GET:/v1/webhooks` added to `ADMIN_ALLOWED_ENDPOINTS` exact allowlist.

## Documentation

- [Cycles Documentation](https://runcycles.io) — full docs site
- [Deploy the Full Stack](https://runcycles.io/quickstart/deploying-the-full-cycles-stack) — deployment guide with admin server setup
- [Tenant Management](https://runcycles.io/how-to/tenant-creation-and-management-in-cycles) — tenant creation and lifecycle
- [Budget Allocation and Management](https://runcycles.io/how-to/budget-allocation-and-management-in-cycles) — budget configuration
- [API Key Management](https://runcycles.io/how-to/api-key-management-in-cycles) — API key lifecycle

## License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
