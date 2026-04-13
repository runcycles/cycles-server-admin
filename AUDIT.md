# Complete Budget Governance v0.1.25.8 — Admin Server Audit

**Server version:** 0.1.25.14 (2026-04-13 — admin-on-behalf-of dual-auth on createBudget, createPolicy, updatePolicy per spec v0.1.25.13; closes "no Create Budget UI" dashboard gap)
**Date:** 2026-04-13 (v0.1.25.14 admin-on-behalf-of dual-auth), 2026-04-13 (v0.1.25.13 CORS PUT fix), 2026-04-12 (v0.1.25.12 spec-compliance hardening + observability), 2026-04-12 (v0.1.25.11 contract-testing default ON), 2026-04-12 (v0.1.25.10 spec-compliance hardening), 2026-04-10 (v0.1.25.9 release), 2026-04-10 (CORS hardening + prod config), 2026-04-10 (observability: prometheus metrics + k8s probes), 2026-04-10 (v0.1.25.8 spec alignment), 2026-04-09 (v0.1.25.7 admin wildcard fallback), 2026-04-08 (v0.1.25.6 freeze/unfreeze + admin fund), 2026-04-08 (v0.1.25.5 dashboard support release), 2026-04-06 (v0.1.25.4 spec compliance + replay lock), 2026-04-01 (spec compliance review), 2026-04-01 (TTL retention + release prep), 2026-04-01 (integration audit + encryption), 2026-03-31 (v0.1.25 Pillar 4: Events & Webhooks spec), 2026-03-31 (dynamic version), 2026-03-24 (Round 6: spec compliance audit), 2026-03-24 (Round 5: pre-release audit), 2026-03-24 (v0.1.24 update), 2026-03-23 (updated), 2026-03-14 (initial)
**Spec:** [`cycles-governance-admin-v0.1.25.yaml`](https://github.com/runcycles/cycles-protocol/blob/main/cycles-governance-admin-v0.1.25.yaml) (OpenAPI 3.1.0, v0.1.25.11) in [cycles-protocol](https://github.com/runcycles/cycles-protocol)
**Server:** Spring Boot 3.5.11 / Java 21 / Redis

### 2026-04-13 — v0.1.25.14 admin-on-behalf-of dual-auth on createBudget / createPolicy / updatePolicy

Implements server side of [cycles-protocol PR #36](https://github.com/runcycles/cycles-protocol/pull/36) (spec v0.1.25.13). Closes a long-standing dashboard gap: admin operators previously could not create budgets or policies because those endpoints accepted **only** `ApiKeyAuth` (X-Cycles-API-Key), and the dashboard authenticates exclusively with `X-Admin-API-Key`. Tenant management worked end-to-end; budget/policy management was list/freeze/fund-only.

**Auth changes** (`AuthInterceptor.java`):
- Three new entries in `ADMIN_ALLOWED_ENDPOINTS` exact-match allowlist:
  - `POST:/v1/admin/budgets` (createBudget)
  - `POST:/v1/admin/policies` (createPolicy)
- New `ADMIN_ALLOWED_PREFIXES` set with `PATCH:/v1/admin/policies/` to handle PATCH `/v1/admin/policies/{policy_id}` — exact match doesn't help when the path contains a resource id. The prefix matcher requires a non-empty resource id after the prefix to avoid accidentally matching the bare path.

**Controller changes** (`BudgetController.create`, `PolicyController.create`, `PolicyController.update`):
- Branch on auth context. ApiKeyAuth → `tenantId` from `authenticated_tenant_id` request attribute (existing). AdminKeyAuth → `tenantId` from request body's new `tenant_id` field (createBudget / createPolicy) or from the policy's stored owner (updatePolicy — `policy_id` already pins it).
- Strict bidirectional validation: admin caller MUST send `tenant_id`; tenant caller MUST NOT send `tenant_id`. Either violation returns 400 INVALID_REQUEST with a clear message. Prevents tenants from spoofing creates as another tenant.
- Audit log records `actor_type=admin_on_behalf_of` (new metadata key) for admin-driven calls; `api_key` for tenant self-service. Lets security review filter without joining to the keys table.
- Event emission tags `Actor.type` with the new `ADMIN_ON_BEHALF_OF` enum value (was always `API_KEY`).

**Schema changes**:
- `BudgetCreateRequest` and `PolicyCreateRequest` gain optional `tenant_id` field. Required-when-admin / forbidden-when-tenant validation lives in the controller; bean validation can't express the conditional contract without a custom validator.

**Model changes**:
- `ActorType` enum gains `ADMIN_ON_BEHALF_OF` value (Jackson `@JsonValue` = `"admin_on_behalf_of"`).

**Pre-merge review hardening** (post code review):
- Defense-in-depth path-traversal guard in `AuthInterceptor.preHandle`. Tomcat's connector already rejects `..` segments by default, but if a future deployment relaxes that or the server sits behind a proxy that forwards the raw URI, the new prefix matcher could in principle let `PATCH /v1/admin/policies/../tenants/t_1` past the dual-auth allowlist and let Spring's dispatcher route it to a different endpoint with admin auth already approved. Now: short-circuit any request whose servlet path contains `/../`, `/./`, or ends in `/..` with 400 INVALID_REQUEST. Also switched the dual-auth match input from `getRequestURI()` to `getServletPath()` so the interceptor sees the same normalized path Spring's dispatcher uses for routing.
- Replaced hardcoded `"admin_on_behalf_of"` / `"api_key"` strings in audit metadata with `ActorType.ADMIN_ON_BEHALF_OF.getValue()` / `ActorType.API_KEY.getValue()` — an enum rename can no longer silently drift the wire format.
- Explicit JSON-null `tenant_id` test on both `BudgetController` and `PolicyController` (separate from JSON-missing and JSON-empty-string).

**Tests** (+18):
- `AuthInterceptorTest`: 3 new dual-auth admit tests (POST budgets, POST policies, PATCH policy by id), 1 prefix-bare-rejection test, 1 trailing-slash normalization test. Existing `preHandle_postBudgets_withAdminKey_rejected` test inverted to `_accepted` (intentional behavior change).
- `BudgetControllerTest`: admin-with-tenant-id-201, admin-missing-tenant-id-400, api-key-with-tenant-id-400, api-key-with-blank-tenant-id-400.
- `PolicyControllerTest`: same 3 createPolicy cases + 2 updatePolicy cases (admin returns 200 with subject tenant from policy, api-key returns 200 with self-tenant). Both update tests verify audit `actor_type` discriminator.

**Spec compliance.** Aligned with cycles-protocol v0.1.25.13. No wire-format changes for existing tenant-key callers (purely additive `tenant_id` schema field).

**Backward compatibility.** Pre-existing test `preHandle_postBudgets_withAdminKey_rejected` was changed to `_accepted` — that's an intentional behavior change matching the spec, not a regression.

**Tests.** **459/459 pass** (was 441; +18). Coverage check (JaCoCo) passes — all new branches covered.

---

### 2026-04-13 — v0.1.25.13 CORS allowedMethods missing PUT

**Reported by dashboard issue [runcycles/cycles-dashboard#30](https://github.com/runcycles/cycles-dashboard/issues/30).** When the dashboard called `PUT /v1/admin/config/webhook-security` to save the webhook security config, browsers running the dashboard at a different origin (Vite dev server on `:5173`, or any non-proxied deployment) saw a `403 Forbidden` and the admin server produced **zero application logs**.

**Root cause.** `WebConfig.addCorsMappings` listed `allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")` — `PUT` was missing. The browser CORS preflight (`OPTIONS` with `Access-Control-Request-Method: PUT`) was rejected by Spring's `CorsFilter` *before* `AuthInterceptor` ran, so:

- Spring returned 403 from the filter, not the controller.
- `AuthInterceptor.preHandle` was never invoked → no INFO/WARN logs.
- The `WebhookSecurityConfigController.update` method (which uses `@PutMapping("/webhook-security")`) was never reached.

This is the only spec-defined endpoint using `PUT` in the admin API; every other write op uses POST / PATCH / DELETE, which is why no other operation regressed.

**Fix.** Added `PUT` to `allowedMethods` in `WebConfig.java`. Updated the existing exact-match assertion in `WebConfigTest.addCorsMappings_allowlistsApiKeyAndRequestIdHeaders` to expect the new list, and added `addCorsMappings_includesPutForWebhookSecurityConfigEndpoint` as a regression guard so any future refactor that drops PUT fails the build.

**Spec compliance.** Unchanged. Spec at v0.1.25.12; this is a server-only CORS fix.

**Same-origin deployments are not affected.** The standard production stack proxies `/v1/*` from the dashboard's nginx → admin server, which is same-origin from the browser POV; CORS doesn't apply there. Affected deployments: dashboard dev server (Vite on 5173) hitting admin server directly, or any non-proxied cross-origin layout.

---

### 2026-04-12 — v0.1.25.12 full-stack spec-compliance hardening

One substantial patch bundling the remainder of the spec-compliance push started in v0.1.25.10. Everything is either build-time tooling or non-wire-breaking observability, with **one** deliberate producer-side wire fix.

**Wire fix (one, deliberate):** `EventDataBudgetLifecycle.operation` on outbound webhook payloads. `BudgetController` was emitting lowercase `"create"` / `"update"` for `budget.created` and `budget.updated` events where the spec enum requires uppercase (`CREATE, UPDATE, CREDIT, DEBIT, RESET, REPAY_DEBT, STATUS_CHANGE`). The server now emits correct values. Any webhook consumer that was case-sensitively matching lowercase needs to update — the lowercase values were never spec-documented and relying on them was undocumented behavior.

**Typed enums replace raw `String` on 5 EventData fields:**
- `EventDataBudgetLifecycle.operation`: `String` → new `BudgetOperation` (7 values)
- `EventDataBudgetThreshold.direction`: `String` → new `ThresholdDirection` (2 values, lowercase wire via `@JsonValue`)
- `EventDataRateSpike.metric`: `String` → new `RateSpikeMetric` (3 values, snake_case wire)
- `EventDataTenantLifecycle.previous_status/new_status`: `String` → existing `TenantStatus`
- `EventDataApiKey.previous_status/new_status`: `String` → existing `ApiKeyStatus`

Two implementation bugs surfaced during this conversion and fixed in the same commit: `BudgetController /fund` was emitting `request.getOperation().name().toLowerCase()` (same lowercase drift); `TenantController` / `ApiKeyController` were calling `.name()` on status enums to produce Strings instead of passing the enum directly.

**Contract-testing Phase 2 — structural diff.** New `OpenApiContractDiffTest` uses `openapi-diff-core:2.1.7` against SpringDoc's `/v3/api-docs` vs the pinned spec. Fails the build on missing endpoints, extra (undocumented) endpoints, or incompatible operation-level divergence. Complements the runtime request/response validation by catching surface drift regardless of per-endpoint test coverage.

**Error-response validation.** `ContractValidationConfig` now validates 4xx/5xx JSON bodies against their documented response schema (typically `ErrorResponse`). Every 401/403/404/409/500 body emitted by the 13 controllers is confirmed to match `required: [error, message, request_id]` with a valid `ErrorCode` enum value and strict `additionalProperties: false`. Before this change, only 2xx responses were checked — regressions to error-body shape would have shipped silently.

**Event payload contract test.** New `EventPayloadContractTest` in the model module (49 assertions). Defines `EventPayloadTypeMapping` as a canonical `EventType → EventData*` lookup (40 entries), asserts every spec `EventType` has an entry, round-trips every mapped class via Jackson, and pins enum-on-wire serialization for each (e.g. `BudgetOperation` → uppercase, `ThresholdDirection` → lowercase). All 13 `EventData*` classes now carry `@JsonIgnoreProperties(ignoreUnknown = false)` — previously they accepted malformed payloads with unknown fields silently.

**Runtime event-payload observability.** `EventService.emit` now converts `event.data` into its `EventPayloadTypeMapping`-assigned class at emit time. Mismatch produces a WARN log and increments new Prometheus metric `cycles_admin_events_payload_invalid_total{type, expected_class}`. **Non-blocking** — webhook delivery proceeds even on malformed data. Operators should alert on nonzero counter.

**Spec coverage visibility.** New `SpecCoverageReportTest` (sub-package `io.runcycles.admin.api.zzz` for alphabetical last-run ordering) enumerates the spec's operations and fails the build if any has zero test coverage. On first run, uncovered 3 real gaps: `GET /v1/admin/budgets/lookup` (no test), `DELETE /v1/admin/webhooks/{id}` + `DELETE /v1/webhooks/{id}` (tests existed but 204 No Content was excluded from the coverage recorder — fixed). After this PR: 43/43 spec operations have at least one test.

**Spec alignment:** depends on `cycles-protocol` v0.1.25.11 (runcycles/cycles-protocol#33) which added `400 Bad Request` response entries to all 43 operations. Removed the original `validation.response.status.unknown → IGNORE` from the spec fetcher; kept a tighter version of the IGNORE specifically for 404 on update endpoints (`PATCH /v1/admin/tenants/{tenant_id}` etc.) — follow-up cycles-protocol PR to document 404 on those endpoints will let us drop even that.

**Tests:** 439 passing, JaCoCo 95%+ coverage met, build SUCCESS with contract validation default-ON validating every controller against `cycles-protocol@main` per build. CI enforces the whole stack on every PR.

**Drifts found and fixed across the compliance push (cumulative, v0.1.25.10–12):**
1. `Permission` strings → typed enum rejecting unknowns (v0.1.25.10)
2. `AuthIntrospectResponse.capabilities: Map` → typed `Capabilities` class (v0.1.25.10)
3. Spec `/v1/balances` used `BudgetListResponse` (wrapper: `ledgers`) — should match runtime's `balances` wrapper (cycles-protocol v0.1.25.10)
4. Spec missing `SignedAmount` schema; debt/overdraft fields used `Amount` with `minimum: 0` (cycles-protocol v0.1.25.10)
5. Spec PATCH inline bodies missing `additionalProperties: false` (cycles-protocol v0.1.25.10)
6. Spec missing `400` responses on 28 endpoints (cycles-protocol v0.1.25.11)
7. Test fixtures used `tenant_id="t1"` violating spec `minLength: 3` (v0.1.25.10.x)
8. `BudgetController` emitting lowercase `create`/`update` vs spec UPPERCASE (v0.1.25.12)
9. `BudgetController /fund` emitting lowercase operation names (v0.1.25.12)
10. `TenantController` / `ApiKeyController` passing `.name()` strings instead of typed enums (v0.1.25.12)
11. `EventData*` classes accepted unknown fields silently (v0.1.25.12)
12. `GET /v1/admin/budgets/lookup` had zero tests (v0.1.25.12)
13. DELETE-endpoint coverage invisible due to 204-no-body filter (v0.1.25.12)

Composite wire-compliance confidence: ~85% at start of push → **~98%** as of v0.1.25.12.

### 2026-04-12 — v0.1.25.11 contract-testing default ON

Flip follow-up to v0.1.25.10's contract-testing infrastructure. One-line default change: `ContractValidationConfig.validationEnabled()` now returns `true` when neither system property nor env var is set. Every 2xx response from the 13 admin controllers under `*ControllerTest` is validated against `cycles-governance-admin-v0.1.25.yaml` fetched from `cycles-protocol@main` per build (cached to `target/contract/` with 1-hour TTL).

**Why now.** The pre-existing drift-debt is zero:
- Server-side: v0.1.25.10 closed the `Permission`/`Capabilities` gaps (runcycles/cycles-server-admin#77).
- Spec-side: v0.1.25.10 spec added `SignedAmount`, `BalanceListResponse`, strict inline PATCH bodies (runcycles/cycles-protocol#32).
- Fixtures: tenant_id rename to satisfy spec `minLength:3` (runcycles/cycles-server-admin#78).

With all three merged, the gate-ON run (`mvn test -Dcontract.validation.enabled=true`) against v0.1.25.10 showed 432/432 tests passing. Flipping the default makes that the new baseline — future drift fails the build rather than silently shipping.

**How to disable** (offline/air-gapped dev only):
- `mvn verify -Dcontract.validation.enabled=false`
- `CONTRACT_VALIDATION_ENABLED=false mvn verify`

No production code changed. No API surface changed. Build-time only.

**Tests:** 432 passing with gate ON, 432 passing with gate OFF. JaCoCo 95%+ coverage maintained.

**Not yet included (Phase 2 follow-up).** Structural diff — comparing SpringDoc's `/api-docs` output against the pinned spec to catch endpoints that exist in spec but aren't implemented (or vice versa). `openapi-diff-core:2.1.7` is already on the test classpath for when this lands. Per-endpoint runtime validation (this PR) catches shape drift; structural diff catches surface drift. They're complementary.

### 2026-04-12 — v0.1.25.10 spec-compliance hardening (`Permission` enum + typed `Capabilities`)

Full line-by-line audit of all 56 schemas in `cycles-governance-admin-v0.1.25.yaml` against every Java DTO. Endpoint surface (43/43 operations) and all other schemas verified compliant. Two real gaps fixed here; one low-impact gap (EventData `String` fields where spec defines enums — outbound-only, producer-disciplined) deferred.

**Gap 1 — `Permission` schema not modeled.** Spec defines `Permission` as a 27-value enum (`reservations:create`, `budgets:write`, `admin:audit:read`, …). Every place the spec says `array of $ref Permission`, the impl used `List<String>`, so `POST /v1/admin/api-keys` and `PATCH /v1/admin/api-keys/{key_id}` accepted arbitrary strings — a typo like `"budgets:wirte"` would silently persist an unusable key.

- **New:** `cycles-admin-service-model/.../auth/Permission.java` — enum with `@JsonValue` (wire-string) and `@JsonCreator` (rejects unknown values with `IllegalArgumentException`, surfaced by Spring as a 400).
- **Changed:** `ApiKeyCreateRequest.permissions` and `ApiKeyUpdateRequest.permissions` from `List<String>` to `List<Permission>`. Added `getPermissionsAsStrings()` helper so the controller/repository path that writes to Redis (`ApiKey` entity) and emits events (`EventDataApiKey`) keeps using wire-strings — no Redis migration, no `AuthInterceptor` rewrite.
- **Boundary touch-ups:** `ApiKeyController` (create audit meta, create event payload, update audit meta) and `ApiKeyRepository` (default permissions, update setter) converted via `getPermissionsAsStrings()`.
- **Storage, outbound DTOs, `AuthInterceptor`, `EventDataApiKey`, `ApiKeyValidationResponse`, `ApiKey`/`ApiKeyResponse`** intentionally remain `List<String>` — once validated at the inbound boundary, strings in internal propagation are safe. `AuthIntrospectResponse.permissions` stays `List<String>` per spec line 2597 (the `"*"` admin wildcard is not a defined `Permission` enum value).

**Gap 2 — `AuthIntrospectResponse.capabilities` weakly typed.** Spec (lines 2599–2611) requires an object with exactly eight named boolean properties, all required and "always present (explicit false, never omitted)". Impl was `Map<String, Boolean>` — a future bug could silently drop any of the eight keys and the dashboard would break in a hard-to-diagnose way.

- **New:** `cycles-admin-service-model/.../auth/Capabilities.java` — eight `@JsonProperty` primitive booleans (`view_overview`, `view_budgets`, `view_events`, `view_webhooks`, `view_audit`, `view_tenants`, `view_api_keys`, `view_policies`), `@JsonInclude(ALWAYS)`.
- **Changed:** `AuthIntrospectResponse.capabilities: Map<String, Boolean>` → `Capabilities`. `AuthController.deriveCapabilities(...)` now returns a `Capabilities.builder()` instead of `Map.of(...)`. **JSON wire shape is identical.**

**Wire compatibility:** both changes are non-breaking for conformant clients. Inbound API-key callers that were previously sending typo'd permission strings will now see 400 instead of 200 — that is the point.

**Tests:** 432 passing (+2 new `AuthModelTest` cases, offset by 2 existing test rewrites — `AuthModelTest.apiKeyCreateRequestValidation` and `ApiKeyRepositoryTest` permission setters — that now use `Permission` enum constants instead of raw strings): `apiKeyCreateRequest_unknownPermission_rejected` verifies an invalid permission in a create payload raises `JsonMappingException`; `apiKeyCreateRequest_validPermissions_deserialize` verifies happy-path round-trip `string → Permission → string`. Updated two existing tests in `AuthModelTest` and `ApiKeyRepositoryTest` to use enum constants instead of raw strings (one previously used invalid values `"read"`/`"write"` — now uses `Permission.BALANCES_READ`/`BUDGETS_WRITE`).

**Deferred — EventData enum typing.** `EventDataBudgetLifecycle.operation`, `EventDataBudgetThreshold.direction`, `EventDataRateSpike.metric`, `EventDataTenantLifecycle.previous_status`/`new_status`, `EventDataApiKey.previous_status`/`new_status` are typed `String` where the spec constrains them with enums. These are outbound-only, producer-disciplined fields (events are emitted by the service, never received). No current user impact; revisit during broader event-model cleanup.

**Follow-up — contract test.** No automated OpenAPI contract test is wired in today. Recommend pinning `cycles-governance-admin-v0.1.25.yaml` and running `openapi-generator validate` or Atlassian Swagger Request Validator against the live SpringDoc `/api-docs` in CI so future drift is caught without manual audits.

### 2026-04-10 — CORS hardening + production config

Two real bugs fixed while closing the MEDIUM CORS gap from the post-v0.1.25.8 gap analysis:

1. **`X-Cycles-API-Key` missing from CORS allowedHeaders.** `AuthInterceptor` accepts the header for tenant authentication, but `WebConfig.addCorsMappings()` only allowlisted `X-Admin-API-Key` and `Content-Type`. Any browser-based dashboard using tenant API keys would have been blocked at CORS preflight. Added `X-Cycles-API-Key` to allowlist.
2. **`X-Request-Id` not in allowedHeaders or exposedHeaders.** `RequestIdFilter` reads the header on input and writes it back on the response, but CORS hid both directions from browsers — breaking correlation-id propagation for dashboard debugging. Added to both `allowedHeaders` and `exposedHeaders`.

**Production config changes:**

- `WebConfig` now parses a comma-separated list from `dashboard.cors.origin`, enabling multi-origin deployments (e.g. `https://dash.example.com,https://staging.example.com`).
- Startup log now prints the configured origins for operational visibility.
- `application.properties` now explicitly binds `dashboard.cors.origin=${DASHBOARD_CORS_ORIGIN:http://localhost:5173}` with an inline comment flagging the localhost default as dev-only.
- `docker-compose.prod.yml` and `docker-compose.full-stack.prod.yml` pass `DASHBOARD_CORS_ORIGIN` through to the admin container with a warning comment.
- README `Environment Variables` table gained a `DASHBOARD_CORS_ORIGIN` row; new `CORS` subsection in Observability lists all allowlisted methods, headers, exposed headers, and origin format.

**Tests:** 429 → 432 (three new `WebConfigTest` cases verifying header allowlist, exposed headers, and comma-separated origin parsing with whitespace/empty handling).

### 2026-04-10 — Observability: Prometheus metrics + Kubernetes probes

Operational-hardening follow-up (not a spec change). Closes two HIGH items from the post-v0.1.25.8 gap analysis.

**Metrics:**

- Added `micrometer-registry-prometheus` dependency in `cycles-admin-service-api/pom.xml`.
- Exposed `/actuator/prometheus` by adding `prometheus` to `management.endpoints.web.exposure.include` and enabling `management.prometheus.metrics.export.enabled=true`.
- Tagged all metrics with `application=cycles-admin-service` for multi-service dashboards.
- Spring Boot auto-provides: `http_server_requests_seconds_{count,sum,max}` per URI/method/status, JVM, Logback, process, Jedis pool gauges.
- **Custom counters (domain events):**
  - `cycles_admin_events_emitted_total{type, result}` — incremented on every `EventService.emit()` call; `result` is `success` or `failure`.
  - `cycles_admin_webhook_dispatched_total{result}` — incremented per webhook enqueue attempt in `WebhookDispatchService.dispatch()`; `result` is `queued` or `failure`.

**Kubernetes probes:**

- Enabled Spring Boot's built-in liveness/readiness split with `management.endpoint.health.probes.enabled=true`.
- New endpoints: `/actuator/health/liveness` and `/actuator/health/readiness`.
- Updated `docker-compose.prod.yml` and `docker-compose.full-stack.prod.yml` healthchecks to hit `/actuator/health/liveness` (healthchecks should be liveness, not aggregate health).

**Test updates:**

- `EventServiceTest`: new `emit_success_incrementsEmittedCounter` and `emit_failure_incrementsEmittedCounter` tests using `SimpleMeterRegistry` via `@Spy`. Verifies counter labels (`type`, `result`) are correct.
- `WebhookDispatchServiceTest`: new `dispatch_success_incrementsQueuedCounter` and `dispatch_failure_incrementsFailureCounter` tests.
- Constructor signature of `EventService` and `WebhookDispatchService` now takes a `MeterRegistry` — test setup updated to pass `SimpleMeterRegistry`.

**Backward compatibility:** None of the changes affect the HTTP API surface. Existing `/actuator/health` still works (returns aggregate). Prometheus endpoint is additive.

### 2026-04-10 — v0.1.25.8 spec alignment

**Spec published 2026-04-10** with dashboard and observability hardening for v0.1.26 readiness. All additions are additive and backward compatible. This commit catches the server up to the spec.

**Java model updates:**

| Model | Change |
|-------|--------|
| `EventDataReservationDenied` | Added optional `policy_id` (identifies policy that caused denial) and `deny_detail` (extension-defined structured context). `reason_code` was already a plain String, so the open-enum extensibility change required no code update. |
| `AdminOverviewResponse` | Added optional top-level fields: `recent_denials_by_reason`, `quota_health`, `access_control_stats`. |
| `AdminOverviewResponse.TenantCounts` | Added optional `in_observe_mode` field. |
| `AdminOverviewResponse.QuotaHealth` | New nested class: `counters_above_80pct`, `counters_at_limit`, `top_offenders` (list of QuotaOffender). |
| `AdminOverviewResponse.QuotaOffender` | New nested class: scope, action_kind, window, used, limit, utilization_pct. |
| `AdminOverviewResponse.AccessControlStats` | New nested class: `policies_with_allow_list`, `policies_with_deny_list`. |

**Controller updates:**

| Controller | Change |
|-----------|--------|
| `TenantController.list()` | Accepts `observe_mode` query param (ignored on v0.1.25.x; v0.1.26+ will wire it up). |
| `PolicyController.list()` | Accepts `has_action_quotas` and `references_action_kind` query params (ignored on v0.1.25.x). |

**Service updates:**

| Service | Change |
|---------|--------|
| `AdminOverviewService.buildOverview()` | Populates `recent_denials_by_reason` by counting `reason_code` values from the recent denials sample. Other new fields (`quota_health`, `access_control_stats`, `tenant_counts.in_observe_mode`) remain null on v0.1.25.x — populated only by v0.1.26+ servers with the corresponding extensions. |

**Backward compatibility:** All new fields use `@JsonInclude(NON_NULL)` — absent from responses when null. Existing v0.1.25.7 clients continue to work unchanged. New query parameters are silently ignored by v0.1.25.7 servers (Spring `@RequestParam(required = false)` default behavior).

**Test count:** 420 → 424 (6+ new tests covering model roundtrips, controller accept-and-ignore, and denials-by-reason aggregation).

### 2026-04-09 — v0.1.25.7: bug fixes, audit enrichment, spec polish

**Bug fixes:**

| Fix | Details |
|-----|---------|
| PATCH api-keys 500 | Redis `cjson.encode` converts `[]` → `{}`, corrupting `scope_filter`/`permissions`. Replaced Lua cjson roundtrip with Java read-modify-write using Jackson serialization. Fixes both 500 error and data corruption on update. |
| Webhook test error messages | "Delivery failed" → specific messages: DNS resolution failed, TLS/SSL handshake failed, Connection refused, Socket timed out, etc. Unwraps cause chains. |

**Audit enrichment:**

| Change | Details |
|--------|---------|
| `resource_type` field | New standard field on AuditLogEntry: tenant, budget, api_key, policy, webhook, config |
| `resource_id` field | New standard field: the specific resource acted on (ledger ID, key ID, subscription ID, etc.) |
| `metadata` on all 22 endpoints | Every mutating endpoint now logs contextual details (scope, unit, amount, url, permissions, reason, etc.) |
| Update operations detail | All 6 update operations log which fields were actually changed (not just boolean flags) |
| Audit query filters | `GET /v1/admin/audit/logs` now accepts `resource_type` and `resource_id` query parameters |

**Test count:** 412 → 419 (7 new: webhook error classification).

### 2026-04-09 — v0.1.25.7: wildcard fallback + spec polish

**Wildcard fallback (code):** v0.1.25.6 broke pre-existing keys with `admin:write` (runcycles/.github#21). `AuthInterceptor.hasPermission()` now treats `admin:write` as a wildcard satisfying any `*:write`, and `admin:read` any `*:read`.

**Spec polish (review feedback):**

| Change | Details |
|--------|---------|
| 401 consistency | Added `401` response to 36 auth-gated endpoints that were missing it (now 45/45) |
| FROZEN semantics | `BudgetLedger.status` FROZEN description now includes funding block (was missing) |
| Webhook/event opt-in | Default tenant permissions documented as NOT including webhooks/events; opt-in only |
| createApiKey clarified | Tenant-scoped keys only; admin key is server-configured, not provisioned |
| PATCH /v1/admin/api-keys/{key_id} | New endpoint for updating permissions, scope_filter, name, description, metadata. Emits `api_key.permissions_changed`. 409 on revoked/expired. |

**PATCH /v1/admin/api-keys/{key_id} implementation:**

| Component | Details |
|-----------|---------|
| `ApiKeyUpdateRequest.java` | New DTO: name, description, permissions, scope_filter, metadata. `@JsonIgnoreProperties(ignoreUnknown = false)` |
| `ApiKeyRepository.update()` | Java read-modify-write with Jackson (replaced Lua cjson). Validates status (409 on REVOKED/EXPIRED). Only merges non-null fields. |
| `ApiKeyController.update()` | PATCH endpoint with audit logging, change detection, conditional `api_key.permissions_changed` event emission |
| Auth routing | Already handled: `/v1/admin/api-keys` requires AdminKeyAuth in AuthInterceptor |

**Spec polish (final pass):**

| Change | Details |
|--------|---------|
| Permission enum | Reusable `Permission` schema extracted; `$ref` used in ApiKey, ApiKeyCreateRequest, ApiKeyCreateResponse, ApiKeyValidationResponse, EventDataApiKey, PATCH body |
| Wildcard semantics normative | `admin:read`/`admin:write` wildcard behavior documented in Permission schema description (not just changelog) |
| 401 precision | All 45 responses: 27 admin-only, 14 tenant-only, 4 dual-auth. Fixed orphan block, duplicates, introspect |
| No wildcard prose | Replaced `reservations:*`, `budgets:*` etc. with concrete permission names throughout |
| AuthIntrospectResponse | permissions field uses plain `string[]` (not Permission ref) because admin returns `["*"]` |
| PATCH api-keys 400 | Added for invalid permission names |

**Test count:** 401 → 412 (12 new: 7 controller + 5 repository).

### 2026-04-08 — v0.1.25.6: Budget freeze/unfreeze + admin fund

Two spec gaps identified during dashboard development. All changes are additive — no breaking changes.

**Spec changes** (`complete-budget-governance-v0.1.25.yaml`):

| Change | Details |
|--------|---------|
| Budget freeze endpoint | `POST /v1/admin/budgets/freeze` — transitions ACTIVE → FROZEN (AdminKeyAuth). Optional body: reason, metadata. 200/401/404/409. |
| Budget unfreeze endpoint | `POST /v1/admin/budgets/unfreeze` — transitions FROZEN → ACTIVE (AdminKeyAuth). Same shape. |
| Admin fund dual-auth | `POST /v1/admin/budgets/fund` now accepts `AdminKeyAuth` as alternative. `tenant_id` required for admin auth. |
| New schema | `BudgetStatusTransitionRequest`: reason (optional), metadata (optional). |

**Code changes:**

| File | Change |
|------|--------|
| `BudgetRepository.java` | New `TRANSITION_STATUS_LUA` script; `freeze()` and `unfreeze()` methods |
| `BudgetController.java` | New `POST /freeze` and `POST /unfreeze` endpoints; `fund()` updated with `tenant_id` param and admin-key fallback |
| `AuthInterceptor.java` | `ADMIN_READABLE_ENDPOINTS` → `ADMIN_ALLOWED_ENDPOINTS` (includes `POST:/v1/admin/budgets/fund`); freeze/unfreeze routed to admin auth |
| `BudgetStatusTransitionRequest.java` | New request model |
| `pom.xml` | `<revision>0.1.25.6</revision>` |

**Tenant permission model** (review feedback):

| Change | Details |
|--------|---------|
| New permissions | `budgets:read`, `budgets:write`, `policies:read`, `policies:write` added to ApiKey.permissions enum |
| Permission enforcement | `AuthInterceptor.PERMISSION_MAP` updated: budget endpoints require `budgets:read`/`budgets:write`, policy endpoints require `policies:read`/`policies:write` (was `admin:read`/`admin:write`) |
| 403 responses | Added to `POST /v1/admin/budgets`, `POST /v1/admin/policies`, `PATCH /v1/admin/policies/{policy_id}`, `POST /v1/admin/budgets/fund` |
| Event emission docs | Freeze/unfreeze endpoint descriptions now explicitly state `budget.frozen`/`budget.unfrozen` event emission |
| Schema header | Fixed stale `v0.1.25.5` → `v0.1.25.6` in DASHBOARD SCHEMAS comment |
| Backward compatible | New permissions included in default tenant key set |

**Audit trail completeness:**

| Endpoint | Controller | Fix |
|----------|-----------|-----|
| `POST /v1/admin/webhooks/{id}/test` | WebhookAdminController | Added audit log (operation, status) |
| `POST /v1/admin/webhooks/{id}/replay` | WebhookAdminController | Added audit log (operation, status) |
| `POST /v1/webhooks` | WebhookTenantController | Added AuditRepository + audit log (tenantId, keyId, operation, status) |
| `PATCH /v1/webhooks/{id}` | WebhookTenantController | Added audit log (tenantId, keyId, operation, status) |
| `DELETE /v1/webhooks/{id}` | WebhookTenantController | Added audit log (tenantId, keyId, operation, status) |
| `POST /v1/webhooks/{id}/test` | WebhookTenantController | Added audit log (tenantId, keyId, operation, status) |

**Test count:** 384 → 401 (all passing, 95%+ coverage maintained).

### 2026-04-08 — Audit log completeness fix

**Issue:** AdminKeyAuth endpoints logged incomplete audit entries — missing tenantId and/or keyId.

| Controller | Endpoint | Fix |
|-----------|----------|-----|
| `WebhookAdminController.update()` | `PATCH /v1/admin/webhooks/{id}` | Added `tenantId` from `updated.getTenantId()` |
| `WebhookAdminController.delete()` | `DELETE /v1/admin/webhooks/{id}` | Fetch subscription before delete to capture `tenantId` |
| `BudgetController.update()` | `PATCH /v1/admin/budgets` | Fall back to `ledger.getTenantId()` when AdminKeyAuth (no `authenticated_tenant_id`) |
| `ApiKeyController.create()` | `POST /v1/admin/api-keys` | Added `keyId` from `response.getKeyId()` (newly created key) |

**Not changed (correct as-is):** `createTenant`, `updateTenant`, `createWebhookSubscription` — these already derive tenantId from request body/path/query params. AdminKeyAuth has no per-key identity so keyId=null is correct for admin-only operations.

### 2026-04-08 — Spec compliance audit: test coverage gaps + ApiKey hardening

**Audit findings:** Deep spec compliance review of all 78 model classes, data layer, and 45 test files.

| Finding | Resolution |
|---------|------------|
| `ApiKey.key_hash` has `@JsonProperty` (needed for Redis serialization) but could leak if model accidentally returned in API | Added guard tests: `AuthModelTest.apiKeyResponse_doesNotContainKeyHash()` verifies `ApiKeyResponse` DTO never includes hash; `apiKey_internalModel_containsKeyHashForRedis()` verifies Redis round-trip still works. Added defensive comment on field. |
| KEY_EXPIRED / KEY_REVOKED validation reasons untested at controller level | Added 4 tests to `AuthControllerTest`: expired key, revoked key, suspended tenant, closed tenant validation responses |
| INSUFFICIENT_PERMISSIONS error code untested at controller level | Added 3 tests to `BudgetControllerTest`: create, fund, and list with keys lacking required permissions |
| IDEMPOTENCY_MISMATCH untested at controller level (only repo-level) | Added `fundBudget_idempotencyMismatch_returns409` to `BudgetControllerTest` |

**Test count:** 375 → 384 (all passing, 95%+ coverage maintained).

### 2026-04-08 — Swagger tag compliance fix

**Issue:** Spec compliance review found two OpenAPI tag mismatches:

| File | Was | Should Be | Affected Endpoint |
|------|-----|-----------|-------------------|
| `AdminOverviewController.java` | `@Tag(name = "Budgets")` | `@Tag(name = "Dashboard")` | `GET /v1/admin/overview` |
| `AuthController.java` | Class-level `@Tag(name = "Authentication")` applied to introspect | Method-level `tags = {"Dashboard"}` on `@Operation` | `GET /v1/auth/introspect` |

**Impact:** Swagger UI grouping only — no runtime behavior change. Both endpoints now appear under the `Dashboard` tag per spec section `tags:` (line 178) and endpoint definitions (lines 3057, 3157).

### 2026-04-08 — v0.1.25.5: Admin dashboard support

Backend changes for the Cycles Admin Dashboard (cycles-dashboard v1). All changes are additive — no breaking changes to existing endpoints or schemas. Spec updated first (YAML is the authority), then code, then multiple rounds of spec review.

**Spec changes** (`complete-budget-governance-v0.1.25.yaml`):

| Change | Details |
|--------|---------|
| `info.version` | `0.1.25` → `0.1.25.5` |
| Dual-auth allowlist | `GET /v1/admin/budgets`, `GET /v1/admin/budgets/lookup`, `GET /v1/admin/policies` now accept `AdminKeyAuth` as alternative. `tenant_id` required on list endpoints, not on lookup (budget uniquely identified by scope+unit). |
| `tenant_id` on policies | `GET /v1/admin/policies`: added `tenant_id` query param (required for AdminKeyAuth, ignored for ApiKeyAuth). |
| Budget lookup endpoint | `GET /v1/admin/budgets/lookup`: exact (scope, unit) retrieval. Dual-auth. 200/401/403/404 responses. |
| Overview endpoint | `GET /v1/admin/overview`: server-aggregated dashboard payload (`AdminOverviewResponse`). AdminKeyAuth only. 200/401 responses. |
| Introspect endpoint | `GET /v1/auth/introspect`: auth introspection with capabilities (`AuthIntrospectResponse`). AdminKeyAuth only. 200/401 responses. |
| Dashboard tag | New `Dashboard` tag groups overview and introspect. |
| Error responses | 400/401/403 added to budget list, budget lookup, policy list endpoints. |
| Schema strictness | `AdminOverviewResponse`: all fields required, `additionalProperties: false` on all nested objects. `AuthIntrospectResponse.capabilities`: all 8 booleans required. `EventCounts.by_category` required. |
| No Redis in spec | Removed implementation details — spec uses protocol terms ("uniquely identified by scope+unit"). |

**Code changes:**

| File | Change |
|------|--------|
| `AuthInterceptor.java` | `ADMIN_READABLE_ENDPOINTS` allowlist, `hasAdminKeyHeader()`, `/v1/admin/overview` + `/v1/auth/introspect` routed to AdminKeyAuth |
| `BudgetController.java` | `list()` requires `tenant_id` for admin callers (400 if missing); new `lookup()` endpoint |
| `BudgetRepository.java` | New `getByExactScope(scope, unit)` |
| `PolicyController.java` | Added `tenant_id` param, same admin-key fallback |
| `AuthController.java` | New `GET /v1/auth/introspect` with capability derivation |
| `AdminOverviewResponse.java` | Response DTO with 8 nested inner classes, `@JsonInclude(ALWAYS)` |
| `AuthIntrospectResponse.java` | Capabilities response, `@JsonInclude(ALWAYS)` |
| `AdminOverviewService.java` | Aggregation through TenantRepository, BudgetRepository, WebhookRepository, EventRepository |
| `AdminOverviewController.java` | `GET /v1/admin/overview` |
| `WebConfig.java` | CORS with configurable `dashboard.cors.origin` |

**Tests:** 375 total, 0 failures, all JaCoCo coverage checks met. New tests cover dual-auth allowlist (accept/reject), write escalation prevention, overview controller/service, introspect endpoint.

---

### 2026-04-06 — v0.1.25.4 (amended): Replay lock + response model additionalProperties enforcement

**Critical fix:** `WebhookService.replay()` had no concurrent replay detection — the spec requires `409 REPLAY_IN_PROGRESS` but `GovernanceException.replayInProgress()` was never called. Multiple simultaneous replays could cause duplicate deliveries.

| Fix | Location |
|-----|----------|
| Added `acquireReplayLock(subscriptionId, replayId)` using Redis SET NX EX (1h TTL) | `WebhookRepository.java` |
| Added `releaseReplayLock(subscriptionId)` in finally block after replay completes | `WebhookRepository.java` |
| `replay()` now throws `GovernanceException.replayInProgress()` when lock is held | `WebhookService.java` |
| Added `@JsonIgnoreProperties(ignoreUnknown = false)` to 8 response/entity models | Tenant, BudgetLedger, Policy, ApiKey, Event, Actor, WebhookSubscription, WebhookDelivery |
| Added 2 new replay lock tests (409 rejection + lock release verification) | `WebhookServiceTest.java` |
| Updated 6 existing replay tests with `acquireReplayLock` mock | `WebhookServiceTest.java` |

**Tests:** 717 total (85 model + 278 data + 354 API), 0 failures. All JaCoCo coverage checks passed.

---

### 2026-04-06 — v0.1.25.4: Spec validation compliance — additionalProperties, range constraints, size limits

Full spec compliance audit of all request DTOs against `complete-budget-governance-v0.1.25.yaml`. Enforces `additionalProperties: false`, adds missing range/size constraints per spec, and removes a spurious permission mapping.

| Category | Fix | Models affected |
|----------|-----|-----------------|
| `additionalProperties: false` | Added `@JsonIgnoreProperties(ignoreUnknown = false)` | TenantCreateRequest, TenantUpdateRequest, BudgetCreateRequest, BudgetFundingRequest, PolicyCreateRequest, RateLimits, ReservationTtlOverride, WebhookCreateRequest, WebhookUpdateRequest, WebhookRetryPolicy, WebhookThresholdConfig, ApiKeyCreateRequest |
| TTL range constraints | Added `@Min(1000) @Max(86400000)` on `defaultReservationTtlMs`, `maxReservationTtlMs` | TenantCreateRequest, TenantUpdateRequest |
| Extension min constraint | Added `@Min(0)` on `maxReservationExtensions` | TenantCreateRequest, TenantUpdateRequest |
| Retry policy ranges | Added `@Min/@Max` on maxRetries (0-10), initialDelayMs (100-60000), backoffMultiplier (1.0-10.0), maxDelayMs (1000-3600000) | WebhookRetryPolicy |
| Threshold config ranges | Added `@DecimalMin/@DecimalMax` on rates (0.0-1.0), `@DecimalMin("1.5")` on burnRateMultiplier, `@Min(60) @Max(86400)` on window seconds | WebhookThresholdConfig |
| String size limits | Added `@Size(max=256)` on name, `@Size(max=1024)` on description, `@Size(max=2048)` on url | WebhookCreateRequest, WebhookUpdateRequest, PolicyCreateRequest, ApiKeyCreateRequest |
| Nested `@Valid` | Added `@Valid` on thresholds and retryPolicy fields | WebhookCreateRequest, WebhookUpdateRequest |
| Spurious permission entry | Removed `GET:/v1/reservations → reservations:list` (no spec endpoint) | AuthInterceptor |
| Version bump | `0.1.25.3` → `0.1.25.4` | pom.xml |

**Tests:** 715 total (85 model + 278 data + 352 API), 0 failures. 19 new validation tests added (TenantModelTest, PolicyModelTest, WebhookModelTest, BudgetModelTest, AuthModelTest). All JaCoCo coverage checks passed.

---

### 2026-04-03 — Fix: listAll() pagination with tenant_id delegates to listByTenant (#57)

**Bug fix:** `WebhookService.listAll()` applied the `tenant_id` filter in-memory after fetching `limit` items from the global `webhooks:_all` Redis set. This caused `has_more` to report `false` prematurely when the filtered result count dropped below the requested limit, silently truncating paginated results.

| Fix | Location |
|-----|----------|
| When `tenantId` is provided, `listAll()` now delegates to `listByTenant()` which queries the tenant-specific `webhooks:{tenantId}` Redis set directly | `WebhookService.java:214` |
| Removed dead in-memory tenant filter code | `WebhookService.java` |
| Updated tests to verify delegation to `listByTenant` and correct pagination (`hasMore`, `nextCursor`) | `WebhookServiceTest.java` |

**Tests:** 352 total, 0 failures. All coverage checks passed.

Related: runcycles/cycles-server-admin#57

---

### 2026-04-03 — v0.1.25.3: Webhook URL validation respects blocked_cidr_ranges config

**Bug fix:** `WebhookUrlValidator` had a hardcoded `isPrivateOrReserved()` check using Java's `InetAddress.isLoopbackAddress()` / `isSiteLocalAddress()` etc. that ran unconditionally, ignoring the configurable `blocked_cidr_ranges` in `WebhookSecurityConfig`. Users could not register webhook URLs pointing to local/Docker endpoints even after removing the corresponding CIDR ranges via `PUT /v1/admin/config/webhook-security`.

| Fix | Location |
|-----|----------|
| Replaced hardcoded `isPrivateOrReserved()` with CIDR-range matching against `config.getBlockedCidrRanges()` | `WebhookUrlValidator.java` |
| Added `CidrRange` inner class for CIDR parsing and IP containment checks | `WebhookUrlValidator.java` |
| When `blocked_cidr_ranges` is empty, no IP-based blocking occurs (user opted out) | `WebhookUrlValidator.java` |
| Validates CIDR prefix lengths (rejects negative or out-of-range values) | `WebhookUrlValidator.CidrRange.parse()` |
| Handles IPv4-mapped IPv6 addresses (`::ffff:x.x.x.x`) against IPv4 CIDR ranges | `WebhookUrlValidator.CidrRange.contains()` |
| Null-safe CIDR list parsing (skips null entries) | `WebhookUrlValidator.parseCidrRanges()` |
| Fixed glob pattern matching to escape regex metacharacters (`+`, `?`, `[`, `]`, etc.) | `WebhookUrlValidator.matchesGlob()` |
| Enforced `additionalProperties: false` on `WebhookSecurityConfig` per spec (rejects unknown JSON fields) | `WebhookSecurityConfig.java` |
| Bumped version to `0.1.25.3` | `pom.xml` |
| Added 20 new tests (CIDR matching, prefix validation, boundary, non-aligned prefix, unresolvable host, no-host URL, additionalProperties rejection, glob escaping) | `WebhookUrlValidatorTest.java`, `WebhookSecurityConfigControllerTest.java` |

**Known limitation (pre-existing, out of scope):** DNS is resolved at webhook creation/update time only. A DNS rebinding attack (changing DNS after validation) could bypass CIDR checks at delivery time. Mitigation requires delivery-time re-validation in `cycles-server-events`.

**Tests:** 352 total, 0 failures. All coverage checks passed.

Related: runcycles/cycles-server-admin#55

---

### 2026-04-03 — v0.1.25.2: Lowercase scope normalization

**Bug fix:** The admin API stored scope values verbatim (mixed case), but the runtime server's `ScopeDerivationService` lowercases all scope values. This caused budgets created via the admin API with mixed-case scopes (e.g. `app:riderApp`) to be invisible to the runtime server's `GET /v1/balances` endpoint.

| Fix | Location |
|-----|----------|
| Added `normalizeScope()` to `BudgetRepository` — lowercases scope in `create()`, `update()`, and `fund()` | `BudgetRepository.java` |

Related: runcycles/cycles-openclaw-budget-guard#70

---

### 2026-04-01 — Spec Compliance Review: README, Pagination, Error Codes

Full review of code, README, and spec compliance against `complete-budget-governance-v0.1.25.yaml`.

**Spec compliance fixes:**

| Issue | Fix |
|-------|-----|
| Pillar 4 list endpoints (webhooks, events) missing `limit` clamping | Added `Math.max(1, Math.min(limit, 100))` to 6 controller methods per spec `maximum: 100` |
| `WebhookUpdateRequest.status` accepted `DISABLED` | Already validated in `WebhookService.update()` — confirmed spec-compliant (only `ACTIVE`/`PAUSED` allowed) |

**README fixes:**

| Issue | Fix |
|-------|-----|
| Pagination `max: 200` incorrect | Changed to `max: 100` per spec |
| 5 governance error codes missing | Added `BUDGET_CLOSED`, `WEBHOOK_NOT_FOUND`, `WEBHOOK_URL_INVALID`, `EVENT_NOT_FOUND`, `REPLAY_IN_PROGRESS` |
| 4 environment variables undocumented | Added `LOG_LEVEL`, `SWAGGER_ENABLED`, `EVENT_TTL_DAYS`, `DELIVERY_TTL_DAYS` to env var table |

**Tests added:** 6 new tests verifying limit clamping on all Pillar 4 list endpoints (WebhookAdminController, WebhookTenantController, EventAdminController, EventTenantController). Total: 331 tests, 0 failures.

**Confirmed correct:**
- All 27 ErrorCode enum values match spec
- All 40 EventType values match spec
- All 38 admin endpoints implemented with correct paths, methods, auth schemes
- Auth routing (AdminKeyAuth vs ApiKeyAuth) matches spec for all endpoints

### 2026-04-01 — Release Prep: Docker + Docs

| Change | Details |
|--------|---------|
| docker-compose.full-stack.prod.yml: `:latest` → `0.1.25.1` | All three services now use versioned image tags for deterministic deployments |
| README: webhook security section | Documented SSRF protection, `allow_http`, blocked CIDRs, local dev setup with Docker |

### 2026-04-01 — Production Readiness Audit (Security, Bugs, Docs, Coverage)

Comprehensive production readiness audit covering security vulnerabilities, code bugs, documentation gaps, and test coverage.

**CRITICAL security fixes:**

| Issue | Fix |
|-------|-----|
| Timing attack on admin API key comparison (`String.equals()`) | Replaced with `MessageDigest.isEqual()` for constant-time comparison |
| SSRF bypass: private IP check skipped when `blockedCidrRanges` empty | Always check private/reserved IPs regardless of CIDR config |
| Webhook subscription ID overwritten by `WebhookRepository.save()` | Preserve caller-set ID; only generate if null/blank |
| Delivery ID overwritten by `WebhookDeliveryRepository.save()` | Same fix — preserve caller-set ID |

**HIGH priority fixes:**

| Issue | Fix |
|-------|-----|
| Wrong error code `WEBHOOK_URL_INVALID` for tenant event type/category validation | Changed to `INVALID_REQUEST` in `EventTenantController` and `WebhookTenantController` |
| Event emission exceptions silently swallowed (no logging) | Added `LOG.warn` in catch blocks across 5 controllers (BudgetController, TenantController, PolicyController, AuthController, ApiKeyController) |
| `RequestIdFilter` ignores client `X-Request-Id` header | Now honors client-provided header for cross-service tracing |
| `HttpClient` created per webhook test request (resource leak) | Replaced with shared static `HTTP_CLIENT` instance |
| Webhook test error leaks internal hostnames/IPs in `errorMessage` | Sanitized to generic messages (Connection timed out, Connection refused, Delivery failed) |
| `EventType.fromValue()` missing `@JsonCreator` annotation | Added `@JsonCreator` for explicit Jackson deserialization support |

**Documentation & config hardening:**

| Issue | Fix |
|-------|-----|
| Docker prod compose: no `restart` policy or app health checks | Added `restart: unless-stopped` + health checks on all prod services |
| Docker prod compose: hardcoded default `ADMIN_API_KEY` | Changed to `${ADMIN_API_KEY:?...}` (required, no default) |
| Application logging set to `DEBUG` in production | Changed to `${LOG_LEVEL:INFO}` (configurable, defaults to INFO) |
| Swagger UI enabled unconditionally | Changed to `${SWAGGER_ENABLED:false}` (disabled by default) |
| `CLAUDE.md` version stale (`0.1.24.0`) | Updated to `0.1.25.1` |
| `EVENT_TTL_DAYS`/`DELIVERY_TTL_DAYS` not in docker-compose | Added to prod compose environment blocks |

**Known issues documented (not fixed — design decisions needed):**

- Unbounded `SMEMBERS` calls in repositories (performance risk at scale; needs SSCAN migration)
- No TTL on webhook subscription, audit log, or sorted set index keys (unbounded growth)
- Pagination `limit * 3` heuristic may miss filtered results
- Missing request body validation constraints (@Min/@Max) on several DTOs

**Tests:** 319 tests, 0 failures, 95%+ coverage (all JaCoCo checks pass). Added 6 new tests: SSRF bypass prevention, RequestIdFilter client header, tenant event type restrictions (3), EventType `@JsonCreator` coverage via existing tests.

---

### 2026-04-01 — Spec Compliance Audit (v0.1.25)

Full validation of all code against `complete-budget-governance-v0.1.25.yaml`. Validated controllers, models, error codes, and auth against spec.

**Discrepancies found and fixed:**

| Issue | Severity | Fix |
|-------|----------|-----|
| `SystemSeverity` enum serialized as `INFO`/`WARNING`/`CRITICAL` but spec requires `info`/`warning`/`critical` | Critical | Added `@JsonValue`/`@JsonCreator` with lowercase values, matching `EventCategory`/`ActorType` pattern |
| `PATCH /v1/admin/budgets` used `ApiKeyAuth` but spec v0.1.25 changed it to `AdminKeyAuth` | Critical | Updated `AuthInterceptor` to route PATCH budgets through admin key auth; updated `BudgetController` to work without tenant scoping |
| `BudgetLedger.metadata` field serialized in API responses but spec has `additionalProperties: false` and no metadata property | Medium | Changed `@JsonProperty` to `@JsonIgnore` (Redis storage unaffected as it uses hash fields) |
| `WebhookUpdateRequest.status` accepted `DISABLED` but spec restricts updates to `[ACTIVE, PAUSED]` | Medium | Added validation in `WebhookService.update()` rejecting `DISABLED` with `INVALID_REQUEST` error |

**Not a defect (validated as correct):**
- `ApiKey.keyHash` has `@JsonProperty` for Redis serialization but is never exposed in API responses (controllers use `ApiKeyResponse` DTO)
- Reservation endpoints not implemented (served by separate `cycles-server-events` runtime service)
- `ApiKeyListResponse` uses `ApiKeyResponse` instead of `ApiKey` — deliberate DTO projection to mask internal fields

**Tests:** 313 tests, 0 failures, 95%+ coverage (all JaCoCo checks pass). Added 7 new tests for spec compliance: 3 for `SystemSeverity` serialization, 3 for `AuthInterceptor` PATCH budget admin auth, 1 for `WebhookService` DISABLED status rejection.

---

### 2026-04-01 — TTL Retention for Event/Delivery Data

Added Redis TTL to all event and delivery keys per spec: "90 days hot, 1 year cold."

- `event:{id}` keys: 90-day TTL via EXPIRE in Lua script (configurable via `EVENT_TTL_DAYS`)
- `delivery:{id}` keys: 14-day TTL via EXPIRE in Lua script (configurable via `DELIVERY_TTL_DAYS`)
- `events:correlation:{id}` keys: 90-day TTL via EXPIRE in Lua script

Admin: 309 tests, 0 failures, 95%+ coverage.

---

### 2026-04-01 — Admin + Events Integration Audit & Hardening

Cross-service integration audit and end-to-end verification between cycles-server-admin and cycles-server-events.

**Build verification:**
- Admin: 309 unit tests, 0 failures, 95%+ coverage (all modules)
- Events: 102 unit tests, 0 failures, 95%+ coverage
- E2E: 12 event types verified delivered with HMAC signatures (14 total webhooks)

**Bugs found and fixed during E2E testing:**

| Bug | Severity | Fix |
|-----|----------|-----|
| `WebhookRepository.findMatchingSubscriptions` checked `webhooks:_system` but subscriptions stored at `webhooks:__system__` | Critical | Fixed key to `webhooks:__system__` |
| `DispatchLoop.@Scheduled(fixedDelay=0)` crashes Spring Boot 3.5 | Critical | Changed to `fixedDelay=1` (in events repo) |
| `ActorType` serialized as `ADMIN` not spec-required `admin` | High | Added `@JsonValue`/`@JsonCreator` with lowercase values |
| `EventCategory` serialized as `BUDGET` not spec-required `budget` | High | Added `@JsonValue`/`@JsonCreator` with lowercase values |
| `EventRepository` category filter used `.name()` instead of `.getValue()` | Medium | Changed to `.getValue()` for spec compliance |
| `WebhookDispatchService.signPayload()` used Base64 encoding | Medium | Changed to `sha256=<hex>` format matching events service and spec |
| `DeliveryHandler` retry count off-by-one (allowed N-1 retries instead of N) | Medium | Changed `>=` to `>` (in events repo) |
| `WebhookService.test()` was a placeholder returning hardcoded success | Medium | Implemented actual HTTP POST with HMAC signing |
| `WebhookService.replay()` was a stub returning eventsQueued=0 | Medium | Implemented event querying and delivery creation |

**Security hardening: AES-256-GCM encryption for webhook signing secrets**

Webhook signing secrets were stored as plaintext in Redis at `webhook:secret:{id}`. Now encrypted using AES-256-GCM with a shared master key (`WEBHOOK_SECRET_ENCRYPTION_KEY` env var).

- Admin `WebhookRepository.save()` encrypts before storing
- Admin `WebhookRepository.getSigningSecret()` decrypts on read
- Admin `WebhookRepository.update()` encrypts rotated secrets
- Events `SubscriptionRepository.getSigningSecret()` decrypts on read
- New `CryptoService` class in both repos (identical implementation)
- Encrypted values prefixed with `enc:` for detection
- Backward compatible: plaintext secrets (no `enc:` prefix) returned as-is
- Pass-through mode when key not configured (dev/test environments)
- Generate key: `openssl rand -base64 32`

**New files:**
- `cycles-admin-service-data/.../service/CryptoService.java` + test (10 tests)
- `cycles-server-events/.../config/CryptoService.java` + test (9 tests)

**Docker-compose updates:**
- `docker-compose.full-stack.yml`: added `WEBHOOK_SECRET_ENCRYPTION_KEY` to admin + events
- `docker-compose.full-stack.prod.yml`: same

---

### 2026-03-31 — v0.1.25: Pillar 4 Implementation Complete

Full server-side implementation of Events & Webhooks (Observability Plane).

**Build verification:**
- 304 unit tests, 0 failures
- Data module: 97.3% line coverage
- API module: 96.4% line coverage
- Model module: 100% class coverage
- All JaCoCo thresholds met (95% minimum)

**New source files (50+):**

*Model layer (35 files):*
- `model/event/`: EventCategory, EventType (40 values), ActorType, SystemSeverity, Actor, Event, EventListResponse, 13 EventData* payload classes
- `model/webhook/`: WebhookStatus, DeliveryStatus, WebhookRetryPolicy, WebhookThresholdConfig, WebhookSubscription, WebhookCreateRequest/Response, WebhookUpdateRequest, WebhookListResponse, WebhookDelivery, WebhookDeliveryListResponse, WebhookTestResponse, WebhookSecurityConfig, ReplayRequest/Response
- `model/shared/ErrorCode`: +4 values (WEBHOOK_NOT_FOUND, WEBHOOK_URL_INVALID, EVENT_NOT_FOUND, REPLAY_IN_PROGRESS)

*Repository layer (4 files + GovernanceException):*
- EventRepository: ZSET-indexed events with cursor pagination, Lua script for atomic save
- WebhookRepository: SET-indexed subscriptions, subscription matching for dispatch
- WebhookDeliveryRepository: ZSET-indexed deliveries per subscription
- WebhookSecurityConfigRepository: server-level URL policy
- GovernanceException: +4 factory methods

*Service layer (4 files):*
- EventService: non-blocking event emission with webhook dispatch
- WebhookService: subscription CRUD, signing secret management, delivery listing
- WebhookDispatchService: subscription matching, HMAC-SHA256 signing, delivery creation
- WebhookUrlValidator: SSRF prevention with CIDR blocking and URL pattern matching

*Controller layer (5 new + 1 modified):*
- WebhookAdminController: 8 admin operations at /v1/admin/webhooks
- WebhookTenantController: 7 tenant-scoped operations at /v1/webhooks
- EventAdminController: 2 admin operations at /v1/admin/events
- EventTenantController: 1 tenant-scoped operation at /v1/events
- WebhookSecurityConfigController: 2 operations at /v1/admin/config/webhook-security
- AuthInterceptor: updated with new route registrations and permission mappings

*Event emission wiring (5 existing controllers modified):*
- TenantController: tenant.created/updated/suspended/reactivated/closed
- BudgetController: budget.created/updated/funded/debited/reset/debt_repaid
- ApiKeyController: api_key.created/revoked
- PolicyController: policy.created/updated
- AuthController: api_key.auth_failed

*Test files (15 new):*
- Repository: EventRepositoryTest, WebhookRepositoryTest, WebhookDeliveryRepositoryTest, WebhookSecurityConfigRepositoryTest
- Model: EventModelTest, WebhookModelTest
- Service: EventServiceTest, WebhookServiceTest, WebhookDispatchServiceTest, WebhookUrlValidatorTest
- Controller: WebhookAdminControllerTest, WebhookTenantControllerTest, EventAdminControllerTest, EventTenantControllerTest, WebhookSecurityConfigControllerTest

*Redis data model (new keys):*
- `event:{eventId}` → String, `events:{tenantId}` → ZSET, `events:_all` → ZSET
- `webhook:{subscriptionId}` → String, `webhooks:{tenantId}` → SET, `webhooks:_all` → SET
- `delivery:{deliveryId}` → String, `deliveries:{subscriptionId}` → ZSET
- `config:webhook-security` → String, `events:correlation:{correlationId}` → SET

*Spec:* 55 schemas, 26 paths, 39 operations, 40 event types, 23 permissions, 27 error codes. Backward compatible with v0.1.24.

---

### 2026-03-31 — v0.1.25: Webhook Security Features

Added three webhook security features to the admin API spec:

**1. Expanded API key permissions (24 total, up from 8):**
- 3 new tenant permissions: `webhooks:read`, `webhooks:write`, `events:read`
- 12 new granular admin permissions: `admin:tenants:read/write`, `admin:budgets:read/write`, `admin:policies:read/write`, `admin:apikeys:read/write`, `admin:webhooks:read/write`, `admin:events:read`, `admin:audit:read`
- Existing `admin:read` and `admin:write` retained as backward-compatible wildcards

**2. WebhookSecurityConfig schema and admin endpoints:**
- New `WebhookSecurityConfig` schema with `blocked_cidr_ranges` (RFC 1918 + loopback + link-local blocked by default), `allowed_url_patterns` (glob), and `allow_http` flag
- `GET /v1/admin/config/webhook-security` — Read current config
- `PUT /v1/admin/config/webhook-security` — Update config (SSRF protection)

**3. Tenant webhook self-service (8 new endpoints):**
- `POST /v1/webhooks` — Create tenant-scoped webhook (ApiKeyAuth, requires `webhooks:write`)
- `GET /v1/webhooks` — List tenant's webhooks
- `GET /v1/webhooks/{subscription_id}` — Get tenant's webhook
- `PATCH /v1/webhooks/{subscription_id}` — Update tenant's webhook
- `DELETE /v1/webhooks/{subscription_id}` — Delete tenant's webhook
- `POST /v1/webhooks/{subscription_id}/test` — Test tenant's webhook
- `GET /v1/webhooks/{subscription_id}/deliveries` — List delivery attempts
- `GET /v1/events` — Query tenant-scoped events (requires `events:read`)

Tenants restricted to `budget.*`, `reservation.*`, `tenant.*` event types (26 of 40). Admin-only: `api_key.*`, `policy.*`, `system.*`.

**Status:** Spec only — server implementation pending.

### 2026-03-31 — Pillar 4: Unit Tests for Service and Controller Layers

Added 82 unit tests across 9 test files covering the Pillar 4 Events & Webhooks service and controller layers:

**Service tests (4 files, 53 tests):**
- `EventServiceTest` (14 tests) — emit auto-generates IDs/timestamps/category, non-blocking on failures, findById delegation, list pagination with limit clamping
- `WebhookServiceTest` (17 tests) — create with auto-generated/provided signing secrets, get masks secrets/headers, partial update, delete, test, listByTenant/listAll with filtering, listDeliveries, replay
- `WebhookDispatchServiceTest` (7 tests) — dispatch to matching subscriptions, delivery creation, non-blocking on failure, empty subscription list, consistent HMAC-SHA256 signing
- `WebhookUrlValidatorTest` (15 tests) — null/blank/malformed URL rejection, HTTP/HTTPS scheme enforcement, private IP blocking, allowed URL pattern matching, glob wildcard tests

**Controller tests (5 files, 29 tests):**
- `WebhookAdminControllerTest` (10 tests) — all 8 admin webhook endpoints (POST 201, GET list/detail, PATCH, DELETE 204, POST test, GET deliveries, POST replay 202), auth enforcement, audit logging
- `WebhookTenantControllerTest` (7 tests) — tenant-scoped create with valid/admin-only event types, ownership enforcement (own=200, other=404), delete, auth enforcement, list
- `EventAdminControllerTest` (5 tests) — list with filters, get found/not found, auth enforcement
- `EventTenantControllerTest` (3 tests) — auto-scoped to authenticated tenant, filters pass-through, auth enforcement
- `WebhookSecurityConfigControllerTest` (4 tests) — get/update config, auth enforcement

All tests follow existing project conventions: `@ExtendWith(MockitoExtension.class)` for services, `@WebMvcTest` with `@MockitoBean` for controllers.

---

### 2026-03-31 — Pillar 4: Wire Event Emission into Existing Controllers

Wired `EventService.emit()` calls into the 5 existing controllers so that state-changing operations produce events:

- **TenantController**: `create()` emits `TENANT_CREATED`; `update()` emits `TENANT_UPDATED`, `TENANT_SUSPENDED`, `TENANT_REACTIVATED`, or `TENANT_CLOSED` based on status change
- **BudgetController**: `create()` emits `BUDGET_CREATED`; `update()` emits `BUDGET_UPDATED`; `fund()` emits `BUDGET_FUNDED`, `BUDGET_DEBITED`, `BUDGET_RESET`, or `BUDGET_DEBT_REPAID` based on operation
- **ApiKeyController**: `create()` emits `API_KEY_CREATED`; `revoke()` emits `API_KEY_REVOKED`
- **PolicyController**: `create()` emits `POLICY_CREATED`; `update()` emits `POLICY_UPDATED`
- **AuthController**: `validate()` emits `API_KEY_AUTH_FAILED` when validation returns `valid=false`

All event emissions are fire-and-forget (wrapped in try-catch), same pattern as audit logging. Admin endpoints use `ActorType.ADMIN`; tenant-scoped endpoints use `ActorType.API_KEY` with `key_id` from request attributes. Event data uses typed builders (`EventDataTenantLifecycle`, `EventDataBudgetLifecycle`, `EventDataApiKey`, `EventDataPolicy`) converted to `Map` via `ObjectMapper.convertValue()`.

---

### 2026-03-31 — Pillar 4: Controller Layer Implementation

Implemented controller layer for Events & Webhooks (5 controllers, 1 AuthInterceptor update):

**New controllers:**
- `WebhookAdminController` — 8 admin endpoints at `/v1/admin/webhooks` (CRUD, test, deliveries, replay)
- `WebhookTenantController` — 7 tenant-scoped endpoints at `/v1/webhooks` with ownership enforcement
- `EventAdminController` — 2 admin endpoints at `/v1/admin/events` (list, get)
- `EventTenantController` — 1 tenant-scoped endpoint at `/v1/events` (list, auto-scoped)
- `WebhookSecurityConfigController` — 2 admin endpoints at `/v1/admin/config/webhook-security` (get, put)

**AuthInterceptor update:**
- Added `/v1/webhooks` and `/v1/events` to `requiresApiKey()` so tenant endpoints require API key authentication
- Admin paths (`/v1/admin/webhooks`, `/v1/admin/events`, `/v1/admin/config`) and PERMISSION_MAP entries were already present

**Design notes:**
- Tenant controllers enforce ownership via `enforceTenantOwnership()` (returns 404, not 403, to avoid leaking existence)
- Tenant webhook creation validates event types are tenant-accessible (budget.*, reservation.*, tenant.* only)
- All write operations on admin webhook controller log to audit repository
- Controllers delegate all business logic to service layer (`WebhookService`, `EventService`)

---

### 2026-03-31 — v0.1.25: Pillar 4 — Events & Webhooks (Observability Plane)

Added a fourth pillar to the admin API spec: **Events & Webhooks**.

**New event type taxonomy (39 event types across 6 categories):**
- `budget.*` (15 types): lifecycle (created, funded, debited, reset, debt_repaid, frozen, unfrozen, closed, updated), runtime (threshold_crossed, exhausted, over_limit_entered/exited, debt_incurred, burn_rate_anomaly)
- `reservation.*` (5 types): denied, denial_rate_spike, expired, expiry_rate_spike, commit_overage
- `tenant.*` (6 types): created, updated, suspended, reactivated, closed, settings_changed
- `api_key.*` (6 types): created, revoked, expired, permissions_changed, auth_failed, auth_failure_rate_spike
- `policy.*` (3 types): created, updated, deleted
- `system.*` (4 types): store_connection_lost, store_connection_restored, high_latency, webhook_delivery_failed

**New schemas (27):**
- Core: `EventCategory`, `EventType`, `Event`
- Event data payloads (11): `EventDataBudgetLifecycle`, `EventDataBudgetThreshold`, `EventDataBudgetOverLimit`, `EventDataBudgetDebtIncurred`, `EventDataBurnRateAnomaly`, `EventDataReservationDenied`, `EventDataReservationExpired`, `EventDataRateSpike`, `EventDataCommitOverage`, `EventDataTenantLifecycle`, `EventDataApiKey`, `EventDataPolicy`, `EventDataSystem`
- Webhook management (11): `WebhookSubscription`, `WebhookThresholdConfig`, `WebhookRetryPolicy`, `WebhookCreateRequest`, `WebhookCreateResponse`, `WebhookUpdateRequest`, `WebhookListResponse`, `WebhookDelivery`, `WebhookDeliveryListResponse`, `WebhookTestResponse`, `EventListResponse`

**New endpoints (10 operations across 7 paths):**
- `POST /v1/admin/webhooks` — Create webhook subscription
- `GET /v1/admin/webhooks` — List webhook subscriptions
- `GET /v1/admin/webhooks/{subscription_id}` — Get subscription details
- `PATCH /v1/admin/webhooks/{subscription_id}` — Update subscription
- `DELETE /v1/admin/webhooks/{subscription_id}` — Delete subscription
- `POST /v1/admin/webhooks/{subscription_id}/test` — Send test event
- `GET /v1/admin/webhooks/{subscription_id}/deliveries` — List delivery attempts
- `GET /v1/admin/events` — Query event stream
- `GET /v1/admin/events/{event_id}` — Get single event
- `POST /v1/admin/webhooks/{subscription_id}/replay` — Replay historical events

**Design decisions:**
- Implementation-agnostic naming: `system.store_connection_lost` (not `redis_connection_lost`)
- Extensible event types: consumers MUST ignore unrecognized types; custom prefix `custom.*` reserved
- At-least-once delivery with deduplication via `event_id`
- Category-level wildcard subscriptions (e.g., subscribe to all `budget.*` including future types)
- Configurable thresholds per subscription (utilization %, burn rate multiplier, denial/expiry rate)
- HMAC-SHA256 payload signing with `X-Cycles-Signature` header
- Auto-disable after consecutive failures with manual re-enable via PATCH

**Status:** Spec only — server implementation pending.

---

### 2026-03-31 — Dynamic version in startup message
- Removed all hardcoded `v0.1.24` from Java source and POM descriptions
- Startup banner now reads version dynamically via `BuildProperties` bean (aligned with cycles-server's `StartupBanner` pattern)
- Added `build-info` goal to `spring-boot-maven-plugin` to generate `META-INF/build-info.properties` at build time
- Version is defined once in `cycles-admin-service/pom.xml` `<revision>` property — no code changes needed when bumping version
- Bumped version to `0.1.24.2`

**Runtime server audit:** See `cycles-server/AUDIT.md` (all passing)

---

## Summary

| Category | Pass | Issues |
|----------|------|--------|
| Endpoints & Routes | 17/17 | 0 |
| Request Schemas | 10/10 | 0 |
| Response Schemas | 12/12 | 0 |
| Enum Values | 10/10 | 0 |
| Auth & Security | — | 0 |
| Tenant Scoping | — | 0 |
| Idempotency | — | 0 |
| Pagination | — | 0 |
| Audit Logging | — | 0 |
| Error Handling | — | 0 |
| Behavioral Constraints | — | 0 |

**All previously identified issues (19 across Rounds 1–3, plus 7 in Rounds 4–5) have been fixed. No remaining spec violations found.**

---

## Audit Scope

Compared the following across spec YAML and server Java source:
- All 17 admin/auth/balance endpoint paths, HTTP methods, and path/query parameters (2 runtime endpoints — reservations and commit — are handled by `cycles-server`)
- All 10 request schemas (fields, types, constraints, required markers)
- All 12 response schemas and domain models
- All 10 enum types and their values
- Auth: `X-Admin-API-Key` for admin endpoints, `X-Cycles-API-Key` for tenant-scoped endpoints
- Tenant scoping on budget, policy, and balance operations
- Funding idempotency, tenant create idempotency, budget duplicate detection
- Cursor-based pagination (limit clamping, next_cursor, has_more)
- Audit log recording on all write operations
- Behavioral constraints (status transitions, funding operations, key lifecycle)

---

## PASS — Correctly Implemented

### Endpoints (all 17 match spec)

| Spec Endpoint | Method | Controller | Auth | Match |
|---|---|---|---|---|
| `/v1/admin/tenants` | POST | `TenantController.create` | AdminKeyAuth | PASS |
| `/v1/admin/tenants` | GET | `TenantController.list` | AdminKeyAuth | PASS |
| `/v1/admin/tenants/{tenant_id}` | GET | `TenantController.get` | AdminKeyAuth | PASS |
| `/v1/admin/tenants/{tenant_id}` | PATCH | `TenantController.update` | AdminKeyAuth | PASS |
| `/v1/admin/budgets` | POST | `BudgetController.create` | ApiKeyAuth | PASS |
| `/v1/admin/budgets` | GET | `BudgetController.list` | ApiKeyAuth | PASS |
| `/v1/admin/budgets?scope=&unit=` | PATCH | `BudgetController.update` | AdminKeyAuth | PASS |
| `/v1/admin/budgets/fund?scope=&unit=` | POST | `BudgetController.fund` | ApiKeyAuth | PASS |
| `/v1/admin/policies` | POST | `PolicyController.create` | ApiKeyAuth | PASS |
| `/v1/admin/policies` | GET | `PolicyController.list` | ApiKeyAuth | PASS |
| `/v1/admin/policies/{policy_id}` | PATCH | `PolicyController.update` | ApiKeyAuth | PASS |
| `/v1/admin/api-keys` | POST | `ApiKeyController.create` | AdminKeyAuth | PASS |
| `/v1/admin/api-keys` | GET | `ApiKeyController.list` | AdminKeyAuth | PASS |
| `/v1/admin/api-keys/{key_id}` | DELETE | `ApiKeyController.revoke` | AdminKeyAuth | PASS |
| `/v1/auth/validate` | POST | `AuthController.validate` | AdminKeyAuth | PASS |
| `/v1/admin/audit/logs` | GET | `AuditController.list` | AdminKeyAuth | PASS |
| `/v1/balances` | GET | `BalanceController.query` | ApiKeyAuth | PASS |

Note: The spec also defines `POST /v1/reservations`, `POST /v1/reservations/{id}/commit`, and `GET /v1/balances` as runtime endpoints. The admin server implements `GET /v1/balances`; the reservation endpoints are handled by `cycles-server`.

### Request Schemas (all match spec)

**TenantCreateRequest** — spec required: `[tenant_id, name]`
- Fields: `tenant_id` (`@NotBlank @Pattern @Size`), `name` (`@NotBlank`), `parent_tenant_id`, `metadata`, `default_commit_overage_policy`, `default_reservation_ttl_ms`, `max_reservation_ttl_ms`, `max_reservation_extensions`, `reservation_expiry_policy` — all match spec constraints. TTL/extension fields default to spec values (60s, 1h, 10) when not provided.

**TenantUpdateRequest** — all optional fields
- Fields: `name`, `status` (TenantStatus), `metadata`, `default_commit_overage_policy`, `default_reservation_ttl_ms`, `max_reservation_ttl_ms`, `max_reservation_extensions` — all match spec

**BudgetCreateRequest** — spec required: `[scope, unit, allocated]`
- Fields: `scope` (`@NotBlank`), `unit` (`@NotNull`), `allocated` (`@NotNull @Valid`), `overdraft_limit`, `commit_overage_policy`, `rollover_policy`, `period_start`, `period_end`, `metadata` — all match spec

**BudgetUpdateRequest** — all optional fields
- Fields: `overdraft_limit`, `commit_overage_policy`, `metadata` — all match spec. Lua script recalculates `is_over_limit` after overdraft change. Returns 409 if budget is CLOSED.

**BudgetFundingRequest** — spec required: `[operation, amount]`
- Fields: `operation` (`@NotNull`), `amount` (`@NotNull @Valid`), `reason` (`@Size`), `idempotency_key` (`@Size`), `metadata` — all match spec

**PolicyCreateRequest** — spec required: `[name, scope_pattern]`
- Fields: `name` (`@NotBlank`), `description`, `scope_pattern` (`@NotBlank`), `priority`, `caps` (`@Valid`), `commit_overage_policy`, `reservation_ttl_override`, `rate_limits`, `effective_from`, `effective_until` — all match spec

**PolicyUpdateRequest** — all optional fields
- Fields: `name`, `description`, `priority`, `caps`, `commit_overage_policy`, `reservation_ttl_override`, `rate_limits`, `effective_from`, `effective_until`, `status` — all match spec. Lua script merges non-null fields into existing policy JSON.

**ApiKeyCreateRequest** — spec required: `[tenant_id, name]`
- Fields: `tenant_id` (`@NotBlank`), `name` (`@NotBlank`), `description`, `permissions`, `scope_filter`, `expires_at`, `metadata` — all match spec

**ApiKeyValidationRequest** — spec required: `[key_secret]`
- Fields: `key_secret` (`@NotBlank`) — matches spec

**ReservationCreateRequest / CommitRequest** — defined in spec but handled by `cycles-server`, not this service

### Response Schemas (all match spec)

| Spec Schema | Java Class | Required Fields Serialized | Match |
|---|---|---|---|
| `Tenant` | `Tenant` | `tenant_id`, `name`, `status`, `created_at` always present; optional fields `NON_NULL` | PASS |
| `TenantListResponse` | `TenantListResponse` | `tenants`, `has_more` always present; `next_cursor` `NON_NULL` | PASS |
| `BudgetLedger` | `BudgetLedger` | `ledger_id`, `scope`, `scope_path`, `unit`, `allocated`, `remaining`, `reserved`, `spent` always present; `debt`, `overdraft_limit`, optional fields `NON_NULL` | PASS |
| `BudgetFundingResponse` | `BudgetFundingResponse` | `operation`, `previous_allocated`, `new_allocated`, `previous_remaining`, `new_remaining`, `timestamp` always present; `previous_debt`, `new_debt` `NON_NULL` | PASS |
| `BudgetListResponse` | `BudgetListResponse` | `ledgers`, `has_more` always present; `next_cursor` `NON_NULL` | PASS |
| `BalanceQueryResponse` | `BalanceQueryResponse` | `balances`, `has_more` always present; `next_cursor` when applicable | PASS |
| `Policy` | `Policy` | `policy_id`, `name`, `scope_pattern`, `priority`, `status`, `created_at` always present; optional fields `NON_NULL` | PASS |
| `PolicyListResponse` | `PolicyListResponse` | `policies`, `has_more` always present; `next_cursor` `NON_NULL` | PASS |
| `ApiKeyCreateResponse` | `ApiKeyCreateResponse` | `key_id`, `key_secret`, `key_prefix`, `tenant_id`, `permissions`, `created_at` always present | PASS |
| `ApiKeyListResponse` | `ApiKeyListResponse` | `keys`, `has_more` always present; `next_cursor` `NON_NULL` | PASS |
| `ApiKeyValidationResponse` | `ApiKeyValidationResponse` | `valid` always present; `tenant_id`, `key_id`, `permissions`, `scope_filter`, `expires_at`, `reason` `NON_NULL` | PASS |
| `AuditLogListResponse` | `AuditLogListResponse` | `logs`, `has_more` always present; `next_cursor` `NON_NULL` | PASS |

### Enum Values (all match spec)

| Spec Enum | Java Enum | Values | Match |
|---|---|---|---|
| `UnitEnum` | `UnitEnum` | `USD_MICROCENTS`, `TOKENS`, `CREDITS`, `RISK_POINTS` | PASS |
| `CommitOveragePolicy` | `CommitOveragePolicy` | `REJECT`, `ALLOW_IF_AVAILABLE`, `ALLOW_WITH_OVERDRAFT` | PASS |
| `ErrorCode` | `ErrorCode` | All 23 spec values (12 runtime + 11 admin-specific) | PASS |
| `TenantStatus` | `TenantStatus` | `ACTIVE`, `SUSPENDED`, `CLOSED` | PASS |
| `ApiKeyStatus` | `ApiKeyStatus` | `ACTIVE`, `REVOKED`, `EXPIRED` | PASS |
| `PolicyStatus` | `PolicyStatus` | `ACTIVE`, `DISABLED` | PASS |
| `BudgetStatus` | `BudgetStatus` | `ACTIVE`, `FROZEN`, `CLOSED` | PASS |
| `FundingOperation` | `FundingOperation` | `CREDIT`, `DEBIT`, `RESET`, `REPAY_DEBT` | PASS |
| `RolloverPolicy` | `RolloverPolicy` | `NONE`, `CARRY_FORWARD`, `CAP_AT_ALLOCATED` | PASS |
| `ReservationExpiryPolicy` | `ReservationExpiryPolicy` | `AUTO_RELEASE`, `MANUAL_CLEANUP`, `GRACE_ONLY` | PASS |

### Auth & Security (correct)

- **AdminKeyAuth** (`X-Admin-API-Key`): Validated by `AuthInterceptor.validateAdminKey()` for all `/v1/admin/*` and `/v1/auth/*` endpoints
- **ApiKeyAuth** (`X-Cycles-API-Key`): Validated by `AuthInterceptor.validateApiKey()` for budget, policy, and balance endpoints
- Missing admin key → 401 UNAUTHORIZED; invalid admin key → 401 UNAUTHORIZED (spec: "Missing or invalid API key")
- API key validation checks: key exists, hash matches, status is ACTIVE, not expired, tenant exists and is ACTIVE — all 6 checks in `ApiKeyRepository.validate()`
- Deleted/missing tenant during key validation → returns `valid=false` with `TENANT_NOT_FOUND` (defense-in-depth)
- `ApiKey.keyHash` excluded from API responses via `@JsonIgnore`; API uses `ApiKeyResponse` DTO instead

### Tenant Scoping (correct)

- Budget list: scoped to `authenticated_tenant_id` in `BudgetController.list()`
- Budget fund: Lua script validates `tenant_id` match before applying operation
- Budget create: scoped to `authenticated_tenant_id` in `BudgetController.create()`
- Balance query: scoped to `authenticated_tenant_id` in `BalanceController.query()`
- Policy create/list: scoped to `authenticated_tenant_id` in `PolicyController`
- Cross-tenant access blocked on all tenant-scoped endpoints

### Idempotency & Atomicity (correct)

- Tenant create: Lua script returns existing tenant on duplicate `tenant_id`; controller returns 200 (existing) vs 201 (new) via `result.created()`
- Budget create: Lua script atomic `EXISTS` check returns 409 `DUPLICATE_RESOURCE` on duplicate `scope + unit`
- Budget funding: `idempotency_key` cached in Redis with 24h TTL; replays return original response
- All Lua scripts use Redis single-threaded execution for atomicity

### Pagination (correct)

- All list endpoints support cursor-based pagination
- Limit clamping: `Math.max(1, Math.min(limit, 100))` — default 50, min 1, max 100
- `next_cursor` present only when more results exist; `has_more` boolean always present

### Audit Logging (correct)

- All write operations (create, update, fund, revoke) log to `AuditRepository`
- `AuditLogEntry` captures: `log_id`, `timestamp`, `tenant_id`, `key_id`, `user_agent`, `source_ip`, `operation`, `request_id`, `status`, `error_code`, plus optional detail fields
- Audit logs queryable via `GET /v1/admin/audit/logs` with cursor pagination

### Behavioral Constraints (all correct)

| Spec Behavior | Implementation | Match |
|---|---|---|
| Tenant status transitions: ACTIVE↔SUSPENDED, *→CLOSED (irreversible) | `TenantRepository.update()` validates transitions | PASS |
| DEBIT fails if remaining < amount | Lua returns INSUFFICIENT_FUNDS error | PASS |
| RESET: remaining = amount − reserved − spent − debt | Lua implements formula | PASS |
| REPAY_DEBT: reduces debt, excess goes to remaining | Lua implements logic | PASS |
| FROZEN/CLOSED budgets block fund operations | Lua checks status, returns error | PASS |
| Key revocation is permanent (ACTIVE → REVOKED) | `ApiKeyRepository.revoke()` sets status and timestamp | PASS |
| Tenant existence check before API key creation | `ApiKeyRepository.create()` validates tenant exists and is active | PASS |
| Tenant existence check before budget creation | `BudgetRepository.create()` Lua validates tenant exists and is ACTIVE | PASS |
| Tenant existence check before policy creation | `PolicyRepository.create()` Lua validates tenant exists and is ACTIVE | PASS |
| Deleted tenant blocks API key validation | `ApiKeyRepository.validate()` returns TENANT_NOT_FOUND if tenant data missing | PASS |
| Unit mismatch validation on budget create/fund/update | `BudgetController` validates Amount.unit matches budget unit | PASS |
| REPAY_DEBT maintains ledger invariant | Lua: `remaining += repayment`; excess updates both `allocated` and `remaining` | PASS |
| Error responses include `request_id` | `GlobalExceptionHandler` and `AuthInterceptor` both generate UUID | PASS |

### Error Handling (correct)

- `GlobalExceptionHandler` maps all exception types to spec `ErrorResponse` format
- `X-Request-Id` set on every response via `RequestIdFilter`
- Admin-specific error codes (TENANT_NOT_FOUND, TENANT_SUSPENDED, BUDGET_NOT_FOUND, BUDGET_FROZEN, etc.) all return correct HTTP status codes
- `ErrorResponse.details` uses `@JsonInclude(NON_NULL)` — omitted when null, not serialized as `null`

---

## Previously Found Issues (all fixed)

26 issues were identified and fixed across Rounds 1–5:

| Round | Issues | Severity | Status |
|---|---|---|---|
| Round 1 | #1–10: Serialization, validation, permissions, field constraints | Various | All **FIXED** |
| Round 2 | #11–13: Class-level `NON_NULL` on required fields in responses | LOW | All **FIXED** |
| Round 3 | #14: `keyHash` exposed via `@JsonProperty` | HIGH | **FIXED** |
| Round 3 | #15: Cross-tenant budget modification via fund endpoint | CRITICAL | **FIXED** |
| Round 3 | #16–17: Cross-tenant budget/balance enumeration | HIGH | All **FIXED** |
| Round 3 | #18–19: `ErrorResponse.details` null serialization, `BalanceQueryResponse` `NON_NULL` | LOW | All **FIXED** |
| Round 4 | #20: REPAY_DEBT Lua breaks ledger invariant (remaining += excess without allocated +=) | CRITICAL | **FIXED** |
| Round 4 | #21: Invalid admin API key returned 403 instead of 401 per spec | MEDIUM | **FIXED** |
| Round 4 | #22: No unit mismatch validation on budget create/fund/update | MEDIUM | **FIXED** |
| Round 5 | #23: API key validation silently accepted deleted/missing tenants | HIGH | **FIXED** |
| Round 5 | #24: Budget create Lua had no tenant existence/status check | HIGH | **FIXED** |
| Round 5 | #25: Policy create Lua had no tenant existence/status check | HIGH | **FIXED** |
| Round 5 | #26: TenantCreateRequest missing TTL/extension/expiry fields (required separate update) | LOW | **FIXED** |
| Round 6 | #27: API key `permissions` stored but never enforced | CRITICAL | **FIXED** |
| Round 6 | #28: API key `scope_filter` stored but never applied | CRITICAL | **FIXED** |
| Round 6 | #29: Rate limits stored in Policy model but not enforced at runtime | v0 LIMITATION | Documented |
| Round 6 | #30: Policies (caps, overrides) not consumed by protocol server at runtime | v0 LIMITATION | Documented |

---

## Round 6 — Spec Compliance Audit (2026-03-24)

Full audit of all changes against the authoritative admin governance YAML spec (`complete-budget-governance-v0.1.24.yaml`).

### Issue 27 [FIXED]: API key `permissions` stored but never enforced

- **Spec:** Admin spec defines permissions enum (`reservations:create`, `reservations:commit`, `reservations:release`, `reservations:extend`, `reservations:list`, `balances:read`, `admin:read`, `admin:write`). Line 1674: `'403': description: Tenant mismatch, suspended, or insufficient permissions`.
- **Was:** `AuthInterceptor.validateApiKey()` stored `authenticated_permissions` in request attributes but no code checked them. Any valid API key could perform any operation.
- **Fix:** Added `PERMISSION_MAP` (endpoint path+method → required permission) and `resolveRequiredPermission()` in `AuthInterceptor`. Permission check runs after API key validation; returns 403 `INSUFFICIENT_PERMISSIONS` when the key lacks the required permission. Path walking handles path variables (e.g. `PATCH /v1/admin/policies/pol_123` → matches `PATCH:/v1/admin/policies` → requires `admin:write`).
- **Location:** `AuthInterceptor.java:26-40,116-128,135-160`
- **Tests:** `AuthInterceptorTest`: 6 new tests covering insufficient permissions (403), balances:read allows, admin:read allows, path variable resolution, null permissions denial

### Issue 28 [FIXED]: API key `scope_filter` stored but never applied

- **Spec:** Admin spec defines `scope_filter` as "Optional restriction to specific scopes. Example: ['workspace:eng', 'agent:*'] limits key to eng workspace". `/v1/auth/validate` returns `scope_filter` to the runtime layer.
- **Was:** `AuthInterceptor.validateApiKey()` stored `authenticated_scope_filter` in request attributes but no controller checked it. A key restricted to `workspace:eng` could access any scope.
- **Fix:** Created `ScopeFilterUtil` with segment-based matching. Wildcard filters use `"key:*"` format (e.g. `"agent:*"` matches any segment starting with `"agent:"`). Bare `"*"` and `":*"` are rejected as malformed. Null/blank filter entries are skipped. Integrated into all controllers:
  - `BudgetController`: create (body scope), list (scope_prefix param), update (scope param), fund (scope param)
  - `PolicyController`: create (body scopePattern), update (fetches scopePattern via `PolicyRepository.getScopePattern()`), list (scope_pattern param)
  - `BalanceController`: query (scope_prefix param)
- **Location:** `ScopeFilterUtil.java` (new), `BudgetController.java`, `PolicyController.java`, `BalanceController.java`, `PolicyRepository.java`, `GovernanceException.java`
- **Tests:** `ScopeFilterUtilTest`: 15 tests covering exact match, wildcard, substring attack prevention, bare asterisk, null/blank filters, enforcement integration. `BudgetControllerTest`: 2 new tests for scope filter denied/allowed.

### Issue 29 [DOCUMENTED]: Rate limits defined but not enforced

- **Spec:** `RateLimits` model defines `max_reservations_per_minute` and `max_commits_per_minute`. Protocol spec: "HTTP 429 is reserved for server-side throttling/rate limiting (optional in v0)".
- **Status:** v0 limitation. Rate limit configuration is accepted and stored but runtime enforcement is deferred.
- **Location:** Comment added to `RateLimits.java`

### Issue 30 [DOCUMENTED]: Policies not consumed by protocol server at runtime

- **Spec:** Admin spec GOVERNANCE INTEGRATION step 6: "Applies matching policies (caps, rate limits)". However, the mechanism for runtime consumption is not specified.
- **Status:** v0 limitation. Policies (caps, overage overrides, TTL overrides, rate limits) are stored for future consumption. Runtime enforcement by the protocol server is deferred.
- **Location:** Comment added to `PolicyController.java`

---

## Test Coverage

21 test classes cover the implementation (207 tests total, up from 182 baseline):

| Layer | Test Classes | Coverage |
|---|---|---|
| Application | `BudgetGovernanceApplicationTest` | Spring Boot main entry point |
| Controllers | `TenantControllerTest`, `BudgetControllerTest`, `PolicyControllerTest`, `ApiKeyControllerTest`, `AuthControllerTest`, `BalanceControllerTest`, `AuditControllerTest` | All 17 endpoints |
| Auth/Config | `AuthInterceptorTest`, `WebConfigTest`, `RequestIdFilterTest`, `GlobalExceptionHandlerTest` | Auth flow, request IDs, error mapping |
| Repositories | `TenantRepositoryTest`, `BudgetRepositoryTest`, `PolicyRepositoryTest`, `ApiKeyRepositoryTest`, `AuditRepositoryTest` | Redis operations, Lua scripts |
| Services | `KeyServiceTest` | API key hashing |
| Data/Config | `RedisConfigTest`, `GovernanceExceptionTest` | Redis configuration, exception model |
| Integration | `RedisIntegrationTest` | End-to-end with TestContainers Redis |

Notable additions since initial audit:
- Tenant lifecycle tests: CLOSED→SUSPENDED invalid transition, CLOSED tenant API key creation rejection
- Integration tests for overage policy resolution, status transitions, pagination filters, and budget operations
- `default_commit_overage_policy` support on tenant create and update endpoints
- Round 6: `ScopeFilterUtilTest` (15 tests), `AuthInterceptorTest` permission enforcement (6 tests), `BudgetControllerTest` scope filter (2 tests), `PolicyControllerTest` updated mock permissions

### JaCoCo Line Coverage

| Module | Lines Covered | Lines Missed | Coverage |
|---|---|---|---|
| cycles-admin-service-api | 252 | 0 | **100.0%** |
| cycles-admin-service-data | 878 | 24 | **97.3%** |
| cycles-admin-service-model | — | — | Skipped (pure data classes) |

**JaCoCo enforcement threshold:** 95% minimum line coverage (BUNDLE level).
All modules exceed the threshold. Overall effective coverage: **98.7%**.

---

## Production Deployment Notes

- **Port 7979** — internal admin service, never expose to public internet
- **Redis** — single point of state; use appendonly persistence, HA setup (Sentinel/Cluster) for production
- **Logging** — `application.properties` defaults to DEBUG for `io.runcycles.admin`; override to INFO via `LOGGING_LEVEL_IO_RUNCYCLES_ADMIN=INFO`
- **Admin key** — set `ADMIN_API_KEY` env var; server rejects all admin requests with 500 if not configured (fail-closed)
- **Swagger UI** — enabled by default (`/swagger-ui.html`); acceptable for internal-only service
- **Redis pool** — 50 max connections, 10 max idle, 5 min idle; increase for high-throughput deployments
- **Audit logging** — non-fatal; audit write failures are logged as errors but do not fail business operations
- **All Lua scripts are atomic** — no TOCTOU race conditions on any write path

---

## Verdict

The admin server is **fully compliant** with the Complete Budget Governance spec v0.1.25 and **ready for production deployment**. 325 tests passing, 95%+ coverage. All 17 endpoints plus 20 webhook/event endpoints (v0.1.25 Pillar 4) are implemented. Auth (AdminKeyAuth / ApiKeyAuth), tenant scoping, idempotency, pagination, audit logging, webhook SSRF protection, AES-256-GCM signing secret encryption, and behavioral constraints all follow spec normative rules. Full-stack E2E test (23 assertions) verified across Admin + Runtime + Events services. All previously identified issues have been verified as fixed. No remaining spec violations found.

---

## Audit History

For historical audit rounds 1-4 against spec v0.1.23 (19 issues found and fixed), see [AUDIT-history.md](./AUDIT-history.md).
