# Operations guide

Operator-facing runbook for running `cycles-server-admin` in production.
Covers metrics, alerting recipes, configuration, and an incident playbook.

Assumes you are already deploying via the published Docker image
(`ghcr.io/runcycles/cycles-server-admin:<version>`) with Prometheus
scraping `/actuator/prometheus` with `X-Admin-API-Key`. If you haven't
set that up yet, see the Monitoring section of [`README.md`](README.md)
first.

`cycles-server-admin` is the **governance / admin plane** for the Cycles
Protocol on port `7979` — it owns tenant, budget, policy, API-key, webhook,
event, and audit CRUD. The **runtime plane** (reservations, commits, the
sub-10ms decision path) lives in the sibling `cycles-server` repo on port
`7878` with its own [OPERATIONS.md](https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md).
Alerts for the two planes should be routed separately.

## Table of contents

1. [Audit coverage](#audit-coverage)
   - [Cross-surface correlation (trace_id)](#cross-surface-correlation-trace_id)
   - [Bulk-action audit triage](#bulk-action-audit-triage)
2. [Metrics inventory](#metrics-inventory)
3. [Alerts worth paging on](#alerts-worth-paging-on)
4. [Configuration tuning](#configuration-tuning)
5. [Incident playbook](#incident-playbook)
6. [Nightly CI coverage](#nightly-ci-coverage-v01252121)
7. [Pre-release drift checklist](#pre-release-drift-checklist)
8. [Related docs](#related-docs)

---

## Audit coverage

`GET /v1/admin/audit/logs` returns entries for every authenticated write
operation **and every failed request** (since v0.1.25.20). The write side
is split across two distinct code paths.

**Success-path entries** — written by each controller after the
repository / service call returns successfully. Rich, operation-specific
metadata:

| Field | Typical value on success |
|---|---|
| `operation` | Rich per-op name, e.g. `createBudget`, `fundBudget`, `updateTenant` |
| `status` | HTTP status of the successful response — 201, 200, 204 |
| `resource_type`, `resource_id` | Populated for mutations — `budget` / `scope:unit`, `tenant` / `tenant_id`, `policy` / `policy_id` |
| `subject`, `action`, `amount` | Populated on funding operations, policy changes, etc. |
| `metadata` | Operation-specific extras — fund reason, before/after spent (RESET_SPENT), policy caps diff, actor_type=admin_on_behalf_of on dual-auth writes |

**Failure-path entries** (v0.1.25.20+) — written by
`GlobalExceptionHandler` (4xx/5xx inside the controller) and
`AuthInterceptor` (401/403/400/500 rejections before the controller
runs). Coarse but always-present:

| Field | Typical value on failure |
|---|---|
| `operation` | `METHOD:URI` form — e.g. `POST:/v1/admin/budgets`, `GET:/v1/auth/introspect` |
| `status` | Actual HTTP status of the error response — 401, 403, 400, 404, 409, 500 |
| `error_code` | `ErrorCode.name()` — `UNAUTHORIZED`, `INVALID_REQUEST`, `TENANT_NOT_FOUND`, `KEY_REVOKED`, `INSUFFICIENT_PERMISSIONS`, `INTERNAL_ERROR`, … |
| `tenant_id` | Real tenant id on tenant-key auth; sentinel `"__admin__"` on admin-key auth (platform-plane ops); sentinel `"__unauth__"` on pre-auth failures (missing/invalid/revoked key, path traversal). Historical rows from v0.1.25.20..v0.1.25.27 may still carry the legacy literal `"<unauthenticated>"` — data layer routes it to the unauthenticated-tier TTL. |
| `metadata.error_message` | Sanitized server-side message (CR/LF stripped, 1024-char capped) |
| `metadata.method`, `metadata.path` | Request method and URI |
| `metadata.exception_class` | Only on `500 INTERNAL_ERROR` — fully-qualified Java class name for post-incident triage |
| `resource_type`, `resource_id`, `subject`, `action`, `amount` | Absent — not derivable on failure paths |

**Single-write invariant.** One request produces one audit entry: either
a success entry (controller reached the audit-log line) or a failure
entry (`GlobalExceptionHandler` or `AuthInterceptor.writeError` handled
the request). Never both. Never zero either — failed writes themselves
are captured in the `cycles_admin_audit_writes_total{outcome}` counter.

**Dashboards that pre-date v0.1.25.20** may assume "audit entry exists ⇒
operation succeeded". That's no longer true. Check `status` (2xx vs
4xx/5xx) or `error_code` (null on success, populated on failure) to
distinguish.

**Query examples:**

```bash
# Pre-auth failures (v0.1.25.28+: missing/invalid/revoked key).
GET /v1/admin/audit/logs?tenant_id=__unauth__

# Admin-plane activity (v0.1.25.28+: admin-key-authenticated requests,
# governance ops, cross-tenant reads, admin-plane 4xx/5xx).
GET /v1/admin/audit/logs?tenant_id=__admin__

# All 401 rejections in the last hour.
GET /v1/admin/audit/logs?status=401&from=<iso8601>

# All budget-plane failures by a specific tenant.
GET /v1/admin/audit/logs?tenant_id=tenant-1&operation=POST:/v1/admin/budgets

# Single-request JOIN (v0.1.25.31+): audit entry + every event the same
# request emitted, by request_id.
GET /v1/admin/audit/logs?request_id=req_abc123
GET /v1/admin/events?request_id=req_abc123

# Cross-surface JOIN (v0.1.25.31+): audit + events + webhook deliveries
# for one logical operation, by W3C trace_id. Useful for bulk-action
# fan-outs (one trace_id → one audit entry + N events + M deliveries)
# and for multi-request operator sessions that share a client-supplied
# traceparent.
GET /v1/admin/audit/logs?trace_id=0af7651916cd43dd8448eb211c80319c
GET /v1/admin/events?trace_id=0af7651916cd43dd8448eb211c80319c
```

### Cross-surface correlation (trace_id)

Since v0.1.25.31 every response on every admin endpoint carries an
`X-Cycles-Trace-Id` header. Operators and clients can reuse that value
as a server-side filter on both `listAuditLogs` and `listEvents` to join
everything caused by one logical operation.

**Capture the trace id from any response:**

```bash
curl -sD - -H "X-Admin-API-Key: $KEY" http://admin.cycles.internal/v1/admin/tenants \
  | grep -i '^x-cycles-trace-id:'
# x-cycles-trace-id: 0af7651916cd43dd8448eb211c80319c
```

**Inbound precedence the server honors** (first match wins):

1. `traceparent` header (W3C Trace Context §3.2, version `00`, non-all-zero trace-id + span-id) — OpenTelemetry-native clients get their distributed trace preserved.
2. `X-Cycles-Trace-Id` header (32 lowercase hex) — simple correlation without an OTel SDK.
3. Server-generated (16 random bytes → 32 lowercase hex; all-zero re-rolled per W3C §3.2.2.3).

A malformed inbound header is treated as absent (falls through to the next rule); the server never rejects a request on a bad correlation header. Valid inbound trace-flags are preserved on outbound webhook delivery so an upstream `sampled=0` opt-out is respected rather than silently re-enabled.

### Bulk-action audit triage

`POST /v1/admin/tenants/bulk-action`, `POST /v1/admin/webhooks/bulk-action`,
and `POST /v1/admin/budgets/bulk-action` each emit **one** `AuditLogEntry`
per invocation — not one per row. Since v0.1.25.30 that entry's
`metadata` map carries enough information that the audit log alone is
sufficient for post-incident triage; operators no longer need to
preserve their copy of the synchronous response envelope or re-run the
operation (which is unacceptable for destructive actions like DELETE
or DEBIT).

**Locating the entry.** Filter `listAuditLogs` by `operation` (one of
`bulkActionTenants`, `bulkActionWebhooks`, `bulkActionBudgets`),
`resource_type` (`tenant` / `webhook` / `budget`), and `tenant_id`.
`resource_id` is the literal string `bulk-action` (not a specific row
id — the entry covers the whole batch).

```bash
# Every budget bulk-op by a specific tenant in the last hour.
GET /v1/admin/audit/logs?operation=bulkActionBudgets&tenant_id=acme&from=<iso8601>

# Every bulk-op that had at least one failure — filter client-side on
# metadata.failed > 0.
GET /v1/admin/audit/logs?operation=bulkActionWebhooks
```

**`metadata` keys (v0.1.25.30+, `LinkedHashMap` field order preserved):**

| Key | Type | Purpose |
|---|---|---|
| `action` | string | Echo of the action enum — e.g. `CREDIT`, `SUSPEND`, `PAUSE`. |
| `total_matched` | int | Rows the filter matched (= `succeeded + failed + skipped`). |
| `succeeded` / `failed` / `skipped` | int | Bucket counts — kept for backward compat with v0.1.25.26..29 dashboards. |
| `succeeded_ids` | string[] | Per-row ids of successful operations — paper trail for compliance. |
| `failed_rows` | object[] | Full `{ id, error_code, message }` per failure — replaces the "re-run to see what broke" workflow. |
| `skipped_rows` | object[] | Full `{ id, reason }` per skip — distinguishes `ALREADY_IN_TARGET_STATE` from `ALREADY_DELETED`. |
| `filter` | object | Normalized filter echoed as-is. Reconstructs operator intent from the audit log alone. |
| `idempotency_key` | string | Correlates envelope ↔ retries ↔ audit across replay attempts. |
| `duration_ms` | int64 | Handler-entry → audit-emit wall-clock for SLO triage without needing trace sampling. |

**Size bound.** 500-row bulk-action cap × ~80 B per outcome + filter
echo + fixed keys ≈ 40 KB worst-case per audit row. Within Redis
value-size comfort range; no sizing changes required at the audit
repository layer.

**Triage recipe.**

1. Find the entry: `listAuditLogs?operation=bulkAction*&tenant_id=…`.
2. Compare `total_matched` to `succeeded + failed + skipped` — they
   must agree; if not, suspect metadata corruption (file a bug).
3. For each `failed_rows[i]`: classify by `error_code` — e.g.
   `BUDGET_EXCEEDED` means DEBIT would have taken `remaining`
   negative; `INVALID_TRANSITION` means unit mismatch / ledger
   FROZEN / CLOSED; `NOT_FOUND` means the row was deleted between
   match and apply (race — benign).
4. For each `skipped_rows[i]`: `reason=ALREADY_IN_TARGET_STATE` is
   the no-op case (currently only `REPAY_DEBT` on `debt==0`).
5. Remediate the underlying issue (refill a ledger, unfreeze,
   re-enable, …) and re-run the bulk-op with (a) a filter narrowed
   to just the failed ids AND (b) a **new** `idempotency_key`.
   Succeeded rows from the first round are protected by the Lua
   fund-idempotency cache on budget actions and by
   already-in-target-state skips on tenant / webhook actions —
   re-targeting by filter cannot double-apply.

**Dashboards that pre-date v0.1.25.30** (i.e. parse bulk-action
audit entries) may display only the six original keys. Field
insertion order is stable; new clients iterating the map see the
five new keys in the documented positions. Clients that deserialize
into a fixed DTO must tolerate unknown keys (Jackson
`ignoreUnknown=true` or equivalent).

### Tenant-close cascade (v0.1.25.35+)

**Cascade mode: Mode B (flip-first-with-guarded-cascade).** Spec
v0.1.25.31 Rule 1 defines two conformant cascade modes; this server
implements **Mode B**. The tenant flip commits first; Rule 2's
`TENANT_CLOSED` guard activates; children transition per-child (not
atomically as one tenant-wide transaction). A durable Redis intent is
written before the flip, and the request path plus scheduled reconciler
drive that intent to completion.

Closing a tenant via `PATCH /v1/admin/tenants/{id}` with
`status=CLOSED` (or via `POST /v1/admin/tenants/bulk-action` with
`action=CLOSE`) cascades owned objects into their terminal states per
spec v0.1.25.31 Rule 1:

| Owned type | Pre-close state | Post-cascade state | Wire-visible effect |
|---|---|---|---|
| `BudgetLedger` | `ACTIVE` / `FROZEN` | `CLOSED` | `reserved` drained to 0; `remaining` bumped by the released amount; `closed_at` stamped. |
| `WebhookSubscription` | `ACTIVE` / `PAUSED` | `DISABLED` | Rule 2 prevents any re-enable — DISABLED is effectively-terminal for closed-owner subscriptions. |
| `ApiKey` | `ACTIVE` | `REVOKED` | `revoked_at` stamped; reason `tenant_closed`. |

The tenant flip happens BEFORE the cascade so spec v0.1.25.31 Rule 2
(`TENANT_CLOSED` 409 on any mutating op against a CLOSED-owner object)
is active during the cascade window. Concurrent admin PATCHes during
a cascade 409 rather than racing to resurrect a just-terminated
child. Each child transition and its outbox record are committed in one
Redis Lua operation. The outbox item is acknowledged only after the
child audit row and typed event have persisted; stable audit/event IDs
make retries storage-idempotent. The parent status flip, committed marker,
and `tenant.closed` outbox item are one Redis Lua transaction, so a crash
cannot leave a CLOSED tenant without a durable parent-event obligation. On
partial failure the tenant remains CLOSED and the durable work item remains
queued.

**Convergence mechanism (Rule 1(c) — MUST be documented).** Spec
v0.1.25.31 Rule 1(c) requires a Mode B implementation to document
how an interrupted cascade reaches terminal state in bounded time.
For this server:

- **Primary mechanism:** the request attempts the cascade immediately.
  Any incomplete result is retained in `tenant-close:pending`; the
  scheduled reconciler reads only due work (it does not scan every
  tenant) and retries up to `max-tenants-per-run`. A 5-minute
  per-tenant distributed lease prevents concurrent replicas from
  executing the same cascade. The owner renews that lease during active
  work; token-checked renewal/release prevents an expired owner from
  modifying a successor's lease. Child CAS scripts remain the final
  duplicate-mutation guard if ownership is lost between heartbeats.
- **Contention:** a request that finds a healthy lease owner remains a
  spec-compatible 200 and returns `X-Cycles-Cascade-Status: in_progress` plus
  `Retry-After: 1`, rather than a 500. An already-CLOSED bulk row is also
  classified `CASCADE_IN_PROGRESS`; a freshly committed row remains succeeded
  because its parent transition did occur. The work remains scheduled and no
  second worker mutates children.
- **Bound:** with defaults and a queue depth `Q`, a due item is selected
  within approximately
  `ceil(Q / 100) × 300 seconds + cascade execution time`. Failed items
  are made eligible again after 30 seconds; the 300-second scheduler
  interval is the effective default retry cadence. Size the interval
  and per-run maximum to keep this bound inside the operational SLO.
- **Poison-item bound:** an outbox item uses exponential backoff and is moved
  to `tenant-close:outbox:dead-letter:<tenant_id>` after 8 failed emissions.
  A tenant with dead letters is removed from the automatic pending queue, so
  a corrupt item cannot retry forever. This pauses convergence and requires
  operator repair/requeue; alert on
  `cycles_admin_tenant_close_outbox_dead_letter_total`.
- **Operator acceleration:** for ordinary transient failures, re-issuing
  `PATCH /v1/admin/tenants/{id} { "status": "CLOSED" }` is safe. It
  re-runs the durable cascade immediately; already-terminal children
  and already-persisted outbox records do not produce duplicate rows. A
  dead-lettered item must be repaired and explicitly requeued as below.
- **Detection:** alert on increases in
  `cycles_admin_tenant_close_reconcile_incomplete_total` or
  `cycles_admin_tenant_close_reconcile_errors_total`. Inspect queue
  depth with `ZCARD tenant-close:pending` and due tenants with
  `ZRANGEBYSCORE tenant-close:pending -inf <epoch-ms>`. A CLOSED tenant
  with a non-terminal child beyond the calculated bound is an incident.
- **Reads remain consistent (Rule 1(d)).** Non-terminal children of
  a CLOSED tenant are observable via `GET` until the cascade reaches
  them. Clients and dashboards should treat the combination "tenant
  CLOSED + child non-terminal" as a transient state that converges,
  not a permanent inconsistency.

For bulk CLOSE, a row whose child cascade is incomplete is returned in
`failed[]` (or `skipped[]` with `CASCADE_IN_PROGRESS` when another healthy
worker owns it). Its durable work item is still reconciled because the parent
status flip has already committed under Mode B. The durable parent lifecycle
event is ordered after all child outbox items and is emitted only when that
logical close converges; it is therefore neither lost nor emitted ahead of an
incomplete cascade.

**Correlation.** Every child audit entry and event emitted in one cascade
shares:

- the originating PATCH's `request_id` and W3C `trace_id`
- a dedicated `correlation_id = tenant_close_cascade:<tenant_id>:<request_id>`

For a single PATCH, the parent `TENANT_CLOSED` event stamps the same
`correlation_id`, so dashboards can JOIN it with the child
`*_via_tenant_cascade` events as one logical operation. A bulk CLOSE parent
uses the bulk invocation correlation ID even when a reconciler emits it later;
its child events retain the per-tenant cascade ID and share the original
request/trace IDs. Outbox processing is at-least-once, while deterministic IDs
and atomic delivery enqueue make the persisted audit/event/delivery effects
idempotent.

**Audit.** One entry per touched child, `operation=tenant_close_cascade`,
`resource_type` in `{budget, webhook_subscription, api_key}`. Metadata
includes `prior_status`, `new_status`, plus type-specific fields
(`released_reserved_amount` on budgets, `name` on webhooks / api
keys).

```bash
# Find every cascade entry for a specific tenant close.
GET /v1/admin/audit/logs?operation=tenant_close_cascade&tenant_id=acme

# Cross-reference with the parent tenant.closed entry by trace_id
# (or request_id). All siblings share both.
GET /v1/admin/audit/logs?trace_id=<value>
```

**Event kinds (v0.1.25.35+).**

- `budget.closed_via_tenant_cascade` — one per closed budget; `data.cascade_reason = "tenant_closed"`.
- `webhook.disabled_via_tenant_cascade` — one per disabled subscription.
- `api_key.revoked_via_tenant_cascade` — one per revoked key.
- `reservation.released_via_tenant_cascade` — aggregate, one per budget that had `reserved > 0` at close time; `data.released_amount` carries the total.
- The parent `tenant.closed` event carries a `data.cascade` sub-object
  with counts: `budgets_closed`, `webhooks_disabled`, `api_keys_revoked`,
  `reservations_released`.

**Triage recipe for "why is my budget still FROZEN on a closed tenant?"**

1. Confirm the tenant is actually CLOSED: `GET /v1/admin/tenants/{id}`.
2. List budgets owned by the tenant: `GET /v1/admin/budgets?tenant_id={id}&status=FROZEN`.
3. If any remain non-CLOSED, the cascade partially failed. Re-issue
   the close: `PATCH /v1/admin/tenants/{id} { "status": "CLOSED" }`.
   This is a no-op on the tenant but re-runs the cascade and picks up
   stragglers.
4. Confirm with `GET /v1/admin/events?event_type=budget.closed_via_tenant_cascade&tenant_id={id}`
   — you should see one event per remediated budget.

**Dead-letter inspection and requeue.** First list the item IDs and inspect the
retained body. Repair the underlying dependency or correct the body before
requeueing; do not delete the intent or committed marker.

```bash
redis-cli SMEMBERS tenant-close:outbox:dead-letter:<tenant_id>
redis-cli GET tenant-close:outbox:item:<tenant_id>:<item_id>

# Atomically move one repaired item back to the live outbox and make it due.
redis-cli --eval /dev/stdin \
  tenant-close:outbox:dead-letter:<tenant_id> \
  tenant-close:outbox:<tenant_id> \
  tenant-close:outbox:attempts:<tenant_id> \
  tenant-close:pending , <item_id> <tenant_id> <epoch-ms-now-plus-one> <<'LUA'
if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then
  redis.call('SADD', KEYS[2], ARGV[1])
  redis.call('HDEL', KEYS[3], ARGV[1])
  redis.call('ZADD', KEYS[4], ARGV[3], ARGV[2])
  return 1
end
return 0
LUA
```

**Post-close operator mutations 409 with `TENANT_CLOSED` (Rule 2).**
This is the expected contract — don't treat it as a bug. To change
anything about a CLOSED tenant's objects, the tenant itself would need
to be re-opened, and closing is deliberately terminal (no re-open
affordance exists by spec).

---

## Metrics inventory

All domain metrics live under the `cycles_admin_*` namespace. Spring Boot
auto-metrics (`http_server_requests_seconds`, `jvm_*`, `process_*`,
`logback_events_total`) are also emitted and worth scraping.

### Event emission

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_admin_events_emitted_total` | `type`, `result` | Every domain-event emission. `type` is the event-type value (e.g. `budget.created`); `result=success`/`failure`. |
| `cycles_admin_events_payload_invalid_total` | `type`, `expected_class` | Orthogonal to the above — increments once per emission where the `data` payload did not round-trip through the `EventPayloadTypeMapping`-assigned class. Any nonzero count is a producer bug (the event is still persisted and dispatched). |

### Webhook dispatch

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_admin_webhook_dispatched_total` | `result` | Every webhook-delivery enqueue attempt. `result=queued` when at least one matching subscription existed and a delivery row was created; `result=failure` on dispatch/enqueue error. This is a dispatch-path counter, not a delivery-outcome one — actual HTTP-delivery outcomes live in the sibling `cycles-server-events` service. |

### Tenant-close convergence

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_admin_tenant_close_reconcile_incomplete_total` | — | A scheduled reconciliation attempt completed without throwing but still had unfinished cascade work. Alert on sustained growth and inspect `tenant-close:pending`. |
| `cycles_admin_tenant_close_reconcile_errors_total` | — | A scheduled reconciliation attempt failed before it could report a normal incomplete result. Any sustained increase indicates Redis, serialization, lease, or cascade-path failure. |
| `cycles_admin_tenant_close_outbox_dead_letter_total` | `resource_type` | A required cascade audit/event outbox item exhausted eight attempts and was parked for operator repair. Any increment requires dead-letter inspection and explicit requeue. |

### Audit log writes (v0.1.25.20+)

| Metric | Tags | What it tells you |
|---|---|---|
| `cycles_admin_audit_writes_total` | `path_class`, `outcome` | Every failure-path audit-write attempt. `path_class=failure` (only value currently emitted — success-path writes come from per-controller `AuditRepository.log()` calls directly, no counter). Outcomes: `written` (persisted), `error` (write itself failed — Redis down, meter registry wedged, serialization bug; business request still returned correctly), `sampled-out` (unauthenticated-tier entry intentionally dropped by `audit.sample.unauthenticated` > 1). **`outcome=error` is the silent-coverage-loss alert surface** — alert on any nonzero rate. **`outcome=sampled-out` is operator-intentional** — high values under DDoS are the sampling working as configured; aggregate volume is (`written` + `sampled-out`). |

### HTTP

Standard Spring Boot `http_server_requests_seconds` timer. Good for
availability SLOs, latency alerts, and per-endpoint traffic shape.

```promql
# Request rate per endpoint
sum by (uri) (rate(http_server_requests_seconds_count{job="cycles-server-admin"}[1m]))

# Error rate (5xx only — 400/403/404/409 are protocol-valid responses)
sum(rate(http_server_requests_seconds_count{job="cycles-server-admin",status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{job="cycles-server-admin"}[5m]))
```

**Percentile-histogram note (same as cycles-server):** Spring Boot doesn't
emit histogram buckets by default. To make `histogram_quantile` queries
return real values, set this in `application.properties`:

```properties
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

Without it, `http_server_requests_seconds_bucket` has no `le`-labelled
series and p99 alerts silently evaluate to `NaN`. A mean-latency fallback
alert (`sum / count`) works without any configuration.

---

## Alerts worth paging on

Copy-paste these into your `prometheus.rules.yml` and tune thresholds to
your actual traffic.

### Availability

```yaml
- alert: CyclesAdminDown
  expr: up{job="cycles-server-admin"} == 0
  for: 2m
  labels: {severity: page}
  annotations:
    summary: cycles-server-admin is down
    runbook: https://github.com/runcycles/cycles-server-admin/blob/main/OPERATIONS.md#incident-playbook

- alert: CyclesAdminErrorRateHigh
  # >5% of requests returning 5xx over 5 minutes = actual server problem.
  # Admin protocol-valid denials (400/401/403/404/409) are deliberately
  # excluded.
  expr: |
    sum(rate(http_server_requests_seconds_count{job="cycles-server-admin",status=~"5.."}[5m]))
      / sum(rate(http_server_requests_seconds_count{job="cycles-server-admin"}[5m]))
    > 0.05
  for: 5m
  labels: {severity: page}
```

### Event-payload producer bug

```yaml
- alert: AdminEventPayloadInvalid
  # Any nonzero value on this counter means a producer emitted an event
  # whose `data` didn't round-trip through its EventPayloadTypeMapping
  # class. This is always a code bug in the admin itself — investigate
  # on first occurrence.
  expr: sum(rate(cycles_admin_events_payload_invalid_total[10m])) > 0
  for: 5m
  labels: {severity: ticket}
  annotations:
    summary: "admin emitted invalid event payload: type={{ $labels.type }}"
    description: "Check EventService + EventPayloadTypeMapping for the offending type."
```

### Admin-key misconfiguration

If the admin API key env var isn't set, the server responds to every
admin-auth request with `500 Server misconfiguration: admin API key not
set` (`AuthInterceptor.validateAdminKey`). That pattern shows up as a
spike of 500s on endpoints that require admin auth — the error-rate alert
above catches it. A more specific alert:

```yaml
- alert: CyclesAdminKeyMisconfigured
  # If the server is running but admin.api-key is unset, EVERY request to
  # admin-only endpoints returns 500. Tight, early alert.
  expr: |
    sum(rate(http_server_requests_seconds_count{job="cycles-server-admin",uri=~"/v1/admin/.*",status="500"}[2m])) > 0.05
  for: 3m
  labels: {severity: page}
  annotations:
    summary: cycles-server-admin is returning 500 on admin endpoints
    description: "Check ADMIN_API_KEY env var / admin.api-key property on the running pod."
```

### Audit-write silent-coverage loss

```yaml
- alert: AdminAuditWriteErrors
  # v0.1.25.20+: failure-path audit writes that themselves fail. The
  # business request still returns the correct error response, but the
  # audit trail silently loses coverage. Compliance-critical — page on
  # first occurrence.
  expr: sum(rate(cycles_admin_audit_writes_total{outcome="error"}[5m])) > 0
  for: 5m
  labels: {severity: page}
  annotations:
    summary: admin audit writes failing — compliance coverage at risk
    description: "Failure-path audit entries are not persisting. Check Redis health + AuditRepository error logs."
```

### Webhook dispatch failures

```yaml
- alert: AdminWebhookDispatchFailureRate
  # Enqueue failures (Redis stream write) — if this is nonzero, webhook
  # deliveries are being dropped before the events service can see them.
  expr: |
    sum(rate(cycles_admin_webhook_dispatched_total{result="failure"}[5m]))
      / sum(rate(cycles_admin_webhook_dispatched_total[5m]))
    > 0.01
  for: 10m
  labels: {severity: ticket}
```

### Redis connectivity (infers from error rate)

The admin server depends on Redis for essentially every operation. A
Redis outage manifests as a 500-spike on all mutating endpoints. The
`CyclesAdminErrorRateHigh` alert above catches this. For a more targeted
signal, scrape the Spring Boot `logback_events_total{level="ERROR"}`
metric and alert on a rate spike.

---

## Configuration tuning

All configuration is via environment variables or `application.properties`.
The Docker image reads env vars; source builds read `application.properties`
with env overrides.

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `admin.api-key` | `ADMIN_API_KEY` | (unset — server refuses admin requests) | Shared secret for `X-Admin-API-Key` header on admin-plane endpoints. **Required in production.** |
| `redis.host` | `REDIS_HOST` | `localhost` | Redis host. |
| `redis.port` | `REDIS_PORT` | `6379` | Redis port. |
| `redis.password` | `REDIS_PASSWORD` | (unset) | Redis AUTH password. **Required by production Compose**, which starts Redis with `requirepass` and does not publish Redis on the host. |
| `webhook.secret.encryption-key` | `WEBHOOK_SECRET_ENCRYPTION_KEY` | (unset) | Base64 AES-256 key used to encrypt webhook signing secrets at rest. Must match the value on `cycles-server-events`. Production Compose requires it. Rotate by generating a new value, re-encrypting existing secrets, and deploying in lockstep with the events service. |
| `webhook.secret.encryption-required` | `WEBHOOK_SECRET_ENCRYPTION_REQUIRED` | `false` | Fail startup if the webhook encryption key is missing. Production Compose sets this to `true`; leave `false` only for local/dev plaintext compatibility. |
| `events.retention.event-ttl-days` | `EVENT_TTL_DAYS` | `90` | Retention for emitted events in Redis. Tune down in high-volume deployments to bound memory. |
| `events.retention.delivery-ttl-days` | `DELIVERY_TTL_DAYS` | `14` | Retention for webhook-delivery attempt records. |
| `audit.retention.authenticated.days` | `AUDIT_RETENTION_AUTHENTICATED_DAYS` | `400` | Retention (days) for authenticated audit entries (success + authenticated failure). Default 400 = SOC2 Type II 12-month lookback + 1-month buffer. **Set to `0` for indefinite** (legal hold, HIPAA-adjacent, forever-retain deployments, or when archiving externally). See [Audit retention tuning](#audit-retention-tuning) below. |
| `audit.retention.unauthenticated.days` | `AUDIT_RETENTION_UNAUTHENTICATED_DAYS` | `30` | Retention for pre-auth failure audit entries (sentinel `tenant_id=__unauth__`; also applied to legacy `<unauthenticated>` rows from v0.1.25.20..v0.1.25.27). Enough for brute-force / credential-stuffing forensic window. Set to `0` for indefinite. Admin-plane entries (sentinel `__admin__`, v0.1.25.28+) ride the **authenticated** tier, not this one. |
| `audit.sample.unauthenticated` | `AUDIT_SAMPLE_UNAUTHENTICATED` | `1` | Sampling rate for unauthenticated-tier entries — record 1 in N. Default `1` = record every attempt (full fidelity). Production Compose defaults to `100` to cut Redis write volume 100x on exposed deployments. Aggregate volume remains visible via the `cycles_admin_audit_writes_total` counter. Authenticated entries are **never** sampled regardless of this setting. Values `≤ 0` treated as `1` (misconfig safety). |
| `auth.failure-rate-limit.enabled` | `AUTH_FAILURE_RATE_LIMIT_ENABLED` | `false` | Enable per-source, per-process throttling for repeated 401/403 responses. Production Compose sets this to `true`. |
| `auth.failure-rate-limit.max-per-minute` | `AUTH_FAILURE_RATE_LIMIT_MAX_PER_MINUTE` | `300` | Failed-auth threshold per source/path class before responses become `429 LIMIT_EXCEEDED` and no extra failure audit row is written. The limiter is in-process and does not coordinate across replicas. |
| `auth.failure-rate-limit.max-tracked-sources` | `AUTH_FAILURE_RATE_LIMIT_MAX_TRACKED_SOURCES` | `10000` | Bound the limiter's in-memory source/path buckets. Stale buckets are removed first; the oldest live bucket is evicted when the cap is reached. |
| `tenant-close.reconciler.enabled` | `TENANT_CLOSE_RECONCILER_ENABLED` | `true` | Retry incomplete Mode-B cascades for tenants already marked `CLOSED`. |
| `tenant-close.reconciler.interval-ms` | `TENANT_CLOSE_RECONCILER_INTERVAL_MS` | `300000` | Fixed delay in milliseconds between reconciliation runs. |
| `tenant-close.reconciler.max-tenants-per-run` | `TENANT_CLOSE_RECONCILER_MAX_TENANTS_PER_RUN` | `100` | Maximum due work items processed per run. Redis score ordering and rescheduling provide durable progress across restarts and replicas. |
| `spring.task.scheduling.pool.size` | `TASK_SCHEDULER_POOL_SIZE` | `2` | Scheduler threads. Values below `2` are raised to the enforced safety floor: one thread may run the reconciler while another renews its distributed lease. |
| JVM options | `JAVA_OPTS` | (unset) | Extra JVM flags consumed by the Docker image entrypoint. Production Compose sets G1, `MaxRAMPercentage=75`, and string deduplication defaults. |
| `audit.sweep.cron` | `AUDIT_SWEEP_CRON` | `0 0 3 * * *` | Cron schedule for the daily audit index sweep (`ZREMRANGEBYSCORE` on expired pointers). Default 03:00 server time. Sweep is best-effort; skipped entirely when `audit.retention.authenticated.days=0` (indefinite — nothing to sweep). |
| `dashboard.cors.origin` | `DASHBOARD_CORS_ORIGIN` | `http://localhost:5173` | CORS allowed origin(s). Comma-separated. **In production, set to your dashboard URL** — the default only works against the local Vite dev server. |
| `springdoc.api-docs.enabled` | `API_DOCS_ENABLED` | `false` | Enable generated OpenAPI JSON. When enabled, `/api-docs`, `/v3/api-docs`, and Swagger paths require `X-Admin-API-Key`. |
| `springdoc.swagger-ui.enabled` | `SWAGGER_ENABLED` | `false` | Enable Swagger UI. When enabled, Swagger paths require `X-Admin-API-Key`. |
| `contract.validation.enabled` | `CONTRACT_VALIDATION_ENABLED` | `true` (tests) / `false` (runtime) | Fetch and validate against the live admin spec at build time. Disable for offline / air-gapped builds. Does not affect runtime — enforcement happens only in the test harness. |

In `docker-compose.full-stack.prod.yml`, the events service exposes management
port `9980` for health/readiness. Do not publish its internal worker port
`7980` on ingress.

### Audit retention tuning

The retention defaults (400 days authenticated, 30 days unauthenticated)
are calibrated for SOC2 Type II out of the box. Different compliance
regimes and operational realities warrant different settings:

**Scenario: SOC2 only — standard deployment.** Defaults work as shipped.
No changes needed.

**Scenario: HIPAA-adjacent / multi-year retention required.** Set
`audit.retention.authenticated.days=0` (indefinite). The authenticated
tier now retains forever in Redis. Plan for external archival (S3,
SIEM) — Redis isn't designed to be a long-term store. Pair this with
explicit monitoring of Redis memory, and consider shipping audit events
to an immutable archive on a schedule.

**Scenario: legal hold triggered on a specific tenant.** Set
`audit.retention.authenticated.days=0` during the hold period. After
resolution, restore the previous value — entries written during the
hold will still age out normally (TTL is applied at write time, not
retroactively).

**Scenario: internet-facing admin endpoint, no upstream WAF.** Set
`audit.sample.unauthenticated=100` (or higher) to reduce DDoS
write-amplification. A sustained 10k req/s failed-auth flood at rate 1
would write ~30k Redis ops/s on a single Lua-serialized surface; rate
100 cuts that to 300 ops/s. Aggregate attempt volume stays visible via
`cycles_admin_audit_writes_total{outcome=sampled-out}`.

**Scenario: memory-constrained deployment.** Tune retention down
(e.g. `authenticated.days=90`, `unauthenticated.days=7`) BEFORE adjusting
sampling. TTL + sweep bound memory footprint at a predictable rate;
sampling reduces fidelity. If ops really needs to trade memory for
fidelity, both levers together work — just understand which compliance
controls you're affecting.

**Checking what you actually have stored:**

```bash
# Authenticated entries for a specific tenant, TTL remaining on first hit:
redis-cli -h $REDIS_HOST KEYS 'audit:log:*' | head -1 | xargs redis-cli -h $REDIS_HOST TTL

# Global index size (unbounded if no sweep or retention=0):
redis-cli -h $REDIS_HOST ZCARD audit:logs:_all

# Per-tenant index sizes (top 10 by cardinality):
redis-cli -h $REDIS_HOST KEYS 'audit:logs:*' | while read k; do
  echo "$(redis-cli -h $REDIS_HOST ZCARD "$k") $k"
done | sort -rn | head -10
```

### Rotating the admin API key

1. Generate a new random key (e.g. `openssl rand -base64 32`).
2. Update `ADMIN_API_KEY` on all admin pods; roll the deployment.
3. Update every operator tool (dashboards, CI, scripts) with the new key.

Because the admin key is a shared secret (not per-operator), there's no
revocation surface — the rotation itself is the revocation of the old key.
All ongoing requests with the old key will get `401` after the last pod
cuts over. Budget a brief read window where both keys are accepted if
you have many operator tools (dual-admin-key support is not currently
implemented — plan a short cutover instead).

### Dashboard CORS

`DASHBOARD_CORS_ORIGIN` is a comma-separated list — `https://dash.example.com,https://staging.example.com`
is valid. Methods allowed are `GET, POST, PUT, PATCH, DELETE, OPTIONS`
(explicitly — `PUT` is load-bearing for `PUT /v1/admin/config/webhook-security`).
Allowed headers: `X-Admin-API-Key, X-Cycles-API-Key, X-Request-Id,
X-Cycles-Trace-Id, traceparent, tracestate, Content-Type`. If a new auth
header or a new HTTP method ever gets added to the spec, update
`WebConfig.addCorsMappings`.

---

## Incident playbook

### "admin API is returning 500 on everything"

Fastest root-cause check: `kubectl exec -it <admin-pod> -- env | grep -i
admin_api_key`. If empty or blank, the pod is misconfigured — the
AuthInterceptor logs `Admin API key is not configured — rejecting admin
request` on each attempt. Patch the env var and bounce the pod.

### "events are emitting but the dashboard says they're missing data"

Check `cycles_admin_events_payload_invalid_total` — any nonzero count
points to a specific `type` + `expected_class` mismatch. The event is
still persisted, but its `data` payload didn't round-trip through its
mapped class. Engineering fix: inspect `EventPayloadTypeMapping` +
`EventService.emit` for that `type`, then patch and redeploy.

### "clients getting 400 'Malformed request body' on legitimate requests"

Request DTO parsing is strict: an unknown JSON field is a contract violation
and returns `400 Malformed request body`. This is intentional; remove the field
or advance the server/spec before retrying. Redis-persisted domain rows use a
separate tolerant mapper, so additive stored fields remain safe during rolling
deployments. Unknown permissions on
`/v1/admin/api-keys` now return `400 Unrecognized permission: <value>`
naming the offender. For the generic message, check both JSON syntax and field
names against the pinned contract.

### "list API-keys is missing some records"

Pre-v0.1.25.17 bug: Lua cjson serialized empty `List<String>` fields
as `{}` instead of `[]`, causing Jackson to fail to deserialize the
record (see AUDIT.md v0.1.25.17 for the full root cause). Any record
that's mutated (revoke, update) on v0.1.25.17+ self-heals to the correct
wire format. If stale corrupted records are still missing, the
`LenientStringListDeserializer` added in v0.1.25.17 now parses them
on read, so upgrading the admin alone (without touching stored data)
fixes the listing. For a fleet-wide self-heal, touch each record with
a no-op PATCH.

### "a broad sorted list returns LIMIT_EXCEEDED"

Arbitrary non-primary sorts must hydrate the complete candidate window to be
correct. The server permits that exact sort through 20,000 candidates and
returns `400 LIMIT_EXCEEDED` above that boundary; it never silently truncates.
Narrow `tenant_id` where the endpoint supports it, or narrow `from` / `to` on
event and audit lists, then retry with the same sort. Primary/indexed order
(for example the default timestamp order on events and audit logs) uses a
separate 5,000-candidate sparse-filter budget. If that budget is exhausted,
narrow the time range or filters; the server will not perform an unbounded
Redis hydration scan.

Set-backed exact sorts also enforce a 50,000-source-candidate scan ceiling
before the 20,000 filtered-result sort ceiling. Surfaces without a useful
narrowing filter should use their primary/indexed order at larger cardinality.

### "a paginated list returns INVALID_REQUEST after records expire"

Cursors identify the last boundary row. If retention, TTL, or deletion removes
that row, every list implementation consistently returns `400 INVALID_REQUEST`
with restart guidance instead of silently truncating the result. Restart from
page one with no cursor and de-duplicate IDs already processed. Internal
overview pagination performs that restart itself with a bounded retry count.

### "a bulk request is stuck IDEMPOTENCY_IN_PROGRESS"

In-progress v2 claims deliberately have no TTL and cannot be taken over after
an arbitrary timeout: a slow original request must never overlap a retry and
double-execute mutations. Normal pre-mutation failures owner-safely abandon the
claim; successful requests replace it with a completed replay envelope that
expires normally. During rolling upgrades, v2 lookup also honors a completed
legacy entry.

If a process dies after entering mutation and leaves a claim, first determine
whether its effects and bulk audit envelope committed. Inspect
`HGETALL idem:v2:<endpoint>:<idempotency_key>` and correlate the hashed key with
the request/audit logs. Only after reconciling the affected rows may an operator
delete that one claim and retry. Never delete an in-progress claim merely
because it is old; the original worker may still be active.

### "after revoking an admin-key secret, old callers are still getting through"

The admin API key is a shared secret with no revocation surface — rotate
by generating a new key, updating env vars, and rolling deployments.
During the rolling deploy, pods with the old `ADMIN_API_KEY` env var
will still accept the old key. This is not a security regression —
it's the rotation semantic. Minimize the window by rolling fast.

### "the nightly pinned-contract drift job is red"

Ordinary contract tests fetch the reviewed commit pinned in
`ContractSpecLoader`, so PR builds are reproducible. The nightly drift job
compares that exact YAML with `cycles-protocol/main` and fails when upstream
changes. Review the upstream diff, update the admin and tests if needed, then
advance `SPEC_REVISION` deliberately. Do not point ordinary CI back at a moving
branch.

For in-flight spec PRs, point the admin PR's `contract.spec.url` at the
spec-PR branch's raw URL until the spec PR merges. The admin AUDIT.md
entries call this out when the coordination pattern is used.

---

## Nightly CI coverage (v0.1.25.21+)

Three scheduled workflows watch behavior that PR-level tests or a reviewed pin
cannot catch:

| Workflow | Schedule | Purpose | Failure-signal runbook |
|---|---|---|---|
| [`.github/workflows/nightly-property-tests.yml`](.github/workflows/nightly-property-tests.yml) | 06:00 UTC daily | jqwik property-based invariants (6 tests, 100 tries nightly). Asserts `logFailure` correctness across arbitrary inputs — exactly-one-outcome per call, authenticated-tier never sampled, TTL tier matches tenant_id, sanitizeMessage bulletproof, non-throwing contract. | **A failure here means a real contract regression.** Download the surefire report from the failed run — jqwik embeds the minimal shrunk input (e.g. `tenantId=""`, `rate=Integer.MAX_VALUE`, `status=500`). Paste it into a unit test for fast-feedback iteration. Priority: **high** — these invariants are NORMATIVE; failing means v0.1.25.20's compliance guarantees no longer hold. |
| [`.github/workflows/nightly-audit-soak.yml`](.github/workflows/nightly-audit-soak.yml) | 06:30 UTC daily | 10-min × 500 ops/s failure flood against real Testcontainers Redis. 5 invariants: heap stable (AS1), latency stable (AS2), counter-sum complete (AS3), index-cardinality bounded (AS4), network-error rate < 1% (AS5). | Which invariant failed determines the triage path: **AS1** — memory leak in the audit-write path; check `AuditFailureService` or `AuditRepository` for recent additions. **AS2** — p99 latency regression on the hot path; profile `logFailure` with a flame graph. **AS3** — lost counter increments under contention; examine `ThreadLocalRandom` usage and meter-registry atomics. **AS4** — orphan ZADD bug; check Lua script for divergence between SET + ZADD atomicity. **AS5** — Redis-pool exhaustion; increase pool size or investigate connection leak. Priority: **medium** — soak catches slow regressions; single-day failure is often transient (CI load variance). Two consecutive failures = genuine problem. |
| [`.github/workflows/nightly-contract-drift.yml`](.github/workflows/nightly-contract-drift.yml) | 05:30 UTC daily | Downloads both the reviewed contract revision and `cycles-protocol/main` with bounded retries, then compares them byte-for-byte. | Review the upstream diff and advance `ContractSpecLoader.SPEC_REVISION` only with the corresponding implementation/tests. A transient download is retried five times before the job fails. |

All three workflows have `workflow_dispatch` triggers — run them manually from
the Actions tab when reproducing a failure or reviewing a contract-pin advance.

**Manual reproduction locally:**

```bash
# Soak (Docker required for Testcontainers)
mvn test -Psoak --file cycles-admin-service/pom.xml \
  -pl cycles-admin-service-api -am \
  -Dtest=AuditFailureSoakIntegrationTest

# Property-based (no Docker needed — uses Mockito + SimpleMeterRegistry)
mvn test -Pproperty-tests --file cycles-admin-service/pom.xml \
  -Djqwik.defaultTries=100
```

PR CI does not run either workflow — `<excludedGroups>soak,property-tests</excludedGroups>` in the default surefire config skips them. A PR that breaks these invariants will pass PR CI and only fail the next nightly run. If ops sees a nightly red shortly after a release, look at the prior day's merged PRs.

## Pre-release drift checklist

Each item below has caused an actual drift incident in this repo's
history; they are cheap to verify and painful to discover after a tag
is published. Run through the list before opening the release PR,
before tagging, and after `release.yml` finishes. A five-minute check
here prevents the multi-release consolidation windows we've had to
patch around (see the v0.1.25.38 release audit, which found
`AUDIT.md` and `README.md` spec-version pointers still stuck at
`v0.1.25.31` two days after the spec had advanced to `.32`).

Mirrors the same checklist in
[`cycles-server-events/OPERATIONS.md`](https://github.com/runcycles/cycles-server-events/blob/main/OPERATIONS.md#pre-release-drift-checklist)
with admin-specific additions (spec pointer, smoke-test-published job,
fourth-segment same-day follow-up convention).

### Before opening the release PR

- **`cycles-admin-service/pom.xml` `<revision>` is bumped to the target
  version.** Grep the module pom (the parent pom inherits via
  `${revision}`). A PR that edits code + CHANGELOG but forgets the pom
  bump will build and publish the *prior* version — silently.
- **`CHANGELOG.md` top entry heading matches the pom revision
  exactly.** `## [X.Y.Z.W] — YYYY-MM-DD` — bracketed version, em-dash,
  ISO date. Version must be string-equal to `<revision>`
  (`0.1.25.38` ≠ `0.1.25.38.0`). Same-day follow-up patches use the
  fourth segment (`0.1.25.28.1`, `0.1.25.38.1`); the preamble in
  `CHANGELOG.md` documents this convention.
- **`AUDIT.md` has a matching section for this version**, header date
  matches the CHANGELOG date. This is a
  [CLAUDE.md](CLAUDE.md) invariant ("always update AUDIT.md files
  when making changes"). Missing AUDIT entry blocks release.
- **`AUDIT.md` top `# <title>` line and `**Server version:**` line
  carry the target version**, not the previous one. Caught on 2026-04-22
  where the header still said 0.1.25.37 while the release was 0.1.25.38.
- **Spec pointer in `AUDIT.md` and `README.md` matches the actual
  spec version used.** `AUDIT.md`'s `**Spec:**` line and the
  `README.md:7` "Cycles Protocol vX.Y.Z" link. This drifted on
  v0.1.25.38 (stuck at `v0.1.25.31` while the spec had advanced to
  `v0.1.25.32`). Cross-check against the current
  `cycles-governance-admin-v0.1.25.yaml` `info.version`.

### Before tagging

- **Every commit on main since the last tag has a home in CHANGELOG.**
  `git log --oneline <last-tag>..HEAD` vs. the top `[X.Y.Z.W]` entry's
  bullet list. Dependabot bumps, CODEOWNERS / workflow tweaks, and
  chore commits often fall through the cracks — either list them under
  `### Changed` / `### Internal` or consolidate with a single "plus N
  transitive dependency bumps" line.
- **No un-tagged intermediate pom revision on main.** Before tagging
  `vX.Y.Z.W`, confirm the previous revision on main (`vX.Y.Z.W-1`) was
  either (a) tagged + released, or (b) explicitly folded into the
  current entry's CHANGELOG body. The events repo hit this exact drift
  on v0.1.25.10; admin's fourth-segment convention
  (`0.1.25.28.1` / `0.1.25.38.1`) is designed to avoid it but the
  check still matters.
- **Tag message is prose, not a bullet dump.** `git tag -a vX.Y.Z.W
  -m "..."` body should be a short paragraph matching the CHANGELOG
  entry's headline (admin's release precedent uses 2–3 sentences).

### After `release.yml` finishes

- **`smoke-test-published` job green.** This job runs inside
  `release.yml`: pulls `ghcr.io/runcycles/cycles-server-admin:X.Y.Z.W`,
  runs the container, probes `/actuator/health/readiness`, confirms the
  version matches the tag, probes an unauthenticated endpoint for the
  401 shape. A red smoke-test means a broken image was published — do
  not announce the release until it's fixed (either hotfix with a
  fourth-segment same-day patch, or delete the tag + release and
  re-cut). This is the admin-specific differentiator vs. events
  (events `release.yml` has build-and-push only).
- **Published image tag resolves correctly.** `docker pull
  ghcr.io/runcycles/cycles-server-admin:X.Y.Z.W` + `:latest` resolve
  to the same digest.
- **GitHub release notes match CHANGELOG.** `gh release view vX.Y.Z.W`
  — body should be the CHANGELOG entry verbatim (or close), not a raw
  autogenerated commit list.
- **Next cycle: bump pom immediately.** To make a future un-tagged
  revision a louder signal, bump `<revision>` to the next planned
  version on the post-release merge to main, even if no work is
  scheduled yet. The next PR will either add a CHANGELOG entry at the
  new version or roll the pom back, not silently ship under the new
  number.

## Related docs

- [`README.md`](README.md) — quickstart, architecture, build-from-source.
- [`CHANGELOG.md`](CHANGELOG.md) — consumer-facing release notes.
- [`AUDIT.md`](AUDIT.md) — internal engineering history.
- [cycles-server OPERATIONS.md](https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md) — runtime-plane runbook.
