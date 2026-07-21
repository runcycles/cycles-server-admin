[![CI](https://github.com/runcycles/cycles-server-admin/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-server-admin/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-95%25+-brightgreen)](https://github.com/runcycles/cycles-server-admin/actions)

# Cycles Admin Server — Multi-tenant management for AI agent governance

**Administrative API for managing tenants, budgets, API keys, and policies in a Cycles deployment.** Configures the AI agent budget and action enforcement that the [Cycles Server](https://github.com/runcycles/cycles-server) applies at runtime.

Multi-tenant by default, with four integrated planes: tenant lifecycle and budget ledgers, API key authentication and permission enforcement, runtime reservation control, and event/webhook delivery for observability. Aligned with [Cycles Protocol v0.1.25.42](https://github.com/runcycles/cycles-protocol/blob/402307a88906e9fd090159e5ccf2d0036e6aec83/cycles-governance-admin-v0.1.25.yaml) (v0.1.25.35–.37 added the four cascade EventTypes + `EventDataTenantCascade` payload schema, the `admin_on_behalf_of` actor type, and `TENANT_CLOSED` in the reservation-denied reason-code documentation; v0.1.25.38 added the tenant self-service webhook `event_categories` boundary; v0.1.25.39 relaxed the webhook schema to permit **category-only** subscriptions, ratifying the behavior this server already shipped in 0.1.25.50; v0.1.25.40 added the **admin-plane webhook category boundary** (INVARIANT 2 — a tenant-owned subscription may not carry admin-only event types/categories); v0.1.25.41 added **replay all-or-narrow semantics** plus the **`/test` synthetic-ping exception**; and v0.1.25.42 aligns policy write requests with the existing non-negative priority invariant — all implemented or documentation-only here). Empty-both subscriptions remain rejected (create requires `event_types`; update requires at least one of `event_types`/`event_categories`).

## Documentation

- **[`CHANGELOG.md`](CHANGELOG.md)** — consumer-facing release notes for downstream consumers pulling the Docker image / JAR.
- **[`OPERATIONS.md`](OPERATIONS.md)** — operator-facing runbook: metrics inventory, alerting recipes, configuration tuning, incident playbook.
- **[`AUDIT.md`](AUDIT.md)** — internal engineering history (root cause analyses, rejected alternatives, test-strategy decisions).

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
- **Framework:** Spring Boot 3.5.16
- **Data Store:** Redis (via Jedis 7.5.2)
- **API Docs:** SpringDoc OpenAPI (disabled by default; enable behind `X-Admin-API-Key` protection)
- **Testing:** JUnit 5 + TestContainers (Redis)

## Quick Start with Docker

The fastest way to run the admin server — no Java or Maven required:

```bash
# Using pre-built image from GHCR
export REDIS_PASSWORD=$(openssl rand -base64 32)
export ADMIN_API_KEY=$(openssl rand -base64 32)
export WEBHOOK_SECRET_ENCRYPTION_KEY=$(openssl rand -base64 32)
docker compose -f docker-compose.prod.yml up -d
```

The server starts at `http://localhost:7979`. Generated API docs and Swagger UI
are disabled by default in production; set `API_DOCS_ENABLED=true` and
`SWAGGER_ENABLED=true` only behind trusted access. When enabled, docs endpoints
require `X-Admin-API-Key`.

To run the full stack (Admin + Runtime + Events + Redis):

```bash
# Generate encryption key for webhook signing secrets (shared across all services)
export REDIS_PASSWORD=$(openssl rand -base64 32)
export ADMIN_API_KEY=$(openssl rand -base64 32)
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
| Events (`cycles-server-events`) | 9980 in production full-stack | Management/readiness surface for async webhook delivery; worker port `7980` remains internal |

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

The server starts at `http://localhost:7979`. Swagger UI is disabled by default;
enable it with `SWAGGER_ENABLED=true` when needed.

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
| `REDIS_PASSWORD` | Yes in prod | — | Redis password. Production Compose requires it and starts Redis with `requirepass`; local Compose may run without auth. |
| `ADMIN_API_KEY` | Yes | — | Master admin API key for `X-Admin-API-Key` header |
| `WEBHOOK_SECRET_ENCRYPTION_KEY` | Yes by default | (empty; startup fails) | AES-256-GCM encryption key for webhook signing secrets at rest. Base64-encoded 32 bytes and shared with `cycles-server-events`. |
| `WEBHOOK_SECRET_ALLOW_PLAINTEXT` | No | `false` | Explicit local/development compatibility opt-out. When `true` with an empty key, startup emits a prominent warning and signing secrets are stored unencrypted. Never enable in production. |
| `LOG_LEVEL` | No | `INFO` | Application logging level (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `JAVA_OPTS` | No | (empty) | JVM options consumed by the Docker image entrypoint. Production Compose sets conservative G1/heap-percentage defaults. |
| `API_DOCS_ENABLED` | No | `false` | Enable generated OpenAPI JSON at `/api-docs`; protected by `X-Admin-API-Key` when enabled. |
| `SWAGGER_ENABLED` | No | `false` | Enable Swagger UI at `/swagger-ui.html`; protected by `X-Admin-API-Key` when enabled. |
| `EVENT_TTL_DAYS` | No | `90` | Event retention in Redis (days) |
| `DELIVERY_TTL_DAYS` | No | `14` | Webhook delivery retention in Redis (days) |
| `AUTH_FAILURE_RATE_LIMIT_ENABLED` | No | `false` | Enable per-source, per-process throttling for repeated 401/403 responses. Production Compose enables it. |
| `AUTH_FAILURE_RATE_LIMIT_MAX_PER_MINUTE` | No | `300` | Per-minute failed-auth threshold before responses become `429 LIMIT_EXCEEDED` without writing extra audit rows. The limiter is in-process and does not coordinate across replicas. |
| `AUTH_FAILURE_RATE_LIMIT_MAX_TRACKED_SOURCES` | No | `10000` | Maximum in-memory source/path buckets retained by the per-process failed-auth limiter. Stale buckets are removed first; the oldest bucket is evicted at the cap. |
| `TENANT_CLOSE_RECONCILER_ENABLED` | No | `true` | Retry incomplete Mode-B child cascades for tenants already marked `CLOSED`. |
| `TENANT_CLOSE_RECONCILER_INTERVAL_MS` | No | `300000` | Delay in milliseconds between tenant-close reconciliation runs. |
| `TENANT_CLOSE_RECONCILER_MAX_TENANTS_PER_RUN` | No | `100` | Maximum due tenant-close work items retried per run; Redis persists progress across restarts and replicas. |
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
4. A missing key fails startup by default in both services. Local/development
   deployments may explicitly set `WEBHOOK_SECRET_ALLOW_PLAINTEXT=true`; this
   stores new secrets unencrypted and emits a prominent startup warning.

After a key is added, existing non-`enc:` secrets remain readable and all new
or rotated writes use the encrypted `enc:` format. This provides a gradual
plaintext-to-encrypted migration without accepting ciphertext as a signing
secret when the decrypt key is unavailable.

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

Tenants can subscribe to `budget.*`, `reservation.*`, `tenant.*` (27 of 41 event types). Admin-only: `api_key.*`, `policy.*`, `system.*`.

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
| `GET` | `/v1/auth/introspect` | Auth introspection & capabilities | ApiKey / Admin |

The overview endpoint returns a single-request aggregated payload with tenant/budget/webhook counts, top-offender arrays (over-limit, debt, failing webhooks), and recent event summaries. The introspect endpoint returns effective capabilities for dashboard UI gating — dual-auth as of v0.1.25.19 (spec v0.1.25.15): admin keys return the admin-shape (`auth_type=admin`, all 15 capabilities true); tenant API keys return the tenant-shape (`auth_type=tenant`, `tenant_id`, concrete permissions, derived per-capability flags, admin-plane caps forced to false).

**41 event types** across 6 categories: budget (16), reservation (5), tenant (6), api_key (6), policy (3), system (5).

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
| **Full stack** | Admin (7979) + Runtime (7878) + Events management/readiness (9980 in production) + Redis |

## Observability

**Health endpoints** (Spring Boot Actuator):

| Endpoint | Use |
|----------|-----|
| `GET /actuator/health` | Aggregate health (requires `X-Admin-API-Key`; use for debugging) |
| `GET /actuator/health/liveness` | K8s liveness probe — open, JVM process state only |
| `GET /actuator/health/readiness` | K8s readiness probe — open, includes Redis connectivity |
| `GET /actuator/info` | Build info (requires `X-Admin-API-Key`) |
| `GET /actuator/prometheus` | Prometheus scrape endpoint (requires `X-Admin-API-Key`) |

Production Docker healthchecks hit `/actuator/health/readiness` so Redis outages mark the container unhealthy without exposing protected actuator data.

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
| Methods | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` |
| Request headers | `X-Admin-API-Key`, `X-Cycles-API-Key`, `X-Request-Id`, `X-Cycles-Trace-Id`, `traceparent`, `tracestate`, `Content-Type` |
| Exposed response headers | `X-Request-Id`, `X-Cycles-Trace-Id` (for correlation) |
| Origins | `DASHBOARD_CORS_ORIGIN` env var (comma-separated), default `http://localhost:5173` |

Multiple origins are supported for staging + prod deployments behind the same server: `DASHBOARD_CORS_ORIGIN=https://dash.example.com,https://staging.example.com`.

## Protocol Specification

The full OpenAPI 3.1.0 specification is [`cycles-governance-admin-v0.1.25.yaml`](https://github.com/runcycles/cycles-protocol/blob/main/cycles-governance-admin-v0.1.25.yaml) in the [cycles-protocol](https://github.com/runcycles/cycles-protocol) repository.

v0.1.25 adds Pillar 4 (Events & Webhooks): 41 event types, 20 webhook endpoints, HMAC-SHA256 signing, at-least-once delivery, and webhook secret encryption at rest.

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

v0.1.25.25 adds **free-text `search` on the six admin list endpoints** (`listTenants`, `listBudgets`, `listApiKeys`, `listAuditLogs`, `listWebhookSubscriptions`, `listEvents`) — closes the first bullet of governance spec v0.1.25.21. Optional `search` query param, case-insensitive substring, `maxLength: 128`, AND-combined with every other filter. Per-endpoint match fields (OR-combined within an endpoint): `listTenants`(`tenant_id`,`name`), `listBudgets`(`tenant_id`,`scope`), `listApiKeys`(`key_id`,`name`), `listAuditLogs`(`resource_id`,`log_id`), `listWebhookSubscriptions`(`subscription_id`,`url`), `listEvents`(`correlation_id`,`scope`). New shared `SearchSpec` validator locks the `trim → empty-check → length-check` ordering so trailing whitespace cannot bypass the cap. Cursor-stability invariant (search predicate applies **before** cursor commitment) asserted by explicit page1→page2 cursor-walk tests in `EventRepositoryTest` and `AuditRepositoryTest`.

v0.1.25.26 adds **filter-driven bulk lifecycle actions** — `POST /v1/admin/tenants/bulk-action` (`SUSPEND`/`REACTIVATE`/`CLOSE`) and `POST /v1/admin/webhooks/bulk-action` (`PAUSE`/`RESUME`/`DELETE`), closing the remaining bullet of governance spec v0.1.25.21. Request: `{filter, action, expected_count?, idempotency_key}`. Response: `{action, total_matched, succeeded[], failed[], skipped[], idempotency_key}`. **Four safety gates:** empty filter → 400 `INVALID_REQUEST`; idempotency replay returns cached envelope (15-min TTL); >500 matches → 400 `LIMIT_EXCEEDED`; `expected_count` mismatch → 409 `COUNT_MISMATCH` (preview→submit anti-footgun). New shared `IdempotencyStore` primitive for bulk-action only (Lua-atomic `BudgetController.fund` idempotency intentionally NOT migrated — externalizing would split atomicity with the balance mutation). Paired with cycles-protocol spec v0.1.25.23 which added `COUNT_MISMATCH` and `LIMIT_EXCEEDED` to the `ErrorCode` enum so response validators don't reject the spec-compliant server.

v0.1.25.27 lands an **audit log filter DSL upgrade on `listAuditLogs`** (spec v0.1.25.24). Four new query params — `error_code` (IN-list), `error_code_exclude` (NOT-IN-list), `status_min`, `status_max` (inclusive range, mutex with exact `status`) — and promotes `operation` / `resource_type` from scalar to `array<string>` (`explode=false`). IN-list semantics: case-sensitive, maxItems 25, unknown codes match nothing (forward-compat). NULL-semantic asymmetry is explicit: NULL `entry.error_code` never matches `error_code` (auditor asking "show me code X" never wants success rows) but always passes `error_code_exclude` (hiding noisy codes MUST NOT silently hide successes). `search` match set extended from `{resource_id, log_id}` to `{resource_id, log_id, error_code, operation}` — closes the free-text gap where `?search=BUDGET` missed `BUDGET_EXCEEDED`. All changes are wire-additive; a single scalar `?operation=createBudget` still parses as a one-element list. Cursor-stability invariant (v0.1.25.25) extended to every new predicate.

v0.1.25.28 splits the **audit `tenant_id` sentinel** (spec v0.1.25.25) so admin-plane compliance signals stop aging out on the anonymous-401 schedule. The previous `"<unauthenticated>"` sentinel conflated two very different populations — pre-auth failures (DDoS-amplifiable noise) and platform-admin-authenticated requests (high-signal governance ops). Now: `__admin__` rides the authenticated-tier TTL (default 400d) and is never sampled; `__unauth__` rides the unauth-tier TTL (default 30d) and is still subject to `audit.sample.unauthenticated`. Both are URL-safe (tenant grammar excludes underscores, no percent-encoding needed) so ops dashboards can filter `?tenant_id=__admin__` without `%3C…%3E` ugliness. **Back-compat:** historical rows carrying the literal `<unauthenticated>` still route to the unauthenticated-tier TTL — no row silently flips to long retention. Dashboard tooling hard-coded to the old sentinel should migrate to `?tenant_id=__unauth__` (for the pre-auth-failure slice) and add `?tenant_id=__admin__` (for the new admin-plane slice).

v0.1.25.28.1 is a **test-only point release** fixing a coverage gap in the nightly audit-soak invariant `AS4`. After v0.1.25.28 split the pre-auth sentinel into `__unauth__` + `__admin__`, the soak test's per-tier equality sum still included only `__unauth__ + tenant-soak`. The admin-plane 4xx tier (`__admin__`) held exactly the shortfall count on the first post-release run (5077 rows = the 400-response count). The production audit write path was correct throughout — all 14000 writes landed in the global index and incremented the counter. One test file updated; no server / spec / data changes.

v0.1.25.29 adds a **budget bulk-action endpoint** — `POST /v1/admin/budgets/bulk-action` — closing [cycles-server-admin#99](https://github.com/runcycles/cycles-server-admin/issues/99) ("Bulk Budget Reset at Tenant or Parent-Scope Level"). AdminKeyAuth only. Five actions reusing `FundingOperation`: `CREDIT`, `DEBIT`, `RESET`, `REPAY_DEBT`, `RESET_SPENT` (all require `amount`; `spent` honored only on `RESET_SPENT`). Filter mirrors `listBudgets` one-for-one (`tenant_id` REQUIRED — cross-tenant bulk explicitly out of scope per spec; `scope_prefix`, `unit`, `status`, `over_limit`, `has_debt`, `utilization_min/max`, `search` optional) and uses the same `BudgetListFilters` matcher so preview-via-list and bulk-apply agree on the match set byte-for-byte. **Safety gates** identical to tenant / webhook bulk-action (v0.1.25.26): 500-row cap (`LIMIT_EXCEEDED` 400), `expected_count` gate (`COUNT_MISMATCH` 409), 15-min idempotency replay, HTTP 200 even with per-row failures. **Per-row classification:** `failed[]` with `BUDGET_EXCEEDED` (DEBIT overflow), `INVALID_TRANSITION` (unit mismatch / FROZEN / CLOSED), `NOT_FOUND` (deleted between match and apply), or `INTERNAL_ERROR`; `skipped[]` with `reason=ALREADY_IN_TARGET_STATE` (currently only `REPAY_DEBT` on `debt==0`). **Double-apply protection:** a per-row idempotency key `{bulkKey}:{scope}:{unit}` threaded into `BudgetRepository.fund` lets the existing Lua fund-idempotency cache short-circuit any row whose prior run actually landed, so retry-the-failed-set on a tighter filter cannot double-apply. Operator workflow: `listBudgets` (preview) → `bulk-action` → inspect `failed[]` → narrow filter → re-run with a **new** idempotency_key.

v0.1.25.30 is a **triage-enrichment release** for all three bulk-action endpoints (`bulkActionTenants`, `bulkActionWebhooks`, `bulkActionBudgets`) — no spec bump. Before v0.1.25.30 the single `AuditLogEntry` emitted per bulk-op carried only bucket counts + `idempotency_key` in its `metadata`; post-incident triage required the operator's own copy of the response envelope or re-running the op (not acceptable for destructive actions like DELETE / DEBIT). Now the audit entry alone is sufficient. **Five new `metadata` keys** (additive): `succeeded_ids` (per-row ids of successful operations), `failed_rows` (full `id + error_code + message` per failure — replaces the "re-run to see what broke" workflow), `skipped_rows` (full `id + reason` per skip — distinguishes `ALREADY_IN_TARGET_STATE` from `ALREADY_DELETED`), `filter` (normalized filter echoed as-is — reconstructs operator intent), `duration_ms` (handler-entry → audit-emit wall-clock for SLO triage). Consolidated into a single `BulkActionAuditMetadataBuilder` helper so future bulk-action endpoints cannot drift on key set or order. Worst-case metadata size ~40 KB (500-row cap × ~80 B per outcome), well within Redis value-size comfort range. No spec bump needed — `AuditLogEntry.metadata` is already typed `object` with `additionalProperties: true`. See [OPERATIONS.md §Audit coverage → Bulk-action triage](OPERATIONS.md#bulk-action-audit-triage) for the runbook.

v0.1.25.31 is the **server impl of cross-surface correlation** (spec v0.1.25.28, cycles-protocol PRs #56 + #58) — adds a third correlation tier on top of the existing `request_id` / `correlation_id`. A `trace_id` (W3C Trace Context, `^[0-9a-f]{32}$`) is captured from inbound `traceparent` OR `X-Cycles-Trace-Id` headers (or server-generated when absent), propagated onto every `ErrorResponse`, `AuditLogEntry`, `Event`, and outbound `WebhookDelivery` (v0.1.25.28 patched the `WebhookDelivery` schema to declare `trace_id` / `trace_flags` / `traceparent_inbound_valid` so this server's payloads conform cleanly), and echoed as the `X-Cycles-Trace-Id` response header on every response. Inbound precedence: valid `traceparent` → valid `X-Cycles-Trace-Id` → generate (16 random bytes → 32-hex, all-zero re-rolled per W3C §3.2.2.3). Malformed inbound correlation headers are tolerated (fall through to the next rule); the server never rejects a request on a bad correlation header. Valid inbound trace-flags are preserved for outbound webhook delivery so an upstream that opted out of sampling (`00`) is respected rather than silently re-enabled. New exact-match query params `trace_id` + `request_id` on `GET /v1/admin/audit/logs` and `GET /v1/admin/events` make dashboard JOINs one-query-per-list. Fully additive on the wire — historical rows without `trace_id` continue to round-trip through strict Jackson.

v0.1.25.32 is a **read-side deserialization tolerance adjustment** in admin only — no wire contract change. `Event` and `WebhookDelivery` now carry class-level `@JsonIgnoreProperties(ignoreUnknown = true)` because runtime (`cycles-server`) is the authoritative writer of `event:*` and `delivery:*` Redis records and admin only reads them. Before .32, if runtime shipped an additive field in a patch release, admin's `listEvents` / `listWebhookDeliveries` would throw `UnrecognizedPropertyException` until admin lockstep-updated the POJO — violating the "additive fields are safe" invariant the admin/runtime split is built on. Runtime can now ship additive fields in any patch without forcing an admin release. **Strict mode preserved** on admin-owned schemas (`WebhookSubscription`, `Tenant`, `Budget`, `Policy`, `ApiKey`, every `EventData*` subtype, every `Bulk*Request`/`Filter`, every `*CreateRequest`/`UpdateRequest`) — admin writes these, so a typo there is an admin-internal bug and must still fail loudly. Two new test cases pin the invariant so a future regression (someone re-adding `ignoreUnknown = false`) fails the build. Also removed a dead `@JsonIgnoreProperties(ignoreUnknown = false)` on `ErrorResponse` that was never reachable at runtime.

v0.1.25.33 is a **security patch**: Spring Boot 3.5.11 → 3.5.13 with a `<tomcat.version>10.1.54</tomcat.version>` property override, closing 4 HIGH/CRITICAL CVEs against `tomcat-embed-core 10.1.52` (CVE-2026-29145 CRITICAL, CVE-2026-29129 HIGH, CVE-2026-34483 HIGH, CVE-2026-34487 HIGH). SB 3.5.13 brings 10.1.53 transitively; the property override picks up the remaining two. Patch-level bump within the same 3.5.x line — no API surface change.

v0.1.25.34 is a **security follow-up** to the v0.1.25.33 Spring Boot bump: Trivy flagged one remaining HIGH finding (CVE-2025-48924) that SB 3.5.13's BOM didn't cover, resolved by adding `<commons-lang3.version>3.18.0</commons-lang3.version>` alongside the existing Tomcat override. Removable when SB ships a release with 3.18.0+ managed. No API surface change.

v0.1.25.35 is the **server impl of tenant-close cascade** (spec v0.1.25.29 Rule 1 + Rule 2). When a tenant transitions to `CLOSED`, owned objects transition per child to their terminal states: `BudgetLedger` → `CLOSED` (stamps `closed_at`, releases any outstanding `reserved` amount), `WebhookSubscription` → `DISABLED`, `ApiKey` → `REVOKED` (stamps `revoked_at`, reason `tenant_closed`). Any budget with `reserved > 0` at close time additionally emits an aggregate `reservation.released_via_tenant_cascade` event. All touched rows share the originating request's `request_id` + `trace_id` plus a dedicated `correlation_id = tenant_close_cascade:<tenant_id>:<request_id>` so operators can JOIN by any of the three. Cascade triggers from both `PATCH /admin/tenants/{id}` with `status=CLOSED` and the bulk-action `CLOSE` path; idempotent when the tenant is already closed. The companion **Rule 2 `TENANT_CLOSED` mutation guard** (409) short-circuits mutations on objects owned by closed tenants at the controller layer. Adds four cascade event kinds (`BUDGET_CLOSED_VIA_TENANT_CASCADE`, `RESERVATION_RELEASED_VIA_TENANT_CASCADE`, `WEBHOOK_DISABLED_VIA_TENANT_CASCADE`, `API_KEY_REVOKED_VIA_TENANT_CASCADE`) plus a new `WEBHOOK` event category. Cascade counts remain in audit metadata; lifecycle event payloads retain their strict contract-defined shape.

v0.1.25.36 closes the **Rule 2 mutation-guard coverage gap** flagged in v0.1.25.35's AUDIT entry. Any mutation on an object whose owning tenant is `CLOSED` now returns `409 TENANT_CLOSED` from every admin-mutating endpoint, per spec v0.1.25.29 MUST. New guard callsites: policy create/update; api-key create/update/delete; webhook create/update/delete/test/replay; tenant webhook delete/test; per-row guards in `POST /v1/admin/budgets/bulk-action` and `POST /v1/admin/webhooks/bulk-action` (closed-owner rows land in `failed[]` with `error_code: "TENANT_CLOSED"`; sibling rows still proceed). No wire contract change — `TENANT_CLOSED` already shipped in the shared `ErrorCode` enum in v0.1.25.35; .36 adds only callsites and the row-level classifier branch.

v0.1.25.37 is a **bounded-convergence fix** (spec v0.1.25.31 Rule 1(c)). Prior releases gated the cascade behind `isFreshClose` in the PATCH path and `ALREADY_IN_TARGET_STATE` in bulk-action, so a mid-cascade crash left the tenant CLOSED but any straggler children remained non-terminal, and re-issuing the close was silently a no-op. The documented recovery path in `OPERATIONS.md` (re-issue the close; cascade is idempotent and picks up stragglers) now matches the code. `PATCH /v1/admin/tenants/{id}` with `status=CLOSED` re-invokes the cascade on every request regardless of prior status; the parent event falls through to `tenant.updated` (not `tenant.closed`) on a retry so consumers don't see a duplicate close event. `POST /v1/admin/tenants/bulk-action` with `action=CLOSE` skips the redundant `repo.update` on already-CLOSED rows but still runs the cascade: if any children transition, the row is bucketed as `succeeded`; full no-op lands in `skipped` with `reason=ALREADY_IN_TARGET_STATE`. Cascade service semantics unchanged — already idempotent per-child (repository queries filter by non-terminal status); only the controller-level re-entry gates were removed. No wire contract change.

v0.1.25.39 adds **webhook lifecycle Events** (spec v0.1.25.33), closing the operator-observability blind spot v0.1.25.38 explicitly deferred. The single-op `createWebhookSubscription` / `updateWebhookSubscription` / `deleteWebhookSubscription` endpoints and the `POST /v1/admin/webhooks/bulk-action` (PAUSE/RESUME/DELETE) path now emit typed Events: `webhook.created`, `webhook.updated`, `webhook.paused`, `webhook.resumed`, `webhook.deleted` (plus `webhook.disabled` reserved for dispatcher auto-disable in `cycles-server-events`). New `EventDataWebhookLifecycle` payload schema carries `subscription_id`, `tenant_id`, `previous_status`, `new_status`, `changed_fields`, `disable_reason`. Update endpoint classifies the emit type by actual transition (`ACTIVE → PAUSED` → `webhook.paused`, `PAUSED → ACTIVE` → `webhook.resumed`, field-only PATCH → `webhook.updated`). Bulk correlation_id: `webhook_bulk_action:<action>:<request_id>` (one per invocation, shared across rows); single-op correlation_ids: `webhook_create:<id>`, `webhook_update:<id>:<request_id>`, `webhook_delete:<id>`. Skipped/failed rows never emit. Additive enum + schema — no wire break.

v0.1.25.38 adds **per-row Events on bulk-action endpoints** (spec v0.1.25.32). Before this release, `POST /v1/admin/budgets/bulk-action` and `POST /v1/admin/tenants/bulk-action` wrote only a single aggregate `AuditLogEntry` per invocation, while the matching single-op endpoints emitted a per-op lifecycle Event — operators watching `listEvents` had a blind spot whenever the same logical operation went through the bulk path. `bulkActionBudgets` now emits one Event per successfully-mutated row, typed by action (`budget.funded` / `budget.debited` / `budget.reset` / `budget.reset_spent` / `budget.debt_repaid`) with `correlation_id = budget_bulk_action:<action>:<request_id>`. `bulkActionTenants` emits one parent Event per successfully-mutated row (`tenant.suspended` / `tenant.reactivated` / `tenant.closed`) with `correlation_id = tenant_bulk_action:<action>:<request_id>`; for action=CLOSE this is in addition to the existing `tenant_close_cascade:<tenant_id>:<request_id>` cascade fan-out events (complementary correlation_ids — operators tracing the bulk invocation query the bulk id; operators tracing a specific tenant's close query the cascade id). Skipped rows (`ALREADY_IN_TARGET_STATE`) and failed rows emit no Event — matching single-op discipline and avoiding false signal. No wire / OpenAPI / DTO contract change; `EventType` enum unchanged (existing kinds reused). Aggregate `AuditLogEntry` per invocation unchanged; close-cascade semantics unchanged.

## Documentation

- [Cycles Documentation](https://runcycles.io) — full docs site
- [Deploy the Full Stack](https://runcycles.io/quickstart/deploying-the-full-cycles-stack) — deployment guide with admin server setup
- [Tenant Management](https://runcycles.io/how-to/tenant-creation-and-management-in-cycles) — tenant creation and lifecycle
- [Budget Allocation and Management](https://runcycles.io/how-to/budget-allocation-and-management-in-cycles) — budget configuration
- [API Key Management](https://runcycles.io/how-to/api-key-management-in-cycles) — API key lifecycle

## License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
