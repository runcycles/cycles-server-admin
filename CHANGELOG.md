# Changelog

All notable changes to `cycles-server-admin` are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions use
[Semantic-ish Versioning](https://semver.org/) with a fourth "patch-of-patch"
segment for same-day follow-ups.

This file is for **downstream consumers** — people pulling the Docker image or
JAR. For internal engineering history (root cause analyses, rejected
alternatives, test-strategy decisions) see [`AUDIT.md`](AUDIT.md).

Wire format is considered stable within a minor version (`0.1.x`). Breaking
changes to request/response bodies or Lua-script semantics would require a
minor bump. Additive fields (new optional response fields, new enum values,
new optional request fields) are **not** considered breaking.

## [0.1.25.21] — 2026-04-16

### Added

- **Nightly CI coverage for the audit-write path** (closes #102
  Phase 1). Two new scheduled workflows mirroring cycles-server's
  pattern:
  - `nightly-audit-soak.yml` (06:30 UTC) — runs
    `AuditFailureSoakIntegrationTest` at 500 ops/s × 10 min against a
    real Testcontainers Redis. Asserts 5 invariants: heap stability,
    latency non-degradation, counter-sum completeness (no lost
    increments under sustained contention), index-cardinality bound,
    network-error rate < 1%. Configurable duration + rps via
    `workflow_dispatch` inputs.
  - `nightly-property-tests.yml` (06:00 UTC) — runs
    `AuditCoverageInvariantsPropertyTest` via jqwik at
    `defaultTries=100` (5× PR depth). 6 NORMATIVE invariants
    around outcome-exclusivity, authenticated-tier immunity,
    TTL tier selection, message sanitization, and non-throwing
    contract. Shrinks failing cases to minimal reproducer.
- New Maven profiles `property-tests` and `soak` in
  `cycles-admin-service-api/pom.xml`. PR builds stay unchanged
  (tagged tests excluded via `<excludedGroups>`); nightly CI
  activates per-profile.
- jqwik 1.9.1 + jqwik-spring 0.11.0 as test-scope dependencies.

### Wire format

Unchanged. Test-infrastructure-only release. No production code paths modified beyond widening `AuditRepository.resolveTtlSeconds` visibility from package-private to public (test-access across modules; not intended as external API).

### Notes for upgraders

No action required. Pulling the image or JAR exposes no behaviour
change — this release purely adds nightly regression coverage that
ops will see as green/red workflow runs on `main`.

If you want to reproduce a nightly locally:

```bash
# Soak (10 min @ 500 ops/s vs real Redis)
mvn test -Psoak --file cycles-admin-service/pom.xml \
  -pl cycles-admin-service-api -am \
  -Dtest=AuditFailureSoakIntegrationTest

# Property-based invariants (100 tries)
mvn test -Pproperty-tests --file cycles-admin-service/pom.xml \
  -Djqwik.defaultTries=100
```

## [0.1.25.20] — 2026-04-16

### Added

- `GET /v1/admin/audit/logs` now returns entries for **failed** requests
  (401 / 403 / 400 / 404 / 409 / 500) alongside the existing success-path
  entries. Previously the audit log only captured successful operations
  (STATUS 201 / 200 / 204) because writes happened inside each controller
  method after the service call returned; errors short-circuited before
  the write. Closes a real compliance-auditing gap for SOC2 / GDPR
  reviewers who need authz denials, malformed requests, and state-
  transition violations on the record.
- Failure entries carry `status`, `error_code` (e.g. `UNAUTHORIZED`,
  `INVALID_REQUEST`, `TENANT_NOT_FOUND`), `operation="<METHOD>:<URI>"`
  (e.g. `POST:/v1/admin/budgets`), `metadata.error_message` (sanitized,
  1024-char capped), `metadata.method`, `metadata.path`, and — on
  `500 INTERNAL_ERROR` — `metadata.exception_class` for post-incident
  triage. `request_id`, `source_ip`, `user_agent` populated from the
  request the same way as success entries.
- New sentinel tenant value `"<unauthenticated>"` on failure entries
  where no authenticated tenant exists (missing / invalid API key, pre-
  auth failure). Preserves the spec's `tenant_id: required` invariant
  without a wire-format change. Queryable via
  `GET /v1/admin/audit/logs?tenant_id=%3Cunauthenticated%3E` for
  failed-attempt review.
- New Prometheus counter `cycles_admin_audit_writes_total{path_class,
  outcome}`. `path_class=failure,outcome=written` increments per
  failure entry persisted. `path_class=failure,outcome=error`
  increments when an audit write itself fails (Redis down, meter
  registry wedged, …). `path_class=failure,outcome=sampled-out`
  increments when an unauthenticated-tier entry was intentionally
  dropped by sampling. **Alert on `outcome=error` nonzero** — audit
  writes are non-fatal to the request but silent coverage loss is the
  exact failure mode this release is trying to prevent.
- **Tiered TTL on audit entries** with SOC2-compliant defaults
  (configurable, settable to 0 for indefinite):
  - `audit.retention.authenticated.days` — default **400** (SOC2 Type
    II 12-month lookback + 1-month auditor-engagement buffer). Applies
    to every entry where `tenant_id != "<unauthenticated>"` — success
    entries and authenticated failures alike. Set to `0` for
    indefinite retention (legal hold, HIPAA-adjacent, forever-retain
    deployments).
  - `audit.retention.unauthenticated.days` — default **30**. Applies
    to pre-auth failures attributed to the sentinel tenant. Enough
    for brute-force / credential-stuffing post-mortem; aggregate
    attempt volume stays visible via the Prometheus counter regardless
    of TTL. Set to `0` for indefinite.
- **Optional sampling for unauthenticated entries** —
  `audit.sample.unauthenticated` (default **1** = no sampling).
  Setting to `100` records 1 in 100 pre-auth failures; operator
  opt-in only. Authenticated entries are **never** sampled.
- **Daily audit index sweep** — new `@Scheduled` job
  (`audit.sweep.cron`, default `0 0 3 * * *`) purges TTL-expired
  pointers from the `audit:logs:_all` + per-tenant sorted-set indexes.
  Without this, indexes grow unboundedly even though the underlying
  log records have expired. Sweep is best-effort and non-fatal; skipped
  entirely when `audit.retention.authenticated.days=0` (indefinite
  retention — no expiry means no zombies to clean up).

### Wire format

Additive. Success-path entries are byte-identical to 0.1.25.19. Failure
entries reuse the existing `AuditLogEntry` schema — `error_code` was
already defined as optional; `metadata` was already `Map<String,
Object>`. No spec change required.

Retention is a Redis-side TTL on audit-log keys — it's an *infrastructure*
change, not a wire-format change. Clients that persist audit-log
responses locally aren't affected by retention settings server-side.

### Notes for upgraders

- **Breaking semantic change for audit-log consumers.** Queries filtered
  by `status=201` / `status=200` / `status=204` return exactly the same
  rows as before. Queries without a `status` filter (or with
  `status=4xx/5xx`) now surface failure entries that didn't exist in
  0.1.25.19. Dashboards that assumed "audit entry exists ⇒ operation
  succeeded" must switch to checking `status` or `error_code` to
  distinguish.
- Redis memory footprint grows with failure traffic. The existing
  retention (Redis TTL on `audit:log:{logId}` + sorted sets) applies
  unchanged — no separate knob for failure entries. If failure volume
  dominates (e.g. under an authz misconfiguration causing sustained
  403s), consider lowering the TTL or reviewing the traffic pattern
  rather than the audit layer.
- Grafana / Prometheus: add an alert on
  `sum(rate(cycles_admin_audit_writes_total{outcome="error"}[5m])) > 0`
  so silent audit-write failures page ops.
- **Retention defaults are SOC2-compliant out of the box**. Operators
  with different compliance regimes should set the retention properties
  (or their env-var equivalents `AUDIT_RETENTION_AUTHENTICATED_DAYS`,
  `AUDIT_RETENTION_UNAUTHENTICATED_DAYS`) before first boot. HIPAA and
  certain financial regulations require multi-year retention — set
  `audit.retention.authenticated.days=0` for indefinite, then archive
  externally on whatever cadence your policy requires. Legal hold:
  also `0`, never decrement while the hold is active.
- **DDoS exposure assessment**: if the admin endpoint is internet-
  facing with no upstream rate limiter, set `audit.sample.unauthenticated`
  to `100` (or higher) to cap write amplification from failed-auth
  floods. Aggregate attempt volume remains observable via
  `cycles_admin_audit_writes_total` — sampling reduces Redis load, not
  monitoring fidelity.

## [0.1.25.19] — 2026-04-16

### Added

- `GET /v1/auth/introspect` now accepts both `AdminKeyAuth` (X-Admin-API-Key)
  and `ApiKeyAuth` (X-Cycles-API-Key) per spec v0.1.25.15. Admin keys
  continue to return the admin-shape response (`auth_type=admin`,
  `permissions=["*"]`, all 15 capability flags true, no `tenant_id` /
  `scope_filter`). Tenant API keys now receive a tenant-shape response
  (`auth_type=tenant`, concrete permissions, `tenant_id`, optional
  `scope_filter`) with capabilities derived per the NORMATIVE capability
  table in the spec. Admin-plane capabilities (`view_tenants`,
  `view_api_keys`, `view_audit`, `view_overview`, `manage_tenants`,
  `manage_api_keys`) are forced to `false` under tenant auth regardless
  of any `admin:*` permissions on the key.
- `AuthIntrospectResponse` schema: added optional `tenant_id` and
  `scope_filter` fields (absent under admin auth, present under tenant auth).
- `Capabilities` schema: added 7 optional boolean fields
  (`view_reservations`, `manage_budgets`, `manage_policies`,
  `manage_webhooks`, `manage_tenants`, `manage_api_keys`,
  `manage_reservations`). Absent by default — legacy admin-shape responses
  from clients that don't set them stay wire-identical to pre-0.1.25.19.
- Operator docs: new [`OPERATIONS.md`](OPERATIONS.md) runbook covering
  metrics inventory, alerts, configuration tuning, and incident playbook.
- Consumer-facing [`CHANGELOG.md`](CHANGELOG.md) (this file) — summaries for
  Docker/JAR pullers; `AUDIT.md` remains the engineering-history log.

### Wire format

Additive. Admin-shape introspect responses remain byte-identical to
0.1.25.18. Tenant-shape responses and optional capability fields only
materialize under the new code paths. No breaking changes; clients built
against 0.1.25.18 parse 0.1.25.19 responses correctly.

### Notes for upgraders

- No configuration changes required. Dashboard-grade clients may now call
  `/v1/auth/introspect` with a tenant API key to render permission-gated UI;
  clients that only use the admin key see no change.
- If you're building a multi-role dashboard (admin + tenant), switch on
  `auth_type` in the response to render the right surface. Treat
  `tenant_id`/`scope_filter` as optional — absent means the caller is an
  admin.

## [0.1.25.18] — 2026-04-15

### Added

- `RESET_SPENT` funding operation on `POST /v1/admin/budgets/fund`. Closes
  the billing-period rollover gap: `RESET` resizes the allocated ceiling but
  preserves `spent`; `RESET_SPENT` additionally clears (or overrides)
  `spent` to start a fresh billing period. Optional `spent` request field
  (defaults to `0`; constrained `>= 0`) covers migration, proration,
  credit-back, and state-correction use cases.
- New `budget.reset_spent` event type, distinct from `budget.reset`.
  `EventPayloadTypeMapping` routes it to `EventDataBudgetLifecycle`.
- `BudgetFundingResponse` gains nullable `previous_spent` + `new_spent`
  fields (present on `RESET_SPENT`, absent on other operations).
- `EventDataBudgetLifecycle`: added `spent` and `reserved` to `BudgetState`;
  new optional `spent_override_provided` flag on the outer payload.

### Fixed

- Negative `spent` override rejected at both controller (Bean Validation) and
  Lua layers (`INVALID_REQUEST` → 400).

### Wire format

Additive. Existing `RESET` callers are unaffected. New enum values follow
the spec's existing extensibility policy ("consumers MUST ignore
unrecognised enum values").

### Notes for upgraders

- Operators transitioning from `RESET` for billing rollovers: switch to
  `RESET_SPENT`. `RESET`'s semantics (preserve `spent`) are unchanged and
  remain correct for ceiling-only resizes.
- Idempotency cache key versioned to `idempotency:fund:v2:`. Old v1 entries
  expire naturally within 24h; rolling deploys are safe.

## [0.1.25.17] — 2026-04-14

### Fixed

- API-key revocation list drop ([dashboard#43](https://github.com/runcycles/cycles-dashboard/issues/43)):
  `ApiKey.permissions` / `scope_filter` empty lists were serialized as
  `{}` (object) instead of `[]` (array) after Lua cjson round-trips,
  causing downstream Jackson deserialization to silently drop the record
  from `GET /v1/admin/api-keys` responses.
- Same cjson empty-array bug also closed on `Policy.caps.tool_allowlist` /
  `tool_denylist` and (defensively) on `Tenant.metadata`. All three write
  paths migrated off Lua-cjson to Jackson-in-Java read/mutate/write.
- New lenient Jackson deserializer (`LenientStringListDeserializer`) keeps
  legacy cjson-corrupted records readable — fleet self-heals on the next
  mutating admin call against each record.
- `PATCH /v1/admin/api-keys/{id}` and `POST /v1/admin/api-keys` no longer
  fail with opaque `400 Malformed request body` on unknown permission
  strings. Permissions typed `List<String>` at the DTO; validation moved
  into the repository; 400 response now names the unrecognized value.
- `revokeApiKey` on an already-revoked key now returns `409` with
  `error=KEY_REVOKED` per spec (was: `200 OK` with the stored record).

### Wire format

Additive deserializer on read; new 409 response code on a specific branch
that was previously 200. Clients that handled 200 on re-revoke idempotently
should switch to treating 409 as success-equivalent.

## [0.1.25.16] — 2026-04-13

### Added

- Dual-auth on 6 tenant-scoped webhook endpoints per spec v0.1.25.14:
  `GET /v1/webhooks`, `GET /v1/webhooks/{id}`, `PATCH /v1/webhooks/{id}`,
  `DELETE /v1/webhooks/{id}`, `POST /v1/webhooks/{id}/test`,
  `GET /v1/webhooks/{id}/deliveries`. Admin operators can pause, inspect,
  or force-delete tenant-provisioned webhooks during incident response
  without the tenant's API key. `POST /v1/webhooks` (create) intentionally
  remains tenant-only — admin-creating-on-tenant-behalf would entangle
  provenance and obscure the audit trail.

## [0.1.25.15] — 2026-04-13

### Added

- `ScopeValidator`: enforces canonical Cycles Protocol scope grammar on
  budget creation / lookup and policy scope patterns. First segment must
  be `tenant:<id>`; canonical kind order is `tenant → workspace → app →
  workflow → agent → toolset`; wildcards allowed only in policy patterns
  and only as a terminal segment.

### Fixed

- Rejects out-of-order scope segments, duplicate kinds, non-canonical kinds,
  and malformed ids with `400 INVALID_REQUEST`.

## [0.1.25.14] — 2026-04-13

### Added

- Dual-auth on `POST /v1/admin/budgets`, `POST /v1/admin/policies`, and
  `PATCH /v1/admin/policies/{id}` per spec v0.1.25.13. Admin operators can
  provision budgets and policies on behalf of tenants during onboarding /
  migration / incident response. Body MUST include `tenant_id` under admin
  auth; controllers enforce, and audit-log records `actor_type=admin_on_behalf_of`.

## [0.1.25.13] — 2026-04-13

### Fixed

- `PUT /v1/admin/config/webhook-security` now works from browser dashboards.
  CORS `allowedMethods` was missing `PUT`, so the browser preflight
  rejected the request with 403 before it reached the server
  ([dashboard#30](https://github.com/runcycles/cycles-dashboard/issues/30)).

## [0.1.25.12] — 2026-04-12

### Added

- Targeted 404/409 error responses on admin endpoints that emit them (spec
  v0.1.25.12 hardening).
- Documented 400 Bad Request on every admin operation (additive to error
  contracts).
- Spec-compliance hardening pass across 16 drift points identified in the
  prior audit (see `AUDIT.md` for the full list).

## [0.1.25.11] — 2026-04-12

### Added

- Contract testing default ON. `ContractValidationConfig` is now imported
  by every `*ControllerTest` and validates every 2xx/4xx/5xx JSON body
  against the pinned spec from `cycles-protocol@main` at build time.
  `SpecCoverageReportTest` fails the build on any spec operation with
  zero tests.

### Notes for upgraders

- Offline/air-gapped builds: set `CONTRACT_VALIDATION_ENABLED=false` or
  pass `-Dcontract.validation.enabled=false` to skip remote spec fetch.

## [0.1.25.10] — 2026-04-12

### Added

- `Permission` enum + typed `Capabilities` class. Eliminates stringly-typed
  permission handling at the auth-interceptor and introspect boundaries.

### Fixed

- Multiple spec-compliance hardening fixes across controllers and error
  contracts (see `AUDIT.md` for the full list).

[0.1.25.21]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.21
[0.1.25.20]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.20
[0.1.25.19]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.19
[0.1.25.18]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.18
[0.1.25.17]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.17
[0.1.25.16]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.16
[0.1.25.15]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.15
[0.1.25.14]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.14
[0.1.25.13]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.13
[0.1.25.12]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.12
[0.1.25.11]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.11
[0.1.25.10]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.10
