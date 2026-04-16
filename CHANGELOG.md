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
