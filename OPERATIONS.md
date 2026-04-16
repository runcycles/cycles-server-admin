# Operations guide

Operator-facing runbook for running `cycles-server-admin` in production.
Covers metrics, alerting recipes, configuration, and an incident playbook.

Assumes you are already deploying via the published Docker image
(`ghcr.io/runcycles/cycles-server-admin:<version>`) with Prometheus
scraping `/actuator/prometheus`. If you haven't set that up yet, see the
Monitoring section of [`README.md`](README.md) first.

`cycles-server-admin` is the **governance / admin plane** for the Cycles
Protocol on port `7979` — it owns tenant, budget, policy, API-key, webhook,
event, and audit CRUD. The **runtime plane** (reservations, commits, the
sub-10ms decision path) lives in the sibling `cycles-server` repo on port
`7878` with its own [OPERATIONS.md](https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md).
Alerts for the two planes should be routed separately.

## Table of contents

1. [Metrics inventory](#metrics-inventory)
2. [Alerts worth paging on](#alerts-worth-paging-on)
3. [Configuration tuning](#configuration-tuning)
4. [Incident playbook](#incident-playbook)

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
| `redis.password` | `REDIS_PASSWORD` | (unset) | Redis AUTH password. |
| `webhook.secret.encryption-key` | `WEBHOOK_SECRET_ENCRYPTION_KEY` | (unset) | Base64 AES-256 key used to encrypt webhook signing secrets at rest. Must match the value on `cycles-server-events`. Rotate by generating a new value, re-encrypting existing secrets, and deploying in lockstep with the events service. |
| `events.retention.event-ttl-days` | `EVENT_TTL_DAYS` | `90` | Retention for emitted events in Redis. Tune down in high-volume deployments to bound memory. |
| `events.retention.delivery-ttl-days` | `DELIVERY_TTL_DAYS` | `14` | Retention for webhook-delivery attempt records. |
| `dashboard.cors.origin` | `DASHBOARD_CORS_ORIGIN` | `http://localhost:5173` | CORS allowed origin(s). Comma-separated. **In production, set to your dashboard URL** — the default only works against the local Vite dev server. |
| `contract.validation.enabled` | `CONTRACT_VALIDATION_ENABLED` | `true` (tests) / `false` (runtime) | Fetch and validate against the live admin spec at build time. Disable for offline / air-gapped builds. Does not affect runtime — enforcement happens only in the test harness. |

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
Content-Type`. If a new auth header or a new HTTP method ever gets added
to the spec, update `WebConfig.addCorsMappings`.

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

As of v0.1.25.17, the admin API only returns generic `400 Malformed
request body` for actual Jackson parse failures. Unknown permissions on
`/v1/admin/api-keys` now return `400 Unrecognized permission: <value>`
naming the offender. If you see the generic message, it's a real JSON
parse error — check the client's request body, not the admin.

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

### "after revoking an admin-key secret, old callers are still getting through"

The admin API key is a shared secret with no revocation surface — rotate
by generating a new key, updating env vars, and rolling deployments.
During the rolling deploy, pods with the old `ADMIN_API_KEY` env var
will still accept the old key. This is not a security regression —
it's the rotation semantic. Minimize the window by rolling fast.

### "contract tests are red on a PR against cycles-protocol@main"

`ContractValidationConfig` fetches the spec from `cycles-protocol@main`
at build time. A breaking push to that repo will red the admin's CI
intentionally, as an early-warning signal. Options:
1. Fix the admin to match the new spec (usual path).
2. Point CI at the prior spec tag temporarily:
   `-Dcontract.spec.url=https://raw.githubusercontent.com/runcycles/cycles-protocol/<tag>/cycles-governance-admin-v0.1.25.yaml`.
3. Disable contract validation for the local iteration:
   `-Dcontract.validation.enabled=false`. Don't commit this — CI must
   keep it on.

For in-flight spec PRs, point the admin PR's `contract.spec.url` at the
spec-PR branch's raw URL until the spec PR merges. The admin AUDIT.md
entries call this out when the coordination pattern is used.

---

## Related docs

- [`README.md`](README.md) — quickstart, architecture, build-from-source.
- [`CHANGELOG.md`](CHANGELOG.md) — consumer-facing release notes.
- [`AUDIT.md`](AUDIT.md) — internal engineering history.
- [cycles-server OPERATIONS.md](https://github.com/runcycles/cycles-server/blob/main/OPERATIONS.md) — runtime-plane runbook.
