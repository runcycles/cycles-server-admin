# Cycles Server Admin

Administrative API for the Complete Budget Governance System, aligned with [Cycles Protocol v0.1.23](complete-budget-governance-v0.1.23.yaml).

## Overview

This service implements a budget governance system built on three integrated pillars:

| Pillar | Plane | Purpose |
|--------|-------|---------|
| **Tenant & Budget Management** | Configuration | Tenant lifecycle, budget ledgers, policy configuration |
| **Authentication & Authorization** | Identity | API key validation, permission enforcement, audit logging |
| **Runtime Enforcement** | Reservation | Budget reservations, commits, balance queries (Cycles Protocol v0.1.23) |

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

## Prerequisites

- Java 21+
- Maven 3.9+
- Redis 7+ (or Docker for TestContainers)

## Getting Started

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

The server starts at `http://localhost:8080`. Swagger UI is available at `/swagger-ui.html`.

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

API keys use the format `cyc_live_{random}` (production) or `cyc_test_{random}` (test). Keys are stored as bcrypt hashes; the full secret is only returned once at creation time.

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
| `POST` | `/v1/admin/budgets/{scope}/{unit}/fund` | Fund/adjust budget | ApiKey |
| `POST` | `/v1/admin/policies` | Create policy | ApiKey |
| `GET` | `/v1/admin/policies` | List policies | ApiKey |

### Pillar 2: Authentication & Authorization

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/admin/api-keys` | Create API key | Admin |
| `GET` | `/v1/admin/api-keys` | List API keys | Admin |
| `DELETE` | `/v1/admin/api-keys/{key_id}` | Revoke API key | Admin |
| `POST` | `/v1/auth/validate` | Validate key & resolve tenant | Admin |
| `GET` | `/v1/admin/audit/logs` | Query audit logs | Admin |

### Pillar 3: Runtime Enforcement (Cycles Protocol v0.1.23)

| Method | Path | Operation | Auth |
|--------|------|-----------|------|
| `POST` | `/v1/reservations` | Create budget reservation | ApiKey |
| `POST` | `/v1/reservations/{reservation_id}/commit` | Commit actual spend | ApiKey |
| `GET` | `/v1/balances` | Query budget balances | ApiKey |

## Core Concepts

### Tenants

Tenants are the top-level isolation boundary. All budgets, keys, and reservations are scoped to a tenant.

- **ID format:** kebab-case (`acme-corp`, `demo-tenant`)
- **Status lifecycle:** `ACTIVE` → `SUSPENDED` ↔ `ACTIVE`, or `* → CLOSED` (irreversible)
- Supports hierarchical tenants via `parent_tenant_id`

### Budget Ledgers

A budget ledger tracks finances for a specific `(scope, unit)` pair. Each ledger maintains:

| Field | Description |
|-------|-------------|
| `allocated` | Total budget cap |
| `remaining` | Available for new reservations (can go negative with overdraft) |
| `reserved` | Locked by active reservations |
| `spent` | Successfully committed |
| `debt` | Overdraft amount |

**Supported units:** `USD_MICROCENTS`, `TOKENS`, `CREDITS`, `RISK_POINTS`

**Scopes** use hierarchical paths: `tenant:acme-corp/workspace:eng/agent:summarizer`

### Funding Operations

Use `POST /v1/admin/budgets/{scope}/{unit}/fund` with one of:

| Operation | Effect |
|-----------|--------|
| `CREDIT` | Add funds (`allocated += amount`, `remaining += amount`) |
| `DEBIT` | Remove funds (fails if remaining would go negative) |
| `RESET` | Set allocation to exact amount, adjust remaining |
| `REPAY_DEBT` | Reduce outstanding debt |

All funding operations support idempotency keys.

### Commit Overage Policies

Controls behavior when actual spend exceeds the reserved amount:

| Policy | Behavior |
|--------|----------|
| `REJECT` | Fail if actual > reserved |
| `ALLOW_IF_AVAILABLE` | Charge delta from remaining budget |
| `ALLOW_WITH_OVERDRAFT` | Create debt up to `overdraft_limit` |

Policy resolution order (highest priority wins): **Reservation** > **Policy** > **Budget Ledger** > **Tenant default**

### Policies

Policies define caps, rate limits, and behavioral rules matched by scope patterns:

- `tenant:acme-corp` — exact match
- `tenant:acme-corp/*` — all descendants
- `agent:*` — all agents system-wide

Policies support priority ordering, effective date windows, and rate limits (`max_reservations_per_minute`, `max_commits_per_minute`).

### Caps (Soft-Landing Constraints)

From Cycles Protocol v0.1.23:

```json
{
  "max_tokens": 4096,
  "max_steps_remaining": 10,
  "tool_allowlist": ["read_file", "search"],
  "tool_denylist": ["execute_command"],
  "cooldown_ms": 5000
}
```

## Error Handling

All errors return a standard `ErrorResponse`:

```json
{
  "error": "BUDGET_EXCEEDED",
  "message": "Remaining budget insufficient for reservation",
  "request_id": "req_abc123"
}
```

Error codes include Cycles v0.1.23 codes (`BUDGET_EXCEEDED`, `RESERVATION_EXPIRED`, `OVERDRAFT_LIMIT_EXCEEDED`, etc.) and governance-specific codes (`TENANT_SUSPENDED`, `POLICY_VIOLATION`, `KEY_REVOKED`, etc.).

## Deployment Models

| Model | Description |
|-------|-------------|
| **Single-service** | All three pillars in one deployment |
| **Split-plane** | Separate admin/auth services from runtime enforcement |
| **Federated** | Multiple runtime enforcement instances, shared admin plane |

## Protocol Specification

The full OpenAPI 3.1.0 specification is in [`complete-budget-governance-v0.1.23.yaml`](complete-budget-governance-v0.1.23.yaml).

## License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
