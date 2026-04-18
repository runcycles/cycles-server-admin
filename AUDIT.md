# Complete Budget Governance v0.1.25.28 — Admin Server Audit

**Server version:** 0.1.25.28 (2026-04-17 — audit tenant_id sentinel split: `__admin__` for admin-plane auth, `__unauth__` for pre-auth failures, spec v0.1.25.25)
**Date:** 2026-04-17 (v0.1.25.28 audit sentinel split), 2026-04-17 (v0.1.25.27 audit filter DSL upgrade), 2026-04-17 (v0.1.25.26 bulk-action endpoints), 2026-04-17 (v0.1.25.25 search on six list endpoints), 2026-04-16 (v0.1.25.24 server-side sort — six admin list endpoints), 2026-04-16 (v0.1.25.23 BudgetLedger tenant_id on wire), 2026-04-16 (v0.1.25.22 cross-tenant list + filters), 2026-04-16 (v0.1.25.21 nightly CI), 2026-04-16 (v0.1.25.20 audit-on-failure), 2026-04-16 (v0.1.25.19 introspect dual-auth + operator docs), 2026-04-15 (v0.1.25.18 RESET_SPENT operation), 2026-04-14 (v0.1.25.17 cjson round-trip sweep: apikey + policy + tenant), 2026-04-13 (v0.1.25.16 webhooks dual-auth), 2026-04-13 (v0.1.25.15 ScopeValidator), 2026-04-13 (v0.1.25.14 admin-on-behalf-of dual-auth), 2026-04-13 (v0.1.25.13 CORS PUT fix), 2026-04-12 (v0.1.25.12 spec-compliance hardening + observability), 2026-04-12 (v0.1.25.11 contract-testing default ON), 2026-04-12 (v0.1.25.10 spec-compliance hardening), 2026-04-10 (v0.1.25.9 release), 2026-04-10 (CORS hardening + prod config), 2026-04-10 (observability: prometheus metrics + k8s probes), 2026-04-10 (v0.1.25.8 spec alignment), 2026-04-09 (v0.1.25.7 admin wildcard fallback), 2026-04-08 (v0.1.25.6 freeze/unfreeze + admin fund), 2026-04-08 (v0.1.25.5 dashboard support release), 2026-04-06 (v0.1.25.4 spec compliance + replay lock), 2026-04-01 (spec compliance review), 2026-04-01 (TTL retention + release prep), 2026-04-01 (integration audit + encryption), 2026-03-31 (v0.1.25 Pillar 4: Events & Webhooks spec), 2026-03-31 (dynamic version), 2026-03-24 (Round 6: spec compliance audit), 2026-03-24 (Round 5: pre-release audit), 2026-03-24 (v0.1.24 update), 2026-03-23 (updated), 2026-03-14 (initial)
**Spec:** [`cycles-governance-admin-v0.1.25.yaml`](https://github.com/runcycles/cycles-protocol/blob/main/cycles-governance-admin-v0.1.25.yaml) (OpenAPI 3.1.0, info.version `0.1.25.25`; `listAuditLogs` gains `error_code` / `error_code_exclude` IN/NOT-IN arrays, `status_min` / `status_max` numeric range; `operation` / `resource_type` promoted scalar → array, all maxItems 25) in [cycles-protocol](https://github.com/runcycles/cycles-protocol)
**Server:** Spring Boot 3.5.11 / Java 21 / Redis

### 2026-04-17 — v0.1.25.28 audit tenant_id sentinel split (spec v0.1.25.25)

Through v0.1.25.27 the failure-audit writer wrote a single sentinel `"<unauthenticated>"` for any request that arrived without a tenant-key binding. That bucket conflated two very different populations: pre-auth failures (missing/invalid/revoked key — potential DDoS / credential-stuffing noise) and platform-admin-authenticated requests that simply aren't scoped to any one tenant (governance ops, cross-tenant reads, admin-plane 4xx/5xx). The conflation leaked into three places:

1. **Retention.** Both populations rode the short unauthenticated-tier TTL (30d). Admin-plane failures — high-signal compliance events — aged out on the same schedule as anonymous 401s.
2. **Sampling.** The DDoS sampling gate would drop admin-plane entries along with pre-auth noise when an operator raised `audit.sample.unauthenticated` above 1.
3. **Auditor UX.** A query like `?tenant_id=<unauthenticated>` returned a mix of "someone tried a bad key" and "an admin operator hit a 404" — the auditor had to filter on `error_code` / `operation` manually to tell them apart.

This release splits the sentinel in two.

**Three-sentinel table** (forward-compat + back-compat):

| Sentinel | Who | TTL tier | Sampling | When emitted |
|---|---|---|---|---|
| `__admin__` | `AuditLogEntry.ADMIN_TENANT` | Authenticated (400d default) | **Never sampled** | `AuthInterceptor.validateAdminKey()` success — any admin-key-authenticated request. Written on both success and failure paths. |
| `__unauth__` | `AuditLogEntry.UNAUTH_TENANT` | Unauthenticated (30d default) | Subject to `audit.sample.unauthenticated` | Pre-auth failure — missing/invalid/revoked key, admin-key absent, path traversal rejected before controller runs. |
| `<unauthenticated>` | `AuditLogEntry.LEGACY_UNAUTHENTICATED_TENANT` | Unauthenticated (30d default) | N/A (no new writes) | **No longer emitted.** Historical rows from v0.1.25.20..v0.1.25.27 carry this literal; `AuditRepository.resolveTtlSeconds()` routes them to the unauthenticated tier so they age out on the correct schedule. |

**URL-safe format.** The new delimiters use double underscores, not angle brackets. Tenant grammar (`^[a-z0-9-]+$`) excludes underscores, so no collision with a real tenant id is possible; underscores are RFC 3986 unreserved, so `GET /v1/admin/audit/logs?tenant_id=__admin__` needs no percent encoding. The previous `<unauthenticated>` required URL-encoding (`%3Cunauthenticated%3E`) and produced cosmetically-broken log IDs and URLs in ops tooling.

**Cutover behavior.** Because underscores can't appear in a legal tenant id, any auditor query or dashboard filter hard-coded to `<unauthenticated>` will stop matching fresh writes the moment 0.1.25.28 ships — but will still match the historical rows that actually carry that literal. Queries should migrate to `?tenant_id=__unauth__` (for the pre-auth-failure slice) and gain a new option `?tenant_id=__admin__` (for the admin-plane slice). Historical rows are not rewritten; they simply age out under the unauth-tier TTL.

**Per-file changes:**

| File | Change |
|---|---|
| `cycles-admin-service/pom.xml` | `<revision>0.1.25.27</revision>` → `<revision>0.1.25.28</revision>` |
| `.../model/audit/AuditLogEntry.java` | Three public constants: `UNAUTH_TENANT = "__unauth__"`, `ADMIN_TENANT = "__admin__"`, `LEGACY_UNAUTHENTICATED_TENANT = "<unauthenticated>"`. The legacy constant is referenced only by the data layer's TTL resolver — no other code should write with it. |
| `.../api/config/AuthInterceptor.java` | `validateAdminKey()` stamps `request.setAttribute("authenticated_actor_type", "admin")` on success — **not** `authenticated_tenant_id`. Rationale: several downstream controllers (`BudgetController`, `PolicyController`, `WebhookTenantController`) use `authenticated_tenant_id == null` as their "is this an admin-on-behalf-of request?" discriminator; stamping a sentinel there breaks their admin-vs-tenant branching. Admin-plane requests now carry a distinct marker attribute the audit writer can read without disturbing controller dispatch. |
| `.../api/service/AuditFailureService.java` | Renamed `UNAUTHENTICATED_TENANT` → `UNAUTH_TENANT` (source of truth is now the model constant; the field is kept as a convenience alias). `resolveTenantId()` now has a two-branch fallback: real `authenticated_tenant_id` (tenant-key auth) → returned verbatim; else `authenticated_actor_type == "admin"` (admin-key auth) → `__admin__`; else → `__unauth__`. The sampling gate only fires on `__unauth__` — `__admin__` persists at full fidelity. |
| `.../data/repository/AuditRepository.java` | `resolveTtlSeconds()` routes `__unauth__` AND legacy `<unauthenticated>` to the unauthenticated tier; everything else (including `__admin__`) rides authenticated-tier retention. |
| `cycles-governance-admin-v0.1.25.yaml` (cycles-protocol) | `info.version` bumped `0.1.25.24` → `0.1.25.25`. Added sentinel description on `AuditLogEntry.tenant_id` schema and on `listAuditLogs` `tenant_id` query param. CHANGELOG entry documenting the URL-safe format and forward-compat client contract. |

**Test coverage delta:** `AuditRepositoryTest` gains two new cases — `resolveTtlSeconds_adminSentinel_usesAuthenticatedRetention` (new `__admin__` rides long tier) and `resolveTtlSeconds_legacyUnauthenticatedSentinel_usesShortRetention` (historical rows still short-tier). `AuditFailureServiceTest` gains `logFailure_adminActorType_usesAdminSentinel` (actor_type="admin" attribute → `__admin__` tenant_id) and `logFailure_adminActorType_notSampledOutEvenAtHighRate` (high sampling rate still records all admin-plane failures). `AuthInterceptorTest` extended four admin-path assertions to also verify `authenticated_actor_type == "admin"` while keeping the existing `authenticated_tenant_id IS NULL` invariant that downstream controllers depend on. Three integration tests updated their Redis index-key literals (`audit:logs:<unauthenticated>` → `audit:logs:__unauth__`). Admin-plane failure entries in `AdminFlowIntegrationTest` now assert `tenant_id == __admin__` end-to-end.

**Forward-looking client contract.** The cycles-protocol CHANGELOG for v0.1.25.25 documents the sentinels as stable names that clients MAY rely on for query parameters. Adding a fourth sentinel in the future remains backwards-compatible — existing queries for `__admin__` / `__unauth__` continue to work; only new auditor flows need to learn about the new value.

---

### 2026-04-17 — v0.1.25.27 audit filter DSL upgrade (spec v0.1.25.24)

Ops auditors cannot slice `listAuditLogs` the way their work demands today — `error_code` is unfilterable, `operation` / `resource_type` accept only exact match, there is no status range, and `search` misses the two fields auditors most want to grep (error codes and operation IDs). This release lands a consistent filter DSL on `GET /v1/admin/audit/logs`: the **promoted string filters** (`operation`, `resource_type`, `error_code`, `error_code_exclude`) are exact-or-IN-list, and the `status` filter is exact-or-range; filters AND-compose, and within one filter an IN-list is OR. `tenant_id`, `key_id`, and `resource_id` remain exact-match this revision (see Not-promoted block below).

**Concrete auditor flows this release unblocks:**

| Question | Before | After |
|---|---|---|
| "All BUDGET_EXCEEDED + TENANT_SUSPENDED in last hour" | Two passes, client-side merge | `?error_code=BUDGET_EXCEEDED,TENANT_SUSPENDED&from=…` |
| "All 5xx yesterday" | Page everything, filter client-side | `?status_min=500&status_max=599&from=…` |
| "All failures except noisy INTERNAL_ERROR" | Not possible | `?error_code_exclude=INTERNAL_ERROR&status_min=400` |
| "All createBudget + updateBudget on tenant X" | Two passes | `?operation=createBudget,updateBudget&tenant_id=X` |
| "Find entries mentioning 'budget'" | Missed error_code / operation | `?search=budget` now matches both fields too |

**Per-param semantics** (`AuditController.list`, spec `listAuditLogs`):

| Param | Shape | Semantics |
|---|---|---|
| `error_code` | `array<string>`, maxItems 25, `explode=false` | Exact-or-IN-list. Case-sensitive. **NULL `entry.error_code` never matches** — auditor asking "show me code X" never wants success rows. Unknown codes match nothing (forward-compat: a newer client sending a newly-added enum value doesn't 400 against an older server). |
| `error_code_exclude` | `array<string>`, maxItems 25, `explode=false` | NOT-IN-list. **NULL `entry.error_code` always passes** — hiding noisy codes shouldn't also hide successes. May combine with `error_code` (AND-composed: "narrow to set A, minus subset B"). |
| `status_min` | `integer`, 100..599 | Inclusive lower bound. Mutex with exact `status`. NULL `entry.status` is treated as out-of-range (does not silently pass). |
| `status_max` | `integer`, 100..599 | Inclusive upper bound. Mutex with exact `status`. `status_min <= status_max` enforced. |
| `operation` | `array<string>`, maxItems 25, `explode=false` | Promoted from `string`. Formal wire contract is the `explode=false` comma-separated form; a single scalar `?operation=createBudget` still parses as a one-element list (byte-identical back-compat for scalar-sending clients). Spring's `@RequestParam List<String>` binding additionally accepts the repeated form `?p=a&p=b` as an implementation convenience, but the spec documents this as a `MAY` — clients MUST NOT rely on repeated form for portability. |
| `resource_type` | `array<string>`, maxItems 25, `explode=false` | Same promotion + same scalar-back-compat / repeated-form-MAY story as `operation`. |
| `search` | `string`, ≤128 chars (unchanged) | Case-insensitive substring — now matches `resource_id` OR `log_id` OR `error_code` OR `operation` (was only the first two). |

**Not promoted** (scope discipline): `tenant_id` / `key_id` (natural keys with cursor-stability implications), `resource_id` (high-cardinality, IN-list has little auditor value). Left exact-match.

**Validation (controller edge, uniform 400 `INVALID_REQUEST` shape).** Cross-param constraints OpenAPI can't express:

- `status` exact MUST NOT combine with `status_min` or `status_max` → 400.
- `status_min` and `status_max` each in `[100, 599]` → else 400.
- `status_min <= status_max` when both present → else 400.
- Each IN-list param normalised via the shared `parseCodeList(List<String>, String paramName)` helper: flatten (comma-splits each element) → trim → drop empties → dedupe via `LinkedHashSet` (first-seen wins) → cap at `MAX_LIST_PARAM_VALUES = 25` → else 400 with `"<param> exceeds maxItems 25 (got <N>)"`. Single try/catch wraps all four `parseCodeList` calls into `GovernanceException(INVALID_REQUEST, ..., 400)` to mirror `parseSortSpec` / `parseSearch` / `BudgetController.listBudgets` shape.

**NULL-semantics contract (lives in `AuditRepository.matchesFilters` javadoc).** The IN-list vs NOT-IN asymmetry is deliberate and asymmetric:

- `errorCodes` (IN-list): null `entry.errorCode` → reject. Success entries excluded when filter asks "show me failure X".
- `errorCodeExcludes` (NOT-IN-list): null `entry.errorCode` → accept. Hiding noisy codes never silently hides successes.
- `statusMin` / `statusMax`: null `entry.status` → reject. A null can't satisfy either bound; silently passing bounds-holding entries through would break the auditor mental model.

**Cursor-stability invariant preserved (v0.1.25.25).** All new predicates are applied **before** cursor commitment on both paths:

1. Time-indexed (`listByTimestamp`) — the `zrevrangeByScore` / `zrangeByScore` walk hydrates each ID, runs `matchesFilters(entry, keyId, operations, status, resourceTypes, resourceId, errorCodes, errorCodeExcludes, statusMin, statusMax)` then `matchesSearch`, and only then appends to the result list. Cursor resumption continues to use the score-based `minScore / maxScore` boundary — so a second page with the same filter set returns the strict suffix of the first.
2. Non-timestamp sort (`listSortedNonTimestamp`) — filters applied inside the `SORTED_HYDRATE_CAP = 2000` hydrate loop, before the in-memory sort and `log_id` cursor walk. Same stability guarantee.

Verified by `AuditRepositoryTest.list_newFilterCursorStable_secondPageSkipsFirstPage`: walks a 3-row filtered fixture across two pages (`operation=createBudget` + `error_code=BUDGET_EXCEEDED` + `status_min=400 max=499`), asserts `page2` contains no `page1` IDs.

**Forward-compat typing.** `error_code` / `error_code_exclude` deliberately declared as `array<string>` rather than `$ref: ErrorCode` in the spec. A newer spec release that extends the `ErrorCode` enum must not cause a pre-enum server to reject the request at the OpenAPI validator layer — unknown codes match nothing at the filter layer, which is the contract cross-version clients need. `ErrorCodeConstants` stays as the canonical authoritative set for audit entries the server itself writes.

**Back-compat (type promotion of `operation` / `resource_type`).** Repository keeps three shorter overloads (`list(...)` with 10, 11, 12 args) that forward to the new 16-arg canonical with trailing nulls — same pattern used for v0.1.25.20 `SortSpec` and v0.1.25.21 `search`. Single-value URL form (`?operation=createBudget`) still parses into a one-element list at the Spring binding layer, so existing dashboard URLs work unchanged.

**Tests.** Coverage added in both modules:

- `AuditRepositoryTest` (+13 behavioural tests): IN-list matches for `operation` / `resource_type`; `errorCodes` rejecting null; `errorCodeExcludes` passing null; combined IN-and-exclude AND-composition; `status` range inclusive boundaries (399/400/450/499/500 fixture asserting `[log_2, log_3, log_4]`); `status_min` only / `status_max` only as half-open ranges; null `entry.status` rejected by the range; `search` matches `error_code` substring; `search` matches `operation` substring; all-filters-combined happy path; cursor stability under the new filter set; empty-list sentinel behaves identically to null. Three mechanical type-promotion updates (`"create"` → `List.of("create")`, `"tenant"` → `List.of("tenant")`).
- `AuditControllerTest` (rewritten to 16-arg matchers + ~21 new behavioural tests): single-value / comma-separated / repeated-param parse paths for `error_code` and `error_code_exclude`; empty-after-trim → treated as absent; over-cap (26 values) → 400; `status_min` / `status_max` happy path threading; each bound out-of-range (<100, >599) → 400; `status_min > status_max` → 400; exact `status` combined with `status_min` → 400; exact `status` combined with `status_max` → 400; `operation` / `resource_type` IN-list threading; single-value back-compat still threads; over-cap on list params → 400; combined-filter happy path threads all sixteen repository args. A `stubAnyListReturns(repo, List<AuditLogEntry>)` helper collapses the boilerplate where the controller test doesn't care about the exact thread-through values.
- `RedisIntegrationTest` (one mechanical update — `"createTenant"` → `List.of("createTenant")` at line 638).

**Verification.** Local `mvn clean verify` (`-Dtest='!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`): **667 tests pass, 0 failures, 0 errors** across all three modules. JaCoCo LINE coverage rule met on both `cycles-admin-service-data` and `cycles-admin-service-api` at **≥95%**. Build SUCCESS. `SpecCoverageReportTest` reports `declared=45 covered=45 missing=0` against the current-main spec; post spec-PR merge the four new query params land on the existing `listAuditLogs` operation (same `operationId`, no new operations added — the op stays covered).

Cloud CI verification requires the cycles-protocol spec PR (bumping spec `info.version` to `0.1.25.24`) to merge to `main` first — the `ContractSpecLoader` fetches from `main`. Local contract-test iteration uses `-Dcontract.spec.url=file:///…` against a locally-patched spec, same flow as v0.1.25.26.

**Future work (Tier 2 — NOT in this release).** The DSL locked in by this release is the contract the following Tier 2 endpoint reuses verbatim. Naming it here so a future reviewer doesn't re-invent the query shape:

```
GET /v1/admin/audit/logs/facets?group_by={error_code|status|operation|resource_type}
    &from=…&to=…
    [all Tier 1 filter params may be passed — they narrow the base set]
```

Returns per-bucket counts (top-N errors today, 5xx rate by operation) without paging entries. **Explicit reuse guarantee:** `error_code`, `error_code_exclude`, `status_min`, `status_max`, `operation`, `resource_type`, `from`, `to`, `search` work identically on `/facets` — same parser, same validation, same NULL semantics. That promise is the main reason the Tier 1 DSL was designed the way it was, instead of a one-off `error_code` flag. Deferred from this release because (a) dashboard aggregation UI isn't committed yet and (b) the underlying sorted-set walk would want a streaming count primitive rather than the `SORTED_HYDRATE_CAP` bounded hydrate — separate performance work.

**Tier 3 — flagged, not designed.** `error_category` enum (QUOTA / AUTH / UPSTREAM / INVALID_INPUT / INFRA) server-derived from `error_code` — needs ops to name categories first + parity with cycles-server. CSV export (`Accept: text/csv`) for compliance audits. Saved audit queries. Not worth inventing unilaterally.

### 2026-04-17 — v0.1.25.26 bulk-action endpoints on tenants + webhooks (spec v0.1.25.21 second bullet)

Closes the remaining bullet of governance spec v0.1.25.21: filter-driven bulk lifecycle actions on tenants and webhook subscriptions. Paired with [cycles-protocol PR #51](https://github.com/runcycles/cycles-protocol/pull/51) — spec v0.1.25.23 — which adds `COUNT_MISMATCH` and `LIMIT_EXCEEDED` to the `ErrorCode` enum. The v0.1.25.21 prose already required those codes; PR #51 just lands the enum-consistency fix so response validators don't reject the spec-compliant server.

**Two new endpoints** (both `AdminKeyAuth` only, no dashboard/ApiKey path):

| Endpoint | Actions | Filter fields |
|---|---|---|
| `POST /v1/admin/tenants/bulk-action` | `SUSPEND`, `REACTIVATE`, `CLOSE` | `status`, `parent_tenant_id`, `observe_mode`, `search` |
| `POST /v1/admin/webhooks/bulk-action` | `PAUSE`, `RESUME`, `DELETE` | `tenant_id`, `status`, `event_type`, `search` |

Request shape: `{ filter, action, expected_count?, idempotency_key }` — matches the v0.1.25.21 contract exactly. Response shape: `{ action, total_matched, succeeded[], failed[], skipped[], idempotency_key }` with `BulkActionRowOutcome = { id, error_code?, message?, reason? }` in each array.

**Safety gates (all four land in the same controller path for both endpoints):**

1. **Empty filter → 400 `INVALID_REQUEST`.** Controllers call `filter.isEmpty()` before anything else; a filter with every property null / blank is rejected with `"filter must contain at least one property"` so operators can't accidentally match-everything.
2. **Idempotency replay → cached response.** The new shared `IdempotencyStore` (see below) is consulted before any match/mutation work. A cache hit returns the original `{action, total_matched, ...}` envelope verbatim — no re-match, no re-write. 15-minute TTL per the spec; keyed by `(endpoint, idempotency_key)` so the tenant and webhook namespaces don't collide.
3. **>500 matches → 400 `LIMIT_EXCEEDED`.** `TenantRepository.matchForBulk(...)` and `WebhookRepository.matchForBulk(...)` both scan their respective candidate sets with a `cap + 1` early-exit — once the 501st match lands, the scan stops. Controller sees `matched.size() > 500` and throws `GovernanceException` with `details: { total_matched: <N> }` so operators know how far over they are and can narrow the filter. No writes performed.
4. **`expected_count` mismatch → 409 `COUNT_MISMATCH`.** When the client supplies `expected_count`, server compares it to the actual match size *before* any write. Mismatch throws `GovernanceException(COUNT_MISMATCH, ..., details: { total_matched })`. Spec-mandated anti-footgun gate for the preview→submit workflow (dashboard shows "49 match", operator clicks SUSPEND, filter drifted between preview and submit → server rejects instead of silently suspending 50 or 48).

**Per-row apply semantics** (ordered: state check reads **live** status, not the matched snapshot — prevents lost updates when a row transitions between match and apply):

- **Tenants.** `SUSPEND`: ACTIVE → SUSPENDED (`succeeded`), SUSPENDED → `skipped(ALREADY_IN_TARGET_STATE)`, CLOSED → `failed(INVALID_TRANSITION)`. `REACTIVATE`: mirror of SUSPEND. `CLOSE`: any non-closed → CLOSED (`succeeded`); already CLOSED → `skipped`.
- **Webhooks.** `PAUSE`: ACTIVE → PAUSED (`succeeded`); PAUSED / DISABLED → `skipped(ALREADY_IN_TARGET_STATE)`. `RESUME`: PAUSED → ACTIVE (`succeeded`); ACTIVE → `skipped`; DISABLED → `failed(INVALID_TRANSITION)` (disabled means the server killed delivery, not operator-pause; resuming is explicit re-enable only). `DELETE`: removes the subscription; if the row already 404'd by the time apply runs (concurrent delete between match and apply) → `skipped(ALREADY_DELETED)`.

Per-row failures never abort the batch: overall response is **HTTP 200** with the envelope split into `succeeded[] + failed[] + skipped[]`, and `succeeded.size + failed.size + skipped.size == total_matched`.

**New shared primitive: `IdempotencyStore`** (`cycles-admin-service-data/.../idempotency/IdempotencyStore.java`). Redis-backed, `JedisPool` + `ObjectMapper` injected. Key shape `idem:{endpoint}:{key}`; value is the serialized response envelope. `TTL_SECONDS = 900` (15 min). Public API is two methods:

- `lookup(endpoint, key, Class<T>) → Optional<T>` — corrupt / unparseable entries degrade to `Optional.empty()` instead of throwing, so a stale-schema cache entry from a previous release can't brick the endpoint.
- `store(endpoint, key, envelope)` — failure to write is **logged, not thrown**. The mutation already succeeded by the time we cache the response; losing the idempotency entry is a degraded-experience issue (the second retry will re-apply), not a correctness bug. Throwing here would turn a successful bulk-action into a user-visible 500.

**Deliberate design deviation from the release plan.** The review plan (`review-admin-0-1-25-spec-indexed-dewdrop.md` Cross-Cutting #1) called for "extract a reusable IdempotencyStore… both bulk endpoints register against it. The existing `BudgetController.fund` path MUST be migrated onto the shared store in the same PR." On inspection, the fund-path idempotency lives inside a Redis Lua script (`BudgetRepository` lines 58-153) that atomically (a) checks the idempotency key and (b) mutates the balance in a single round-trip. Externalizing the idempotency check to a separate `IdempotencyStore` call would split that atomicity — a concurrent retry could slip between the `lookup` call and the Lua script running, apply the mutation twice, and corrupt the balance. The safety guarantee of the Lua atomic is load-bearing, not incidental.

Decision: kept fund-path untouched; built the new `IdempotencyStore` for bulk-action only. `IdempotencyStore.java`'s javadoc documents this rationale inline (not just here) so a future reviewer doesn't re-introduce the "unify them" idea without seeing the tradeoff. This diverges from the plan's hard `MUST`, but the plan's rationale ("otherwise two idempotency implementations diverge forever") assumed both implementations were layered the same way — they aren't. The two coexist on purpose.

**Spec lineage: paired PRs.** The server implementation is spec-compliant with v0.1.25.21's prose. But v0.1.25.22's `ErrorCode` enum didn't list `COUNT_MISMATCH` or `LIMIT_EXCEEDED`, so response validators would reject the spec-compliant server. Caught mid-release by the `OpenApiContractDiffTest` contract validator. Fix shipped as cycles-protocol PR #51 → spec v0.1.25.23 (enum widening; purely additive). Release ordering: the spec PR MUST merge to cycles-protocol `main` **before** this branch can pass server CI (the `ContractSpecLoader` fetches from `main`). Local verification for this PR used a cache-seeded copy of the patched spec.

**Tests.** Coverage added across model, data, and api modules:

- `cycles-admin-service-model/.../shared/BulkActionRowOutcome.java` + `tenant/TenantBulk*` + `webhook/WebhookBulk*` — DTOs with `@JsonInclude(NON_NULL)` on optional fields so absent `error_code` / `reason` don't bloat the wire.
- `TenantControllerTest` (+16 bulk-action tests): suspend happy-path, empty-filter 400, search-over-128 400, expected_count mismatch 409 (asserts `$.details.total_matched`), >500 matches 400 LIMIT_EXCEEDED (asserts `$.details.total_matched` = 501 via seeded oversized list), idempotency replay (asserts `matchForBulk`, `update`, and `idempotencyStore.store` all `never()` invoked on replay), already-in-target-state → skipped, closed tenant SUSPEND attempt → failed INVALID_TRANSITION, missing idempotency_key 400, no admin key 401, CLOSE from ACTIVE succeeds; additional classify-by-error-code coverage (PERMISSION_DENIED / INVALID_TRANSITION / NOT_FOUND), REACTIVATE-from-CLOSED → failed INVALID_TRANSITION, and generic-exception → INTERNAL_ERROR.
- `WebhookAdminControllerTest` (+16 bulk-action tests): pause happy-path, empty-filter 400, search-over-128 400, expected_count mismatch 409, >500 matches 400 LIMIT_EXCEEDED, idempotency replay, RESUME from DISABLED → failed INVALID_TRANSITION (webhook-specific semantic that spec demands), PAUSE already-paused → skipped, DELETE on missing row → skipped ALREADY_DELETED (concurrent-delete correctness), DELETE happy-path, missing idempotency_key 400, no admin key 401; additional RESUME-from-PAUSED happy-path, RESUME-already-ACTIVE → skipped, concurrent-delete-during-PAUSE (findById throws) → skipped ALREADY_DELETED, DELETE non-NOT_FOUND GovernanceException → failed PERMISSION_DENIED, generic-exception → INTERNAL_ERROR.
- `IdempotencyStoreTest` (9 tests): cache-miss returns empty, cache-hit deserialises, corrupt JSON returns empty, `JsonProcessingException` returns empty, Redis throw returns empty, store writes with 15-min TTL (long overload), serialisation failure swallowed, Redis failure swallowed, TTL constant = 900.
- `TenantRepositoryTest` / `WebhookRepositoryTest` (+8 / +10 `matchForBulk` tests each): no-filter scan, status filter, tenant_id / parent / scope filters, search substring matching both fields, missing row skipped, corrupt JSON skipped (loop continues), blank-tenantId fallback to global set, `cap+1` sentinel stops iteration without hydrating the extra row.
- `SpecCoverageReportTest` + `OpenApiContractDiffTest` allowlists are now both `Set.of()` — bulk-action entries removed. Javadoc comment updated from "planned for server release 0.1.25.26" to "v0.1.25.26 closed the bulk-action gap — allowlist is now empty."

**Verification.**

- Local `mvn verify` (`-Dtest='!*IntegrationTest'`) with a cache-seeded v0.1.25.23 spec: **646 tests pass, 0 failures, 0 errors** across all three modules (model, data, api). JaCoCo LINE coverage rule met on both `cycles-admin-service-data` and `cycles-admin-service-api` at **≥95%** (data module covers the new `IdempotencyStore` 25/25 and `matchForBulk` paths; api module covers the bulk-action controllers end-to-end including the classify-by-error-code branches and generic-exception fallback). Build SUCCESS.
- Cloud CI verification requires cycles-protocol PR #51 to merge to `main` first (spec-fetch ordering). Gating test: the tests that assert `$.error = "COUNT_MISMATCH"` / `$.error = "LIMIT_EXCEEDED"` fail against the pre-PR-51 spec with the validator message "Instance value not found in enum" — green once PR #51 lands.

### 2026-04-17 — v0.1.25.25 free-text `search` on six admin list endpoints (spec v0.1.25.21 first bullet)

Closes the first bullet of governance spec v0.1.25.21: every admin list endpoint now accepts an optional `search` query param — case-insensitive substring match, ≤128 characters, AND-combined with every other filter. The remaining bullet of v0.1.25.21 (bulk-action endpoints) is deferred to v0.1.25.26 per the `review-admin-0-1-25-spec-indexed-dewdrop.md` two-release sequencing.

**Spec-first lineage.** Spec content was finalized at v0.1.25.21 (2026-04-17 cycles-protocol); v0.1.25.22 is editorial-only cleanup of impl-version markers. Target `info.version` for this release is `0.1.25.22` — the current authoritative spec version — though the normative `search` content lives in the v0.1.25.21 CHANGELOG entry.

**Endpoints + per-endpoint match fields.** All six list endpoints route `search` through the shared `SearchSpec.resolve(String)` validator then thread it into the repository as a typed-optional argument. Match fields are **OR**-combined within an endpoint, and the whole `search` predicate is **AND**-combined with every other filter on that endpoint.

| Endpoint | Match fields (OR) |
|---|---|
| `listTenants` | `tenant_id`, `name` |
| `listBudgets` | `tenant_id`, `scope` |
| `listApiKeys` | `key_id`, `name` |
| `listAuditLogs` | `resource_id`, `log_id` |
| `listWebhookSubscriptions` | `subscription_id`, `url` |
| `listEvents` | `correlation_id`, `scope` |

**Shared validator.** New under `cycles-admin-service-model/.../model/shared/`:

- `SearchSpec` — holds `MAX_LENGTH = 128` and two static helpers: `resolve(String raw)` applies the validator in a **fixed order of `trim → empty-check → length-check`** so trailing/leading whitespace cannot bypass the 128-char cap, and `matches(String haystack, String needle)` runs the case-insensitive substring match used by every repository. Null raw input returns null (absent); empty-post-trim returns null (treated as absent); over-cap post-trim throws `IllegalArgumentException("search exceeds maxLength 128")` which the controller maps to 400 `INVALID_REQUEST`.

Every controller calls `SearchSpec.resolve(request.getSearch())` at the edge and passes the normalized value (or null) to the repository. The validator contract is locked down by `SearchSpecTest` (watch-item #3) so a reordering regression — length-check before trim — is caught before merge.

**Three repository paths.** The implementation mirrors the v0.1.25.24 sort shape, piggy-backing on the infrastructure that release introduced:

1. **Time-indexed (Events, Audit).** `search` folds into the existing `SORTED_HYDRATE_CAP = 2000` hydrate path introduced in v0.1.25.20 for non-primary sort. In the common default-sort (timestamp DESC) path, the repository iterates the `zrevrangeByScore` stream, applies `matchesSearch(entry, search)` per hydrated entry, and stops at the limit — filter happens **before** cursor-position is committed so cursor chains stay stable across pages with the same `search`.
2. **Set-backed single-tenant (Tenants, ApiKeys single-tenant, Webhooks single-tenant).** `search` bolts onto the existing in-memory filter composition. Filter runs on the hydrated candidate set, then the legacy `SortSpec`-or-default sort and cursor walk proceed.
3. **Cross-tenant (ApiKeys cross-tenant, Budgets cross-tenant).** `search` applies inside the `SORTED_HYDRATE_CAP`-capped cross-tenant hydration path added in v0.1.25.24. When the cap is hit, the warning log already in place still fires; `search` does not change hydration ordering.

`BudgetRepository.list(...)` uses a typed `BudgetListFilters` record for its filter grouping — `search` is added as a new field on that record, so the repository signature stayed 5-arg and no existing mock in `BudgetRepositoryTest` / `BudgetControllerTest` needed arg-count edits.

**Cursor stability under `search`.** Watch-item #1 of the review plan: the repository applies the `search` predicate **before** cursor evaluation on every path. A second page with the same `search` value returns the strict suffix of the first; a second page with a different `search` value is a new logical query and chains correctly off the same cursor (the cursor encodes a position in the underlying time-indexed or set-backed source, not in the `search`-filtered view). Tested explicitly in `EventRepositoryTest.list_searchCursorStable_secondPageSkipsFirstPageIds` and `AuditRepositoryTest.list_searchCursorStable_secondPageSkipsFirstPageIds` — both walk a 3-row fixture across two pages and assert `page2` contains no `page1` IDs.

**Backward compatibility.** Every repository keeps its pre-search `list(...)` signature as a thin shim that delegates to the new search-accepting overload with `null` search. Response shape and cursor semantics are unchanged for every endpoint when `search` is absent. Callers omitting `search` see byte-identical wire output to v0.1.25.24.

**Tests.** Coverage added across model, data, and api modules:

- `cycles-admin-service-model/.../SearchSpecTest` (NEW) — 9 test methods covering `resolve` null/empty/whitespace/trim/at-cap/over-cap/validator-ordering and `matches` case-insensitive + null-safety semantics. The validator-ordering test `resolve_lengthCheckRunsAfterTrim_trailingWhitespaceCannotBypassCap` is watch-item #3's regression lock.
- `cycles-admin-service-data` — cursor-stability watch-item tests added to `EventRepositoryTest` (+4) and `AuditRepositoryTest` (+4). Per-repository search happy-path (correlation_id / scope for events, resource_id / log_id for audit), case-insensitive confirmation, AND-composition with other filters, and the page1→page2 cursor walk.
- Per-controller mock signature updates: Mockito strict stubbing flagged every `verify(...).list(...)` call after the repository signatures grew by one arg; all six controller test classes updated with the correct arg-position insertion (position 3 for `TenantController`, end-of-args for the others; `BudgetController` unchanged because it packs filters into a record).
- Per-controller `searchOver128Chars_returns400` behavioural test (6 total — one per controller): sends `search=<129 chars>` to each list endpoint and asserts HTTP 400 `INVALID_REQUEST` plus `verify(repo/service, never()).list(...)` so a future regression that drops `parseSearch` or mis-threads `searchNorm` through to the repository is caught at the controller layer (not just the validator layer).
- `SpecCoverageReportTest` + `OpenApiContractDiffTest` — both gain a `KNOWN_MISSING` allowlist for the two bulk-action endpoints (tenants/webhooks) that spec v0.1.25.22 declares but won't ship server-side until v0.1.25.26. Entries include a pointer to the release plan so reviewers know why they're present and when to remove them.

**JaCoCo ≥95% LINE coverage gate.** Both `data` and `api` modules inherit the parent pom BUNDLE rule `<minimum>0.95</minimum>`; the `model` module skips the check because it's DTO-only. The gate is green for this release.

**Dashboard alignment.** No dashboard wire-up is part of this release. The dashboard's client-side filter today continues to work; a follow-up track against `cycles-dashboard` will wire the six list views onto `?search=…` the same way the v0.1.25.24 sort alignment was handled.

### 2026-04-16 — v0.1.25.24 server-side sort (six admin list endpoints)

Closes dashboard scale-hardening gap **V4** (client-side sort on partial data is misleading — a user paginating through a 10k-tenant list sees "sorted by created_at desc" but rows 5000–6000 are silently absent with no indication). Governance spec v0.1.25.20 (cycles-protocol PR #49) adds `sort_by` + `sort_dir` query params across the six admin list endpoints, each with its own per-endpoint whitelist of sortable fields. This release wires the server side to match.

**Spec-first lineage.** Spec PR #49 was merged before any server work. This release implements exactly that contract — no shape drift, no whitelist drift. The `cycles-governance-admin-v0.1.25.yaml` CHANGELOG entry at `0.1.25.20 (2026-04-16)` describes the normative surface; this section describes only how the server honours it.

**Endpoints + whitelists.** All six admin list endpoints now accept `sort_by` + `sort_dir` optional query params. `sort_dir` ∈ {`asc`, `desc`} (lowercase, wire-encoded via `@JsonValue` on `SortDirection`; `SortDirection.fromWire` is case-insensitive but emits the wire enum for round-tripping). Per-endpoint `sort_by` whitelists are enforced at the controller boundary — unknown fields → 400 `INVALID_REQUEST`. Missing `sort_dir` → DESC (spec default, applied in `SortSpec.resolve`).

| Endpoint | Whitelist | Default |
|---|---|---|
| `listTenants` | `tenant_id`, `name`, `status`, `created_at` | `created_at DESC` |
| `listApiKeys` | `key_id`, `name`, `tenant_id`, `status`, `created_at`, `expires_at` | `created_at DESC` |
| `listBudgets` | `tenant_id`, `scope`, `unit`, `status`, `commit_overage_policy`, `utilization`, `debt` | `utilization DESC` |
| `listWebhookSubscriptions` | `url`, `tenant_id`, `status`, `consecutive_failures` | `consecutive_failures DESC` |
| `listEvents` | `event_type`, `category`, `scope`, `tenant_id`, `timestamp` | `timestamp DESC` |
| `listAuditLogs` | `timestamp`, `operation`, `resource_type`, `tenant_id`, `key_id`, `status` | `timestamp DESC` |

Non-`created_at` / non-`timestamp` defaults reflect per-endpoint operator ergonomics: `listBudgets` defaults to `utilization DESC` so hot budgets surface first in the dashboard's default view; `listWebhookSubscriptions` defaults to `consecutive_failures DESC` so flaky subscriptions surface first for operators triaging webhook health. Callers that want `created_at DESC` on budgets/webhooks can pass `sort_by=…` explicitly — these defaults are the server's opinion, not a wire constraint.

**Shared sort foundation.** New under `cycles-admin-service-model/.../model/shared/`:

- `SortDirection` enum (`ASC`, `DESC`) with `@JsonValue` wire-form lowercase and `fromWire(String)` factory that throws `IllegalArgumentException` on unknown input. Unknown `sort_dir` → controller catches → 400 `INVALID_REQUEST`.
- `SortSpec` record (`field`, `direction`) with `resolve(rawField, direction, allowedFields, defaultField)` factory that (1) validates `rawField` against the endpoint whitelist, (2) falls back to `defaultField` when `rawField` is null/blank, and (3) throws `IllegalArgumentException` on whitelist violation. Also exposes `of(field, direction)` for tests and `isAscending()` as a readability helper.

Every controller threads `sort_by`/`sort_dir` through `parseSortSpec(...)` → `SortSpec` → repository. The repository contract is: null `SortSpec` preserves the endpoint's legacy cursor walk verbatim; otherwise branch on `field == <primary timestamp-like field>` vs other whitelisted fields.

**Three repository paths (non-reservations).** For each endpoint, the repository has the same shape:

1. **Null SortSpec OR field == default primary (DESC)** → existing `zrevrangeByScore` walk (Events, Audit) or `SMEMBERS` + in-memory sort (Tenants, ApiKeys, Budgets, Webhooks). Cursor chains unchanged.
2. **Default primary field, ASC direction** → mirrored walk — `zrangeByScore` for time-indexed, reverse in-memory sort for set-backed — with the cursor's score as the new minScore/maxScore floor so ASC cursor pages chain correctly.
3. **Non-primary whitelisted fields** → for time-indexed endpoints (Events, Audit), hydrate up to `SORTED_HYDRATE_CAP = 2000` IDs from the filtered time window, apply all filters, sort in-memory by a null-safe `<entity>Comparator(SortSpec)`, then walk the primary-key cursor. For set-backed endpoints (Tenants, ApiKeys, Budgets, Webhooks), hydrate the full filter-matching set, sort, walk. The cap exists because non-timestamp sort needs to see the full filter-matching population before the cursor walk; callers on very large windows should narrow `from`/`to` or add filters.

**Cross-tenant sort hydration cap.** The `listAllTenants` sorted path on `ApiKeyRepository` and `BudgetRepository` (cross-tenant admin-key list modes) also applies `SORTED_HYDRATE_CAP = 2000` to protect the admin pane from memory exhaustion at scale (10k tenants × N keys/budgets each is an unbounded product otherwise). When the cap is hit, hydration stops and a WARN is logged; the cursor page still fills from the capped slice, but rows beyond the cap are not visible in that sort window. Callers that need to see past the cap must narrow the filter (status, tenant_id, utilization range, unit, etc.) until the filter-matching population fits under 2000. Single-tenant `list` sorted paths are not capped because the tenant's key/budget cardinality is inherently bounded.

Per-entity comparators are all null-safe on every whitelisted field, with a primary-key tie-breaker (`tenant_id`, `key_id`, `ledger_id`, `whsub_id`, `event_id`, `log_id`) so cursor resume is total-order deterministic. Unknown fields fall through to the tie-breaker; the controller whitelist already blocks those at the edge.

**Backward compatibility.** Every repository keeps its pre-sort `list(...)` signature as a thin shim that delegates to the new SortSpec-accepting overload with `null` SortSpec — this preserves every repository call site and every existing mock in the test suite without rewrites. Response shape and cursor semantics are unchanged for every endpoint.

Row-order behavioural change to flag for **`listBudgets`** and **`listWebhookSubscriptions`** only: because both controllers always pass a non-null resolved `SortSpec` (never `null`) into the repository, and their new server defaults are `utilization DESC` and `consecutive_failures DESC` respectively (not `created_at DESC`), the default row order for these two endpoints changes versus pre-0.1.25.24 when the caller omits `sort_by`/`sort_dir`. Callers that had pinned to the prior order must pass `sort_by=created_at&sort_dir=desc` explicitly on these two endpoints. The other four endpoints (`listTenants`, `listApiKeys`, `listEvents`, `listAuditLogs`) keep their legacy default row order verbatim because their defaults (`created_at DESC` / `timestamp DESC`) route through the repository's legacy walk path unchanged.

**Why per-endpoint whitelist instead of a global enum.** Different endpoints expose different sortable columns — `listBudgets` sorts on `utilization` (derived), `listEvents` sorts on `event_type` / `category` (enums), `listAuditLogs` sorts on `resource_type`. A global enum would either inflate to 20+ fields (most invalid on any given endpoint) or drop unique fields per endpoint. Per-endpoint `Set<String> ALLOWED_SORT_FIELDS` + `SortSpec.resolve(...)` keeps every endpoint's contract explicit and the error message specific ("sort_by must be one of […]").

**Tests.** Sort-related tests added across one new shared-model test class plus the six existing repository + controller test classes:

- `cycles-admin-service-model/.../SharedModelTest` — +16 @Test methods covering `SortDirection.fromWire` (null input returns null, valid case-insensitive parse, unknown throws `IllegalArgumentException`), `SortSpec.resolve` (null/blank `rawField` falls back to `defaultField`, unknown `rawField` throws, whitelisted `rawField` round-trips, null `rawDirection` resolves to DESC), and `SortSpec.isAscending` / `of` / accessor helpers.
- `TenantRepositoryTest` +11 sort tests; `TenantControllerTest` +5 contract tests (default, valid, all whitelist, unknown sort_by → 400, unknown sort_dir → 400).
- `ApiKeyRepositoryTest` +11 sort tests; `ApiKeyControllerTest` +5 contract tests.
- `BudgetRepositoryTest` +11 sort tests (including `utilization` derived-field sort + `debt` raw-long sort); `BudgetControllerTest` +5 contract tests.
- `WebhookRepositoryTest` +11 sort tests; `WebhookAdminControllerTest` +5 contract tests.
- `EventRepositoryTest` +14 tests (11 sort + 3 comparator / parse-failure coverage).
- `EventAdminControllerTest` +5 contract tests.
- `AuditRepositoryTest` +18 sort tests (11 sort + 7 comparator / parse-failure / edge-case coverage).
- `AuditControllerTest` +5 contract tests.

Existing service-layer mocks migrated to new repository signatures (`EventServiceTest` 9-arg → 10-arg; `WebhookServiceTest` `listByTenant`/`listAll` 5-arg → 6-arg) — the new SortSpec position uses `any()` since the service layer does not validate sort (validation happens in the controller). Full admin-service build: 607/607 tests green; all JaCoCo coverage gates met (data ≥95%, api ≥93%); spec coverage 43/43.

**Dashboard wire-up.** Not in this release. The dashboard's `useSort` composable still sorts client-side; pushing the sort spec to the server (gap V4 follow-through) lands in a separate PR against `cycles-dashboard`. Until then, the server accepts the new query params but no dashboard view sends them — behaviour for existing callers is unchanged.

**AUDIT trail.**

- `cycles-admin-service/pom.xml` → `<revision>0.1.25.24</revision>`.
- `cycles-admin-service-model/.../model/shared/SortDirection.java` — NEW enum (`@JsonValue` wire-form, `@JsonCreator fromWire` case-insensitive parse).
- `cycles-admin-service-model/.../model/shared/SortSpec.java` — NEW record (`resolve(rawField, rawDirection, allowedFields, defaultField)` + `of` + `isAscending`).
- `cycles-admin-service-model/src/test/java/io/runcycles/admin/model/SharedModelTest.java` — +16 tests covering both new primitives.
- `cycles-admin-service-api/.../controller/TenantController.java` — `sort_by`/`sort_dir` params + `parseSortSpec` helper.
- `cycles-admin-service-api/.../controller/ApiKeyController.java` — same.
- `cycles-admin-service-api/.../controller/BudgetController.java` — same.
- `cycles-admin-service-api/.../controller/WebhookAdminController.java` — same.
- `cycles-admin-service-api/.../controller/EventAdminController.java` — same.
- `cycles-admin-service-api/.../controller/AuditController.java` — same.
- `cycles-admin-service-data/.../repository/TenantRepository.java` — SortSpec-accepting overload + legacy shim + `tenantComparator(SortSpec)`.
- `cycles-admin-service-data/.../repository/ApiKeyRepository.java` — same pattern, `apiKeyComparator(SortSpec)`.
- `cycles-admin-service-data/.../repository/BudgetRepository.java` — same pattern, `budgetComparator(SortSpec)` with utilization-derived sort.
- `cycles-admin-service-data/.../repository/WebhookRepository.java` — same pattern, `webhookComparator(SortSpec)`.
- `cycles-admin-service-data/.../repository/EventRepository.java` — three-path sort (null / timestamp-ASC / non-timestamp) + `eventComparator(SortSpec)` + correlation re-sort.
- `cycles-admin-service-data/.../repository/AuditRepository.java` — three-path sort + `auditLogComparator(SortSpec)`.
- Test files: every `*RepositoryTest.java` + `*ControllerTest.java` updated with mock-signature migrations + new sort/contract tests.

### 2026-04-16 — v0.1.25.23 BudgetLedger exposes `tenant_id` on the wire

Closes the companion wire-level gap exposed by v0.1.25.22's cross-tenant `listBudgets`. With `tenant_id` now optional on the request, the response is cross-tenant but each `BudgetLedger` was still returning with no wire-level tenant attribution — the `tenantId` field was `@JsonIgnore` in the model. Dashboards rendering cross-tenant budget lists either had to parse the opaque `scope` string or omit tenant context entirely. Governance spec v0.1.25.19 (cycles-protocol PR #47) adds `tenant_id` as an OPTIONAL response field on `BudgetLedger`; this release switches the server model to serialize it.

**Spec-first lineage.** Spec PR #47 was merged before any server wire change. Field is OPTIONAL on the response schema (not required) so pre-v0.1.25.23 servers don't break strict-validation consumers. Normative server behavior per spec: v0.1.25.19+ servers MUST populate `tenant_id` on every `BudgetLedger` they return.

**Model change.** `BudgetLedger.tenantId`: `@JsonIgnore` → `@JsonInclude(NON_NULL) @JsonProperty("tenant_id")`. No other model field changed. The field was already populated end-to-end in-memory — `BudgetRepository.hashToBudgetLedger` at line 636 reads `tenant_id` from the Redis hash, and the create path at line 189 writes it. Redis storage format is unchanged.

**Why `@JsonInclude(NON_NULL)` over unconditional emission.** Matches the spec's OPTIONAL classification. Legacy ledgers written by pre-tenant_id-aware servers (if any exist in production Redis) would have no `tenant_id` in their hash; `hashToBudgetLedger` returns `null` for them, and the JSON response correctly omits the field rather than emitting `"tenant_id": null`. Clean for mixed-fleet deployments where stored data may predate the field-awareness boundary.

**Wire compatibility.** Additive. Every endpoint that returns `BudgetLedger` — getBudget, listBudgets, createBudget, fundBudget, freezeBudget, unfreezeBudget, updateBudget — now includes `tenant_id` on populated ledgers. Clients that ignore unknown response fields (OpenAPI-compliant default) observe no behavioral change beyond seeing the new field. Clients that strictly validate responses against spec need to be on spec v0.1.25.19+ to recognize the field as known.

**Tests.** +2 round-trip tests on `BudgetModelTest` (`budgetLedger_tenantId_roundTripsOnWire`, `budgetLedger_tenantId_omittedWhenNull`). Existing 575 tests unaffected. JaCoCo LINE gates green (data 94%, api 93%). Spec coverage 43/43.

**AUDIT trail.**

- `cycles-admin-service/pom.xml` → `<revision>0.1.25.23</revision>`.
- `cycles-admin-service-model/.../model/budget/BudgetLedger.java` — `@JsonIgnore` → `@JsonInclude(NON_NULL) @JsonProperty("tenant_id")`.
- `cycles-admin-service-model/.../BudgetModelTest.java` — +2 round-trip tests.

### 2026-04-16 — v0.1.25.22 cross-tenant list (api-keys, budgets) + budget filter params

Closes dashboard scale-hardening gaps **R1** (ApiKeysView N+1 storm) and **R2** (BudgetsView cross-tenant N+1 + over_limit/has_debt/utilization filtering silently missing page 2+). Both were traceable to an endpoint-shape gap: `listApiKeys` required `tenant_id`, so a dashboard surfacing keys across all tenants had to fan out one HTTP call per tenant; and `listBudgets` only paginated within a single tenant and exposed none of the filter dimensions the dashboard needs. Governance spec v0.1.25.18 (cycles-protocol PR #46) added the endpoint-shape fix, and this release wires the server side to match.

**Spec-first lineage.** Spec PR #46 was merged before any server work. This release implements exactly that contract — no shape drift, no undocumented extensions. The `cycles-governance-admin-v0.1.25.yaml` CHANGELOG entry at `0.1.25.18 (2026-04-16)` describes the normative surface; this section describes only how the server honours it.

**listApiKeys — `GET /v1/admin/api-keys`.** `tenant_id` is now `required: false`. Under AdminKeyAuth:

- `tenant_id` provided → existing per-tenant path, unchanged. Cursor format stays `{keyId}`.
- `tenant_id` absent → new cross-tenant path. Walks every tenant in `tenants` (global Redis set) in sorted order; for each tenant iterates `apikeys:<tenantId>` in sorted order. Cursor format becomes `{tenantId}|{keyId}` so the follow-up page resumes inside the correct tenant boundary instead of restarting the global walk.

AdminKeyAuth is still the only auth type that reaches the listApiKeys endpoint (see `AuthInterceptor` at `/v1/admin/api-keys`), so there is no ApiKeyAuth-vs-AdminKeyAuth branching here — the authenticated tenant never scopes this endpoint today.

**listBudgets — `GET /v1/admin/budgets`.** Four new optional filter params beyond the existing `scope_prefix` / `unit` / `status`:

| Param | Type | Semantics |
|---|---|---|
| `over_limit` | boolean | True → include only ledgers whose stored `is_over_limit == true`. |
| `has_debt` | boolean | True → include only ledgers whose `debt.amount > 0`. |
| `utilization_min` | double in [0, 1] | Lower bound on `spent / allocated`. Ledgers with `allocated == 0` treat utilization as 0 (not NaN). |
| `utilization_max` | double in [0, 1] | Upper bound on `spent / allocated`. |

Filter semantics (matching spec v0.1.25.18):

- **AND across filters.** A budget must match every provided filter to appear.
- **Filter-before-cursor.** Filters apply to the candidate set before cursor traversal; pagination is therefore stable under any filter combination.
- **Cross-parameter validation.** `utilization_min > utilization_max` → 400 INVALID_REQUEST. OpenAPI cannot express this in-schema, so it is enforced at the controller boundary. Each bound is also re-validated against [0, 1] because Spring's `@RequestParam` binding does not re-check OpenAPI `minimum`/`maximum` at bind time.
- **allocated == 0.** Treated as utilization 0, never as a filter miss. Keeps new / just-created ledgers visible under the default `utilization_min=0` case.
- **Both auth types.** Filters work symmetrically under ApiKeyAuth (scoped to the authenticated tenant) and AdminKeyAuth (scoped to the resolved tenant or cross-tenant).

Tenant-resolution precedence under AdminKeyAuth:

1. `authenticated_tenant_id` set (ApiKeyAuth) → per-tenant path, ignores any `tenant_id` query param to avoid impersonation.
2. AdminKeyAuth + `tenant_id` query param present → per-tenant path.
3. AdminKeyAuth + `tenant_id` query param absent → cross-tenant path.

**`BudgetListFilters` record.** New under `cycles-admin-service-data/.../repository/BudgetListFilters.java`. Composable filter set — seven nullable fields, all optional, with a `matches(BudgetLedger)` method that encodes the AND-combination and the `allocated == 0 → utilization = 0` rule in one place. Repository-level iteration applies `matches()` before cursor traversal so pagination stays stable as filters change.

**Cross-tenant cursor format.** `{tenantId}|{ledgerId}` for budgets, `{tenantId}|{keyId}` for api-keys. Server parses on `indexOf('|')`, so ledger / key IDs MAY contain pipes and still round-trip correctly as long as tenant IDs don't (tenants are UUID-like and never contain `|`). Per-tenant cursors stay unchanged for wire-compat with existing callers.

**Why no Redis SCAN.** SCAN has non-deterministic ordering across calls, which would break cursor stability. The `tenants` + `budgets:<tenantId>` + `apikeys:<tenantId>` sets are already small enough to `SMEMBERS` + sort in-memory (tenant counts measured in thousands, per-tenant fan-out in thousands at worst). Bounded-scale deployments tolerate this cleanly; the next revision can move to a sorted-set index if single-operator-all-tenants queries start showing up as hot in profiling.

**Backward compatibility.** The old `BudgetRepository.list(tenantId, scopePrefix, unit, status, cursor, limit)` 6-arg signature is kept as a thin shim that constructs an empty-filter `BudgetListFilters` and delegates. This is deliberate: preserves every existing `BudgetRepository.list(...)` mock in `BudgetControllerTest` without rewriting them, while giving the new code path one source of truth for filter semantics.

**AUDIT trail.**

- `cycles-admin-service/pom.xml` → `<revision>0.1.25.22</revision>`.
- `cycles-admin-service-api/.../controller/ApiKeyController.java` — `tenant_id` now optional; cross-tenant branch added.
- `cycles-admin-service-api/.../controller/BudgetController.java` — four new `@RequestParam` filter fields; utilization bounds + cross-parameter validation; three-way tenant resolution.
- `cycles-admin-service-data/.../repository/ApiKeyRepository.java` — new `listAllTenants(status, cursor, limit)` + shared `collectForTenant` helper.
- `cycles-admin-service-data/.../repository/BudgetRepository.java` — new `list(tenantId, filters, cursor, limit)` + `listAllTenants(filters, cursor, limit)` + shared `collectForTenant` helper; 6-arg shim preserved.
- `cycles-admin-service-data/.../repository/BudgetListFilters.java` — NEW composable filter record.

### 2026-04-16 — v0.1.25.21 nightly CI coverage for audit-write path (soak + property-based)

Closes the nightly-CI parity gap between cycles-server and cycles-server-admin for the audit-write surface introduced in v0.1.25.20. cycles-server has 3 scheduled workflows (`nightly-property-tests.yml`, `nightly-soak-test.yml`, `nightly-benchmark.yml`); admin previously had none. This release ships Phase 1 of the plan laid out in [#102](https://github.com/runcycles/cycles-server-admin/issues/102): the property-based and soak workflows. Phase 2 (Redis resilience + benchmark baseline) remains deferred.

**Why now.** v0.1.25.20 added substantial new code paths — failure audit writes via `GlobalExceptionHandler` + `AuthInterceptor.writeError`, tiered TTL, opt-in unauthenticated-tier sampling, scheduled index sweep — that unit tests exercise at the method level but nothing exercises under sustained contention or across a wide input space. Two nightly gaps specifically:

1. **Sustained-load stability.** A 1000-sample unit test is fast but can't catch multi-hour emergent behaviour: Redis-pool exhaustion under continuous failure flood, p99 latency climb as audit writes accumulate, index-cardinality bloat if sweep misfires, heap leaks from counter-registry growth. cycles-server solved this with `SoakIntegrationTest`; admin now has `AuditFailureSoakIntegrationTest`.
2. **Wide-input invariant coverage.** Unit tests pick specific input values. jqwik generates arbitrary inputs and shrinks failing cases to the minimal reproducer — catching edge cases (empty tenant IDs, Integer.MAX_VALUE sample rates, binary-like messages) that no unit-test author would have enumerated. cycles-server has 4 such tests; admin now has 1 (`AuditCoverageInvariantsPropertyTest`) covering 6 NORMATIVE invariants.

**Soak test — `AuditFailureSoakIntegrationTest`.** Drives 500 ops/s for 10 minutes by default (configurable via `soak.duration.minutes` and `soak.target.rps`) across 16 worker threads with nanosecond-precision pacing. Round-robins 3 failure flavours so every failure-audit code path is exercised under contention:

| Flavour | HTTP method + path | Auth | Audit code path |
|---|---|---|---|
| 1 | `GET /v1/admin/tenants` (no auth) | none | `AuthInterceptor.writeError` (401, sentinel tenant_id) |
| 2 | `POST /v1/admin/budgets` (under-permissioned tenant key) | ApiKeyAuth | `AuthInterceptor.writeError` (403, real tenant_id) |
| 3 | `POST /v1/admin/tenants` (malformed body, admin key) | AdminKeyAuth | `GlobalExceptionHandler.handleMalformedJson` (400, sentinel since admin-key auth doesn't stamp tenant_id) |

Invariants asserted after the soak window:

| ID | Invariant | Measurement |
|---|---|---|
| AS1 | JVM heap end/start ratio < 2.0 | JMX `MemoryMXBean` |
| AS2 | 401 mean latency < 100ms | Micrometer `http.server.requests` timer |
| AS3 | `written + error + sampled-out == total_requests` (no lost increments) | `cycles_admin_audit_writes_total` |
| AS4 | `audit:logs:_all` cardinality ≤ written count (no orphan ZADD) | Jedis `ZCARD` |
| AS5 | Network-error rate < 1% | `AtomicLong` httpErrors / totalRequests |

Nightly CI: `.github/workflows/nightly-audit-soak.yml` fires at 06:30 UTC, 90-min timeout, `workflow_dispatch` inputs for duration + rps overrides. Surefire reports uploaded on failure.

**Property test — `AuditCoverageInvariantsPropertyTest`.** 6 `@Property` methods at jqwik default 20 tries (PR) / 100 tries (nightly). Uses plain Mockito + `SimpleMeterRegistry` rather than `@SpringBootTest` — deliberate choice to keep property-test runtime bounded (Spring context boot × 100 tries would run into hours). The Spring-wired behaviour is already covered by the soak test and `AdminFlowIntegrationTest`.

Invariants:

| ID | Invariant | Failure mode prevented |
|---|---|---|
| I1 | Exactly one outcome per `logFailure` call — never zero, never two | Lost counter increment / double-count regression |
| I2 | Authenticated entries never sampled, even at `Integer.MAX_VALUE` rate | Compliance gap from a widened sampling gate |
| I3 | TTL tier matches tenant_id (sentinel → unauth retention; real → auth retention; 0 days → no EX) | DDoS memory amplification (unauth got long retention) or compliance gap (auth got short retention) |
| I4 | Sanitized `error_message` has no `\r`/`\n` and length ≤ 1024 for any input | Log-injection vector or audit-row bloat |
| I5 | `logFailure` never propagates, even under adversarial `AuditRepository.log()` failure | Business response blocked by audit breakage |
| (bonus) | Sample rate ≤ 1 never samples out | Misconfigured `0` silently dropping every audit entry |

Nightly CI: `.github/workflows/nightly-property-tests.yml` fires at 06:00 UTC (before the soak), 30-min timeout, `-Djqwik.defaultTries=100`. Uses `mvn test` (not `verify`) because JaCoCo 95% check would fail on the 7-test property subset — the full-suite coverage bar is still enforced on every PR; nightly property tests are about invariant coverage over interleavings, not code-path coverage.

**Maven profile scaffolding.** Two new profiles added to `cycles-admin-service-api/pom.xml`:

- `property-tests` — overrides default `<excludes>` + `<excludedGroups>` and pins `<groups>property-tests</groups>`. Activates only the 6 `@net.jqwik.api.Tag("property-tests")` properties.
- `soak` — same pattern with `<groups>soak</groups>` and `forkedProcessTimeoutInSeconds=4500` (75 min — covers 10-min default + up to ~60-min workflow_dispatch deep soaks).

Default PR build adds `<excludedGroups>soak,property-tests</excludedGroups>` so tagged tests are skipped; PR CI stays at 560 tests / ~45s build as before.

jqwik + jqwik-spring dependencies added to api module. `junit-platform.properties` (admin convention) could replace the `jqwik.properties` file going forward, but current jqwik 1.9.1 still reads `jqwik.properties` with a deprecation warning — matching cycles-server's current state, and keeping the admin/server pattern symmetric. Migration to `junit-platform.properties` tracked for a future housekeeping pass.

**Changes:**

| File | Change |
|---|---|
| `.github/workflows/nightly-audit-soak.yml` (new) | Cron 06:30 UTC, 90-min timeout, `-Psoak -Dsoak.duration.minutes=... -Dsoak.target.rps=... -Dtest=AuditFailureSoakIntegrationTest`. Uploads surefire reports on failure. |
| `.github/workflows/nightly-property-tests.yml` (new) | Cron 06:00 UTC, 30-min timeout, `-Pproperty-tests -Djqwik.defaultTries=100`. |
| `cycles-admin-service-api/pom.xml` | Added jqwik 1.9.1 + jqwik-spring 0.11.0 test deps. Default surefire `<excludedGroups>soak,property-tests</excludedGroups>`. New `property-tests` and `soak` profiles. |
| `cycles-admin-service-api/src/test/resources/jqwik.properties` (new) | `defaultTries=20` (PR speed). Mirrors cycles-server. |
| `cycles-admin-service-api/src/test/java/.../service/AuditCoverageInvariantsPropertyTest.java` (new) | 6 `@Property` methods + 6 `@Provide` generators. Uses plain Mockito + `SimpleMeterRegistry` for speed. |
| `cycles-admin-service-api/src/test/java/.../integration/AuditFailureSoakIntegrationTest.java` (new) | Testcontainers-backed soak extending `BaseIntegrationTest`. 16 workers × nanosecond pacing × 3-flavour round-robin. |
| `cycles-admin-service-data/.../repository/AuditRepository.java` | Widened `resolveTtlSeconds` visibility from package-private to public for property-test access across modules. Added javadoc noting the test-access rationale; not intended as external API. |
| `pom.xml` | `revision` 0.1.25.20 → 0.1.25.21. |
| `docker-compose.prod.yml` + `docker-compose.full-stack.prod.yml` | Image tag bumped 0.1.25.20 → 0.1.25.21. |
| `AUDIT.md`, `CHANGELOG.md`, `OPERATIONS.md` | Dated entry + operator-facing runbook additions. |

**Verification:**

- `mvn verify` (default): 560 unit tests pass (unchanged from v0.1.25.20 — soak + property correctly excluded), JaCoCo ≥95% on all modules, `SpecCoverageReportTest` 43/43, zero `OpenApiContractDiff`.
- `mvn test -Pproperty-tests`: 7 property tests pass (6 + 1 bonus), `@Tag("property-tests")` filter resolves through `net.jqwik.api.Tag`, all 5 NORMATIVE invariants hold across 20 jqwik-generated input triples each.
- Nightly workflows committed but not yet executed (first run at 06:00 UTC and 06:30 UTC). Exit criteria per #102: 7 consecutive green nights before closing the issue.

**Deferred (tracked separately):**

- Phase 2: `RedisDisconnectResilienceIntegrationTest` (parity with cycles-server) + benchmark baseline via `-Pbenchmark`. Target v0.1.25.22 or later.
- Phase 3: long-run 48h+ soak (requires dedicated perf-lab infra — GH Actions 6h cap doesn't fit) + org-level spec-drift nightly. Target v0.1.26+.

**Cross-refs:** Phase 1 of [#102](https://github.com/runcycles/cycles-server-admin/issues/102). No spec dependency. No cycles-server impact — CI infra only.

---

### 2026-04-16 — v0.1.25.20 audit log on failure paths (hybrid — success writes unchanged)

Closes a real compliance-auditing gap reported during post-v0.1.25.19 review: `GET /v1/admin/audit/logs` only returned STATUS 201 / 200 / 204 entries because admin-server writes audit records **exclusively on success paths** — each controller hardcodes `auditRepository.log(...)` *after* the repository/service call returns. Every error path (401/403 in `AuthInterceptor`, 400/404/409/500 in `GlobalExceptionHandler`) short-circuits before the audit write, leaving auth rejections, malformed requests, and server errors invisible to SOC2/GDPR reviewers.

**Approach: hybrid, not full replacement, with tiered retention + DDoS protection.** Success-path per-controller writes stay verbatim — they carry rich operation-specific metadata (funded amount, budget scope, policy caps, subject/action/amount) that a catch-all interceptor couldn't reconstruct. Failure writes land in two new places:

1. **`GlobalExceptionHandler`** — every `@ExceptionHandler` method calls `auditFailure.logFailure(...)` before returning the error response. Covers 4xx/5xx that make it *into* the controller and then throw.
2. **`AuthInterceptor.writeError`** — every pre-controller auth rejection calls the same helper. Covers 401/403/400/500 failures that never reach the controller.

Plus two defense-in-depth measures that make this safe to ship against a DDoS amplification risk:

3. **Tiered TTL** (configurable, indefinite by default for compliance-critical entries).
4. **Unauthenticated-tier sampling** (configurable, off by default).

### DDoS amplification and the tiered-retention response

A naïve failure-audit implementation converts every failed request into a Redis write — at 10k req/s sustained that's ~10 MB/s of Redis memory growth and 30k Redis ops/s on a single Lua-serialized surface. The `audit:logs:<unauthenticated>` sorted set becomes a hotspot, `audit:logs:_all` grows unboundedly, and under `volatile-*` eviction policies the non-TTL audit keys survive while legitimate operational data (keys with TTL — idempotency caches, session data) gets evicted. Worst of both worlds.

Solution: **differentiate retention by authentication tier.** Compliance-critical entries (authenticated success + authenticated failure) keep long retention by default; DDoS-amplifiable entries (pre-auth failures, marked by the `<unauthenticated>` sentinel) get short retention and optional sampling.

| Tier | Example entries | Default TTL | Rationale |
|---|---|---|---|
| **Authenticated** (`tenant_id != "<unauthenticated>"`) | success mutations, 403 by valid tenant key, 404 / 409 on owned resource, 500 in authenticated path | **400 days** (`audit.retention.authenticated.days=400`) | SOC2 Type II covers a 12-month period. Auditor engagement usually begins ~30 days after period close. 400 days = 365 + ~35 buffer — the audit-of-record trail is intact when the auditor arrives. Set to `0` for indefinite (legal hold, HIPAA-adjacent, forever-retain deployments). |
| **Unauthenticated** (`tenant_id == "<unauthenticated>"`) | missing / invalid admin key 401, pre-auth path traversal 400, admin-key-only requests failing before the controller runs | **30 days** (`audit.retention.unauthenticated.days=30`) | Enough for brute-force / credential-stuffing post-mortem. Aggregate attempt volume stays visible via `cycles_admin_audit_writes_total` regardless of TTL. Set to `0` for indefinite. |

Setting a retention to `0` disables expiry for that tier. Setting BOTH to `0` also disables the index sweep entirely (`sweepStaleIndexEntries()` short-circuits when `authenticatedRetentionDays <= 0` to avoid accidentally removing pointers to live indefinite-retention records).

### Unauthenticated-tier sampling (`audit.sample.unauthenticated`, default `1`)

Independent of TTL. Records 1 in N unauthenticated entries. Default `1` preserves full fidelity — operator must opt in. Typical DDoS-exposed deployment sets this to `100`, cutting Redis write volume by 100× while keeping aggregate attempt volume visible via a new Prometheus counter outcome `sampled-out`.

Values `<= 0` are treated as `1` (no sampling) defensively — a misconfigured `0` must never silently drop every audit entry. Authenticated failures are **never** sampled regardless of the setting; they're compliance-relevant security signals, not DDoS noise.

### Daily index sweep (`audit.sweep.cron`, default `0 0 3 * * *`)

When `audit:log:{logId}` keys TTL-expire, the matching sorted-set entries in `audit:logs:_all` and `audit:logs:{tenant}` still hold pointers to the now-dead keys. Without cleanup those indexes grow unboundedly even though the underlying records are gone (~32 bytes per stale pointer).

`AuditRepository.sweepStaleIndexEntries()` runs daily (03:00 server time by default): `ZREMRANGEBYSCORE` on the global index and — via `SCAN` — each per-tenant index, removing pointers older than the longest retention window. `AuditRepository.list()` already tolerates `null` from `GET audit:log:{id}` (line 71-74), so the sweep is eventual-consistency cleanup, not on the read critical path. Sweep is best-effort: Redis-unavailable failures are logged ERROR and swallowed; next tick retries. `@EnableScheduling` added to `BudgetGovernanceApplication`.

**Single-write invariant by construction.** If the controller threw, execution never reached the controller's `audit.log(...)` — `GlobalExceptionHandler` is the only audit source for that request. Pre-auth failures never enter the controller at all — `AuthInterceptor.writeError` is the only source. No idempotency key, no dedup logic: a request produces either a success entry or a failure entry, never both. The generic `handleGenericException` branch explicitly delegates `GovernanceException` to `handleGovernanceException` to avoid double-writing on dispatch-order corner cases.

**Unauthenticated tenant sentinel.** Spec marks `AuditLogEntry.tenant_id` as **required**. Pre-auth failures have no authenticated tenant. Sentinel value `"<unauthenticated>"` preserves the spec required-field invariant — no spec change needed — and keeps failed attempts queryable via `GET /v1/admin/audit/logs?tenant_id=%3Cunauthenticated%3E`. Admin-key auth (e.g. `X-Admin-API-Key` present but paired with malformed JSON) also falls back to the sentinel because `AuthInterceptor.validateAdminKey` doesn't stamp `authenticated_tenant_id` on the request — by design, admin keys have no effective tenant.

**Failure entry shape:**

| Field | Source on failure | Example |
|---|---|---|
| `tenant_id` | `authenticated_tenant_id` attr OR sentinel | `"<unauthenticated>"`, `"tenant-1"` |
| `key_id` | `authenticated_key_id` attr (absent under admin auth) | omitted when null |
| `operation` | `"<METHOD>:<URI>"` | `"POST:/v1/admin/budgets"` |
| `status` | actual HTTP status | 401, 403, 400, 404, 409, 500 |
| `error_code` | `ErrorCode.name()` | `UNAUTHORIZED`, `INVALID_REQUEST` |
| `metadata.error_message` | CR/LF-stripped, 1024-cap sanitized | `"Missing X-Admin-API-Key header"` |
| `metadata.method`, `metadata.path` | request method + URI | `"POST"`, `"/v1/admin/budgets"` |
| `metadata.exception_class` | fully-qualified name (generic handler only) | `"java.lang.RuntimeException"` |
| `resource_type`, `resource_id`, `subject`, `action`, `amount` | omitted — not derivable on failure paths | `null` |
| `source_ip`, `user_agent`, `request_id` | from request | unchanged from success side |

**Non-fatal contract preserved.** `AuditRepository.log()` already swallowed all exceptions (logs ERROR, returns). New `AuditFailureService` wraps its own body in a separate `try/catch` as defense-in-depth — if the metric-registry call or anything else ever threw, the error response is guaranteed to reach the client. A Prometheus counter `cycles_admin_audit_writes_total{path_class, outcome}` exposes silent audit-write failures to ops:
- `path_class=failure, outcome=written` — failure audit entry written successfully
- `path_class=failure, outcome=error` — failure audit write itself failed (alert on nonzero)

**Log-injection hardening.** Failure audit entries carry the server-side error message in `metadata.error_message`. `AuditFailureService.sanitizeMessage(...)` replaces CR/LF with spaces and caps at 1024 chars. Server-side messages are typically fixed strings (`"Missing X-Admin-API-Key header"`, `"Validation failed: name: must not be blank"`), but sanitize defensively in case any caller-controlled content ever leaks in.

**Changes:**

| File | Change |
|---|---|
| `cycles-admin-service-model/.../audit/AuditLogEntry.java` | Added `UNAUTHENTICATED_TENANT = "<unauthenticated>"` public constant. Source of truth — both `AuditRepository` (data) and `AuditFailureService` (api) reference it without cross-layer coupling. |
| `cycles-admin-service-api/.../service/AuditFailureService.java` (new) | Shared failure-audit writer. `logFailure(HttpServletRequest, int status, ErrorCode, String message, Map extras)`. Populates `tenant_id` via attr-or-sentinel, `operation=METHOD:URI`, sanitized message + handler extras in metadata. Non-throwing. Emits `cycles_admin_audit_writes_total{path_class, outcome}` counter with outcome values `written` / `error` / `sampled-out`. New `@Value("${audit.sample.unauthenticated:1}")` sampling gate applied ONLY to unauthenticated-tier entries — authenticated failures always persist at full fidelity. Values `<= 1` treated as "no sampling" (defense against misconfigured zero). `ThreadLocalRandom` for lock-free sampling under contention. |
| `cycles-admin-service-data/.../repository/AuditRepository.java` | Widened `LOG_AUDIT_LUA` to accept per-entry TTL (seconds) via `ARGV[4]`. Lua branches on `ttl > 0` — non-positive → plain `SET`, positive → `SET ... EX ttl`. Two new `@Value`-bound fields: `audit.retention.authenticated.days:400` and `audit.retention.unauthenticated.days:30`. New `resolveTtlSeconds(entry)` picks tier by `AuditLogEntry.UNAUTHENTICATED_TENANT` check. New `@Scheduled(cron = "${audit.sweep.cron:0 0 3 * * *}") sweepStaleIndexEntries()` method purges TTL-expired pointers from global + per-tenant indexes via `ZREMRANGEBYSCORE` (uses `SCAN` to enumerate per-tenant indexes; O(log N + M) per remove). Sweep skipped when `authenticatedRetentionDays <= 0` (indefinite retention). `list()` debug log message updated to note TTL-expired entries are expected. |
| `cycles-admin-service-api/.../BudgetGovernanceApplication.java` | Added `@EnableScheduling` so `AuditRepository.sweepStaleIndexEntries()` fires on the configured cron. |
| `cycles-admin-service-api/src/main/resources/application.properties` | Added 4 new properties: `audit.retention.authenticated.days` (default 400), `audit.retention.unauthenticated.days` (default 30), `audit.sample.unauthenticated` (default 1), `audit.sweep.cron` (default `0 0 3 * * *`). All env-var overridable. |
| `cycles-admin-service-api/.../exception/GlobalExceptionHandler.java` | Constructor inject `AuditFailureService`. All 7 `@ExceptionHandler` methods call `logFailure(...)` before returning. Generic handler passes `exception_class` in metadata for post-incident triage; delegates `GovernanceException` to its dedicated handler to prevent double-writes. |
| `cycles-admin-service-api/.../config/AuthInterceptor.java` | Constructor widened from 2 → 3 deps (added `AuditFailureService`). `writeError(...)` calls `logFailure(...)` before writing the error body. Covers all 7 call sites: path traversal 400; admin key missing 401; admin key blank/invalid 401; admin key misconfigured 500; api key missing 401; api key invalid 403; insufficient permissions 403. |
| `cycles-admin-service-api/.../config/AuthInterceptorTest.java` | Added `@Mock AuditFailureService`; updated constructor call. +8 tests locking the failure-audit contract at the interceptor layer (7 failure call sites + 1 single-write-invariant guard for the success path). |
| `cycles-admin-service-api/.../exception/GlobalExceptionHandlerTest.java` | Added `AuditFailureService` mock injection to setUp. +8 tests verifying every `@ExceptionHandler` branch writes the failure audit entry with correct status + error_code; explicit single-write-invariant test for the `GovernanceException` → generic-dispatch edge case. |
| `cycles-admin-service-api/.../service/AuditFailureServiceTest.java` (new) | 15 unit tests: sentinel resolution (null attr, empty attr); authenticated tier not sampled at any rate (guard against widened gate); unauthenticated tier at rate 1 (never sampled, 200 iterations deterministic); unauthenticated at rate 100 (statistical bounds, 1000 iterations); rate 0 treated as 1 (misconfig safety); CR/LF sanitization; 1024-char truncation; null message omits key; extras merged; non-throwing under `AuditRepository` failure (error counter increments); null request safe. **Plus 2 DDoS-in-miniature concurrency tests**: (a) 1000 concurrent `logFailure` calls on 16 threads at rate=100 — asserts `written + sampled-out + error == 1000` exactly (no lost increments), `error == 0` (non-throwing under contention), and statistical sampling bounds hold; (b) 500 concurrent authenticated failures at rate=10000 — asserts 100% fidelity (authenticated tier immune to sampling under load). |
| `cycles-admin-service-data/.../repository/AuditRepositoryTest.java` | +7 tests: `resolveTtlSeconds` for authenticated / unauthenticated / zero tiers; `log()` passes correct TTL in `ARGV[4]` per tier; `log()` with retention 0 passes 0 (Lua skips EX); `sweepStaleIndexEntries` skipped when retention is indefinite; sweep removes from both global and per-tenant indexes without double-sweeping `audit:logs:_all` through the SCAN enumeration; sweep Redis-failure non-throwing. |
| `cycles-admin-service-api/.../integration/AuditRetentionIntegrationTest.java` (new) | 3 end-to-end tests against real Redis (Testcontainers): authenticated failure produces key with `ttl ≈ 400d`; unauthenticated failure produces key with `ttl ≈ 30d`; the two tiers produce materially distinct TTLs (diff > 300d). Closes the Lua-mock gap — proves Redis actually applies the EX, not just that the Lua receives the right value. Runs under `-Pintegration-tests`. |
| `cycles-admin-service-api/.../integration/AuditRetentionIndefiniteIntegrationTest.java` (new) | 3 end-to-end tests with `@TestPropertySource(audit.retention.*.days=0)` overriding defaults: unauthenticated failure produces key with `ttl = -1` (no expiry); authenticated failure same; `sweepStaleIndexEntries` under retention=0 does not touch live pointers (regression guard — a future refactor that re-enables sweep under indefinite retention would delete pointers to forever-retain records). |
| `cycles-admin-service-api/.../integration/AdminFlowIntegrationTest.java` | New step 25 — 3 deliberate failures (unauth 401, malformed JSON 400, nonexistent resource 404) followed by audit-log query asserting each failure landed with correct status, error_code, operation, and tenant_id sentinel on unauth case. Contract-validated end-to-end against `cycles-protocol@main`. |
| `OPERATIONS.md` | New "Audit coverage" subsection documenting success-path (per-controller, rich metadata) vs failure-path (handler/interceptor, coarse but status + error_code). Added `cycles_admin_audit_writes_total` to Metrics inventory with alert recipe on `outcome=error` rate. |
| `CHANGELOG.md` | New `## [0.1.25.20]` section. |
| `pom.xml` | `revision` 0.1.25.19 → 0.1.25.20. |
| `docker-compose.prod.yml` + `docker-compose.full-stack.prod.yml` | Image tag 0.1.25.19 → 0.1.25.20. |

**Design choices:**
- **Sentinel over nullable tenant_id.** Spec declares `tenant_id` required. Making it nullable would need a cycles-protocol PR and block this release on spec-first merge ordering. Sentinel is backward-compatible: existing queries that filter `tenant_id=<real-tenant>` continue returning only that tenant's entries; failed-attempt review uses the sentinel as a filter value.
- **Include error_message in metadata (operator confirmed).** Queryable `error_code` is the machine key; `error_message` is the "what specifically went wrong" for the human reviewer. Sanitization keeps it safe.
- **"METHOD:URI" operation format on failure.** Per-controller operation names (`createBudget`, `fundBudget`) aren't available pre-controller or in `GlobalExceptionHandler`. Ops queries can still group by operation; success entries use the rich name, failure entries use the coarse form. Different format across success/failure is deliberate — failure entries are intentionally less specific.
- **Prometheus counter outcome=error as the alert surface.** If audit writes silently fail (Redis down, auth-chain breakage), `outcome=error` spikes while the error responses themselves still reach clients. Critical for compliance: never trust that "zero failure entries in Redis" means "zero failures occurred" without also checking this counter.

**Verification:**
- `mvn verify` passes 560 unit tests (up from 528), JaCoCo ≥95% on all modules, `SpecCoverageReportTest` 43/43 endpoints. 2 new integration tests (`AuditRetentionIntegrationTest`, `AuditRetentionIndefiniteIntegrationTest`) run under `-Pintegration-tests` against Testcontainers Redis.
- `OpenApiContractDiffTest` — zero INCOMPATIBLE diff. No wire-format changes: `AuditLogEntry` schema already had every field populated on failure (`error_code` was already declared optional, `metadata` was already `Map<String, Object>`).
- `AdminFlowIntegrationTest` — 25-step flow ends with an audit-log query that asserts each deliberate failure landed with correct status, error_code, and tenant_id sentinel. Entries validated on the wire by `ContractValidatingRestTemplateInterceptor`.

**Operator note:** Queries that filter `status=201` / `status=200` are unaffected — those return exactly the same rows as before. Queries without a status filter (or with `status=401/403/400/404/409/500`) now surface the new failure entries. Dashboards that assume "audit entry exists ⇒ operation succeeded" are broken by this change — callers must now check `status` or `error_code` to distinguish success from failure. No CLAUDE.md/integration-tests change required: the admin-owned integration tests (`runcycles/.github`) only read audit logs for post-flow verification and don't make the success-assumption.

**Cross-refs:** this release has no cycles-protocol dependency — the fix is entirely admin-side and the spec already accommodates the failure entry shape. No change to cycles-server or cycles-server-events.

---

### 2026-04-16 — v0.1.25.19 /v1/auth/introspect dual-auth + operator docs (spec v0.1.25.15)

Closes the one remaining parity gap against the admin spec: `GET /v1/auth/introspect` now accepts both `AdminKeyAuth` (admin-shape) and `ApiKeyAuth` (tenant-shape) per spec v0.1.25.15 (cycles-protocol@101416f, yaml:3066-3198 + 4703-4768). Prior admin releases returned the admin-only stub that the spec's BACKWARD COMPATIBILITY clause (yaml:225-228, 4729-4734) explicitly allows as a forward-compat position — this release closes the gap so tenants can introspect their own API keys through the dashboard.

Also introduces consumer-facing `CHANGELOG.md` and operator-facing `OPERATIONS.md`, mirroring the shape just added to cycles-server (CHANGELOG.md + OPERATIONS.md per cycles-server PR #98). `AUDIT.md` remains the engineering-history log; `CHANGELOG.md` is the summary for downstream consumers pulling the Docker image / JAR.

**Parity audit context.** Full review of cycles-governance-admin-v0.1.25.yaml revisions v0.1.25.11 → v0.1.25.17 found every spec change already covered in prior admin releases (v0.1.25.11–v0.1.25.18) except the introspect dual-auth contract from v0.1.25.15. cycles-server recently mirrored `BUDGET_RESET_SPENT` into its runtime `EventType` vocabulary (cycles-server PR #103); admin has had this enum value since v0.1.25.18 — no action required.

**Auth-shape semantics (NORMATIVE, yaml:3105-3166).**

| Branch | `auth_type` | `permissions` | `tenant_id` | `scope_filter` | Capabilities |
|---|---|---|---|---|---|
| AdminKeyAuth (X-Admin-API-Key) | `"admin"` | `["*"]` | **absent** | **absent** | all 15 flags true |
| ApiKeyAuth (X-Cycles-API-Key) | `"tenant"` | concrete key perms | tenant id (required) | key `scope_filter` if non-empty | derived per table; admin-plane caps forced false |

Admin-plane capabilities (`view_tenants`, `view_api_keys`, `view_audit`, `view_overview`, `manage_tenants`, `manage_api_keys`) are hard-coded to `false` under tenant auth regardless of any `admin:*` permissions on the key (yaml:3138-3147 — prevents accidental admin-UI elevation via legacy admin-permission tenant keys).

**Changes:**

| File | Change |
|---|---|
| `cycles-admin-service-api/.../config/AuthInterceptor.java` | `/v1/auth/introspect` removed from `requiresAdminKey()`; added to `ADMIN_ALLOWED_ENDPOINTS` (exact dual-auth match) and `requiresApiKey()` path set. Tenant keys now stamp `authenticated_tenant_id`/`authenticated_permissions`/`authenticated_scope_filter` on the request; admin keys take the admin-only branch (no attributes stamped). |
| `cycles-admin-service-api/.../controller/AuthController.java` | Rewrote `introspect()` to branch on `authenticated_tenant_id` attribute. New helpers `allCapabilitiesTrue()` (admin-shape) and `deriveTenantCapabilities(List<String>)` (tenant-shape per NORMATIVE table). Obsolete `deriveCapabilities(List<String>)` removed. |
| `cycles-admin-service-model/.../auth/AuthIntrospectResponse.java` | Added nullable `tenant_id` (`@JsonInclude(NON_NULL)`) and `scope_filter` (`@JsonInclude(NON_EMPTY)`). Per-field include overrides class-level `ALWAYS` so admin-shape stays wire-identical to pre-v0.1.25.19. |
| `cycles-admin-service-model/.../auth/Capabilities.java` | Added 7 optional boxed-`Boolean` fields (`view_reservations`, `manage_budgets`, `manage_policies`, `manage_webhooks`, `manage_tenants`, `manage_api_keys`, `manage_reservations`) each with `@JsonInclude(NON_NULL)`. Absent by default — legacy consumers see unchanged wire output. |
| `cycles-admin-service-api/.../controller/AuthControllerTest.java` | +5 tests: admin-shape now asserts all 15 caps true + absence of tenant_id/scope_filter; 4 new tenant-shape tests including NORMATIVE admin-plane forced-false guard and scope_filter omit/include. Existing `introspect_withoutAdminKey_returns401` → `introspect_withoutAnyKey_returns401`. |
| `cycles-admin-service-api/.../config/AuthInterceptorTest.java` | +3 tests: tenant-key success with attribute stamping, invalid-tenant-key 403, zero-permission minimal-key success (no required permission on introspect). Existing admin-key test updated to assert no tenant-auth attributes. |
| `cycles-admin-service-model/.../AuthModelTest.java` | +5 serialization tests: admin-shape omits tenant_id+scope_filter; tenant-shape includes both; empty scope_filter omitted; legacy Capabilities shape omits all 7 new manage_* fields; full-caps shape serializes all 15. |
| `cycles-admin-service-api/.../integration/AdminFlowIntegrationTest.java` | New step 17b tenant-key introspect call (before step 18's key revoke), validated through `ContractValidatingRestTemplateInterceptor` against `cycles-protocol@main` — admin-plane caps asserted false, tenant-plane caps derived from step 5's granted permissions. |
| `pom.xml` | `revision` 0.1.25.18 → 0.1.25.19. |
| `README.md` | Spec ref line updated v0.1.25.14 → v0.1.25.15. |
| `docker-compose.prod.yml` + `docker-compose.full-stack.prod.yml` | Image tag bumped 0.1.25.18 → 0.1.25.19. |
| `CHANGELOG.md` (new) | Consumer-facing Keep-a-Changelog format, entries v0.1.25.10 → v0.1.25.19. |
| `OPERATIONS.md` (new) | Operator-facing runbook: metrics inventory, alerts, tuning, incident playbook. |

**Design choices:**
- **Per-field Jackson include overrides class-level `ALWAYS`.** Chose this over restructuring into two response classes because spec has one schema (`AuthIntrospectResponse`) with optional fields — two-class split would drift from spec. `@JsonInclude(NON_NULL)` on `tenantId` and `@JsonInclude(NON_EMPTY)` on `scopeFilter` preserve wire compatibility with pre-v0.1.25.19 admin-shape responses.
- **Boxed `Boolean` for the 7 optional caps.** Primitive `boolean` defaults to `false` and would serialize (with `ALWAYS` class-level inclusion), breaking pre-v0.1.25.19 wire compatibility for consumers parsing the admin shape.
- **NORMATIVE admin-plane forced-false under tenant auth.** Dashboard security boundary. Legacy tenant keys with `admin:read`/`admin:write` still govern per-endpoint access control (unchanged in `AuthInterceptor.hasPermission()`) but MUST NOT lift the dashboard's admin-UI chrome.
- **No new endpoint; no operationId change.** `introspectAuth` already existed; only its security schemes + response shape expand. `OpenApiContractDiffTest` reports zero INCOMPATIBLE diff.

**Verification:**
- `mvn verify` passes 528 tests (up from 518), JaCoCo ≥95% on all modules, `SpecCoverageReportTest` 43/43 endpoints.
- `ContractValidationConfig` validates all introspect responses (admin + tenant shapes) against the pinned spec on `cycles-protocol@main` — zero drift.
- `OpenApiContractDiffTest` vs live spec: zero INCOMPATIBLE diff; added optional fields on `AuthIntrospectResponse`/`Capabilities` show as COMPATIBLE (additive).

**Wire format:** additive. Pre-v0.1.25.19 admin-shape responses remain byte-identical. New tenant-shape and optional cap fields only materialize when the new code paths / new cap setters fire. Existing admin-key callers see no change.

**Cross-refs:** implements [cycles-protocol#43](https://github.com/runcycles/cycles-protocol/pull/43) spec v0.1.25.15 (tenant-introspect dual-auth).

---

### 2026-04-15 — v0.1.25.18 RESET_SPENT operation: billing-period boundary

Closes a long-standing gap between the docs (`how-to/budget-allocation-and-management-in-cycles#resetting-budgets` describes RESET as "for resetting a scope for a new billing period") and the RESET implementation (which only resizes the allocated ceiling and preserves spent — a strict no-op on an exhausted budget when re-RESET to the same allocated). Adds the new `RESET_SPENT` funding operation and its event type, paired with the cycles-protocol#45 spec PR.

**Approach:** add a new operation, don't redefine RESET. Keeps RESET symmetric with CREDIT/DEBIT as a history-preserving ceiling adjustment; RESET_SPENT owns the billing-period semantic. No existing caller sees behaviour drift.

**RESET_SPENT semantics** (Lua, BudgetRepository.java):
- `allocated` = request `amount`
- `spent` = optional request `spent` field (defaults to 0 when omitted)
- `reserved` preserved (active runtime reservations straddle the period and land in the new period's spent when they commit)
- `debt` preserved (periods don't forgive debt; `REPAY_DEBT` is the channel)
- `remaining` recomputed from the ledger invariant; allowed to go negative if `(allocated - spent - reserved - debt) < 0` (overdraft semantics)
- `is_over_limit` recomputed from `debt > overdraft_limit` (logic unchanged, inputs change)

**Optional `spent` parameter** covers four operator needs beyond routine rollovers: migration from another billing system, prorated mid-period signup, credit-back / compensation, state correction. Constrained to `>= 0`. Validated at both the controller (Bean Validation + unit-mismatch check) and Lua layers (defensive `INVALID_REQUEST` on negative; surfaces as 400).

**Spec coordination:** depends on cycles-protocol#45 (spec PR for v0.1.25.17). Admin contract test fetches `cycles-protocol@main`; this PR's contract tests pass against the spec PR branch and will pass against `main` once cycles-protocol#45 merges. Spec-first merge ordering required.

**Idempotency cache versioned to v2.** The fund Lua now caches 9 pipe-delimited fields (was 7) — added `prev_spent` and `new_spent`. The cache key prefix bumped from `idempotency:fund:<key>` to `idempotency:fund:v2:<key>`. Old v1 entries expire naturally within 24h; old and new coexist with zero parse-failure risk during the rolling deploy. Java parser updated to read the v2 format.

**Event emission:** new `BUDGET_RESET_SPENT` event type (`budget.reset_spent`), distinct from `budget.reset`. Event consumers (dashboards, webhook handlers, compliance queries) can route period boundaries separately from resize events. The payload's new `spent_override_provided` boolean flags whether the request explicitly set `spent` (migration / correction needing compliance scrutiny) vs defaulted to 0 (routine rollover).

**Audit metadata enriched** in `BudgetController.buildFundMetadata`: now records `previous_spent`, `new_spent`, and (for RESET_SPENT) `spent_override_provided`. Reviewers see the before/after spent transition and whether the operator deliberately set it without joining to event logs.

**Changes:**

| File | Change |
|---|---|
| `cycles-admin-service-model/.../FundingOperation.java` | Add RESET_SPENT enum value + javadoc explaining each operation's role |
| `cycles-admin-service-model/.../BudgetFundingRequest.java` | Add optional `spent` field (Amount); javadoc clarifies RESET_SPENT-only semantic |
| `cycles-admin-service-model/.../BudgetFundingResponse.java` | Add nullable `previous_spent` and `new_spent` (additive, `@JsonInclude(NON_NULL)`) |
| `cycles-admin-service-model/.../event/BudgetOperation.java` | Add RESET_SPENT enum value |
| `cycles-admin-service-model/.../event/EventType.java` | Add `BUDGET_RESET_SPENT("budget.reset_spent", BUDGET)`; drop stale "(15)" comment |
| `cycles-admin-service-model/.../event/EventPayloadTypeMapping.java` | Map `BUDGET_RESET_SPENT` → `EventDataBudgetLifecycle.class` |
| `cycles-admin-service-model/.../event/EventDataBudgetLifecycle.java` | Add `spent` and `reserved` to `BudgetState`; new optional `spent_override_provided` field on the outer payload |
| `cycles-admin-service-data/.../BudgetRepository.java` | New RESET_SPENT Lua branch with optional ARGV[7] spent override; return array extended 8 → 10 elements (adds prev_spent / new_spent); `INVALID_REQUEST` error path for negative spent; idempotency cache key prefix bumped to `idempotency:fund:v2:`; Java parser reads new fields |
| `cycles-admin-service-api/.../BudgetController.java` | Validate optional `spent` (operation must be RESET_SPENT, unit must match, value `>= 0`); emit `BUDGET_RESET_SPENT` event with full pre/post BudgetState snapshots and `spent_override_provided` flag; enrich audit metadata with prev/new spent + the override flag |
| `cycles-admin-service-data/.../BudgetRepositoryTest.java` | +6 new tests: default-clears-spent, with-explicit-override, rejects-negative, allows-negative-remaining, empty-ARGV-default-path, idempotent-hit-parses-v2-cache |
| `cycles-admin-service-api/.../BudgetControllerTest.java` | +5 new tests: RESET_SPENT default, with override, spent on wrong operation rejected, negative spent rejected, spent unit mismatch rejected |
| `cycles-admin-service-model/.../EventModelTest.java` | Bump expected event-type count 40 → 41 with explanatory comment |

**Verification:**
- `mvn verify` passes 518 tests (up from 512), JaCoCo coverage met (≥95%), spec coverage 43/43 endpoints.
- Run with `-Dcontract.spec.url=https://raw.githubusercontent.com/runcycles/cycles-protocol/spec/budget-reset-spent-v0.1.25.17/cycles-governance-admin-v0.1.25.yaml` while cycles-protocol#45 is in flight; once merged, default contract URL (cycles-protocol@main) will validate cleanly.

**Wire format:** additive. Existing RESET callers are unaffected. New fields are optional / nullable; new enum values follow the spec's existing extensibility policy ("consumers MUST ignore unrecognised enum values").

**Cross-refs:** cycles-protocol#45 (spec PR), upcoming docs PR (split `how-to/budget-allocation-and-management-in-cycles#resetting-budgets` into "Resizing a budget" and "Starting a new billing period").

---

### 2026-04-14 — v0.1.25.17 cjson round-trip sweep: apikey + policy + tenant

Originally scoped to fix [cycles-dashboard#43](https://github.com/runcycles/cycles-dashboard/issues/43) — an API-key revocation bug that silently dropped records from admin list responses after corrupting their JSON via Redis cjson. A follow-up audit found the same anti-pattern on two more write paths; this release sweeps all three.

**Root cause (shared across all three).** Lua scripts that round-trip record JSON through `cjson.decode` → `cjson.encode` on write trip Redis cjson's well-known empty-array bug: `cjson.encode({})` (empty Lua table) serializes as `{}` instead of `[]`. Any `List<*>` field that's empty at write time becomes `{}` in the stored JSON, after which Jackson fails to deserialize the record (`MismatchedInputException`) and the `list()` catch swallows the error with a WARN, dropping the record from admin responses. The `update()` on ApiKey had already been migrated off cjson in an earlier release (see its pre-existing docblock) — the sweep catches the three remaining offenders.

**Blast radius per write path:**

| Path | Fields at risk | Severity |
|---|---|---|
| `ApiKeyRepository.revoke()` (was: `REVOKE_KEY_LUA`) | `ApiKey.permissions` (`List<String>`), `ApiKey.scopeFilter` (`List<String>`) | **HIGH** — both are routinely empty on freshly-created keys. The dashboard#43 reporter hit this directly. |
| `PolicyRepository.update()` (was: `UPDATE_POLICY_LUA`) | `Caps.toolAllowlist` (`List<String>`), `Caps.toolDenylist` (`List<String>`) | **HIGH** — both are optional and very commonly empty (typical policies cap by amount/rate only and leave tool lists empty). Symptom identical to dashboard#43: silent drop from `GET /v1/admin/policies`. |
| `TenantRepository.update()` (was: `UPDATE_TENANT_LUA`) | `Tenant.metadata` (`Map<String, String>`) | **LOW** — Jackson reads `{}` into an empty `Map` cleanly, so the corruption was symptom-free. Migrated anyway for consistency and to eliminate latent risk if any future `Tenant` field becomes a `List<*>`. |

**Changes:**

| File | Change |
|---|---|
| `ApiKeyRepository.java` | Dropped `REVOKE_KEY_LUA`. `revoke()` now does `jedis.get → Jackson readValue → mutate → writeValueAsString → jedis.set`, same pattern as `update()`. Idempotent on already-revoked keys (original `revoked_at` / `revoked_reason` preserved). Old Lua block replaced with a hazard-marker comment. |
| `PolicyRepository.java` | Dropped `UPDATE_POLICY_LUA`. `update()` now mirrors the Jackson-in-Java pattern; partial updates apply per-field against the deserialized `Policy`. Ownership check preserved verbatim (`tenantId != null && !tenantId.equals(policy.getTenantId())` → 403); because `Policy.tenantId` is `@JsonIgnore` the stored JSON never carries it, matching the prior Lua semantics where tenant-auth always tripped FORBIDDEN against a nil stored `tenant_id`. Only admin-auth callers (tenantId=null) exercise this path in production. |
| `TenantRepository.java` | Dropped `UPDATE_TENANT_LUA`. `update()` ports the Lua status-transition validator verbatim into Java: reject transitions out of `CLOSED`, allow `ACTIVE ⇄ SUSPENDED ⇄ CLOSED` otherwise, stamp `suspended_at` / `closed_at` on the respective transitions. All other scalar + map fields apply as replace-non-null. |
| `ApiKey.java` | Added `@JsonDeserialize(using = LenientStringListDeserializer.class)` to `permissions` and `scope_filter`. |
| `Caps.java` | Same: `@JsonDeserialize(using = LenientStringListDeserializer.class)` on `toolAllowlist` and `toolDenylist`. |
| `LenientStringListDeserializer.java` | New Jackson deserializer (shared): accepts `[]`, `null`, and empty `{}` as `List<String>`; forwards any other shape to Jackson's default mismatch handling. |
| `cycles-admin-service-model/pom.xml` | Promoted `jackson-core` / `jackson-databind` to compile scope (needed by the deserializer; already on the admin runtime classpath transitively). |
| `ApiKeyRepositoryTest.java` | Four revoke tests rewritten against the Jackson-in-Java path. New `list_legacyCorruptedEmptyArrays_stillReadable` test asserts a pre-v0.1.25.17 record with `scope_filter: {}` / `permissions: {}` is still parseable. `revoke_alreadyRevoked_returnsRevokedKey` verifies idempotency (no re-write, original timestamp preserved). |
| `PolicyRepositoryTest.java` | Six update tests rewritten against the new flow. Tests now pass `null` for tenantId to exercise the admin-auth path (the only path that doesn't short-circuit on the ownership check under the preserved behavior). New `update_emptyToolAllowlistRoundtripsAsArray_notObject` and `list_legacyCorruptedCapsArrays_stillReadable` guard the fix. |
| `TenantRepositoryTest.java` | Nine update tests rewritten, using a shared `storedTenantJson(status)` helper. Status-transition paths now exercise the real Java validator. |
| `pom.xml` | `revision` 0.1.25.16 → 0.1.25.17. |

**Design choices:**
- **Lenient on read, strict on write.** The deserializer only tolerates the exact `{}` shape produced by cjson corruption. New writes always go through Jackson and produce proper `[]`, so the fleet self-heals on the next mutating admin call against each record.
- **Shared deserializer.** `LenientStringListDeserializer` is reused by `ApiKey` (permissions, scope_filter) and `Caps` (tool_allowlist, tool_denylist). If a future List<String> field surfaces the same risk, wire it to the same class.
- **No atomicity regression.** The previous Lua scripts' atomicity was nominal — every other admin write path is Jackson-in-Java with no CAS, so a concurrent `update()` racing an update/revoke was already last-writer-wins. All three migrated paths inherit the same semantics. If stricter CAS is ever required, all Jackson-in-Java mutators should be migrated together via `WATCH`/`MULTI`/`EXEC`.
- **Status-transition semantics on tenant.** The Java port preserves the Lua's state machine exactly: `CLOSED` is terminal (rejects all transitions), `ACTIVE ⇄ SUSPENDED ⇄ CLOSED` are the only legal edges.

**Operator note.** No migration required. Corrupted records (api keys, policies) will be rewritten cleanly the next time any mutating admin call touches them. Until then, the lenient deserializer keeps them listable. Tenant records were never observably affected.

**Also in v0.1.25.17 — revokeApiKey spec-compliance fix.** The spec (`cycles-governance-admin-v0.1.25.yaml` → `revokeApiKey`, line 4476-4481) requires HTTP 409 ALREADY_REVOKED when the caller attempts to revoke a key that is already REVOKED. The old Lua path (and the earlier commit in this PR before the compliance audit) returned HTTP 200 with the stored record. Corrected: `ApiKeyRepository.revoke()` now throws `GovernanceException.apiKeyAlreadyRevoked(keyId)` (KEY_REVOKED, 409) on that branch. A new factory method was added to `GovernanceException`. The `revoke_alreadyRevoked_*` test was rewritten to assert the 409 contract.

**Also in v0.1.25.17 — `updateApiKey` / `createApiKey` permission validation UX fix.** Dev-reported bug: `PATCH /v1/admin/api-keys/{id}` returning 400 with the opaque message "Malformed request body" when the dashboard round-tripped stored permissions. Root cause: `ApiKeyCreateRequest.permissions` and `ApiKeyUpdateRequest.permissions` were typed `List<Permission>` (closed enum), so any stored permission string not in the enum — legacy records, typos from direct Redis writes, schema drift — caused Jackson to fail with `HttpMessageNotReadableException` before the controller ever ran. The generic 400 response didn't identify the offender, so the operator couldn't tell which of several permissions was bad.

Both DTOs now carry `permissions: List<String>`. Validation moves into `ApiKeyRepository.create()` / `.update()` via a new `Permission.findUnknown(List<String>)` helper that returns the first unrecognized value (or `null`). If any value is unknown the repository throws `GovernanceException(INVALID_REQUEST, "Unrecognized permission: <value>", 400)` — same HTTP status, still spec-compliant (yaml line 4383 permits 400 on unknown permissions), but with an actionable message that names the specific bad string. The `getPermissionsAsStrings()` helpers were removed (redundant — the field is now `List<String>` directly). Controller call sites in `ApiKeyController.create()` use `request.getPermissions()` directly.

New tests: `create_unknownPermission_throws400WithSpecificValue`, `update_unknownPermission_throws400WithSpecificValue` assert the 400 path names the offender. `AuthModelTest.apiKeyCreateRequest_unknownPermission_acceptedAtDeserialization` documents the new contract (Jackson accepts; repo rejects). `apiKeyCreateRequest_validPermissions_deserialize` updated to assert string equality instead of enum equality. Full suite: 513 tests, 0 failures.

### 2026-04-13 — v0.1.25.16 Stage 3 dual-auth: tenant-scoped webhook endpoints

Third and final stage of the admin-on-behalf-of rollout (v0.1.25.13 covered budgets/policies, v0.1.25.14 in cycles-server covered reservations). Closes the webhook ops gap: admin operators can now pause / force-delete / inspect tenant-provisioned webhooks during incident response without holding the tenant's API key.

Implements [cycles-protocol#40 (spec v0.1.25.14)](https://github.com/runcycles/cycles-protocol/pull/40).

**Dual-auth added to 6 endpoints** at `WebhookTenantController`:

| Endpoint | Treatment |
|---|---|
| `GET /v1/webhooks` | + `AdminKeyAuth`. New `tenant` query param: REQUIRED under admin (filter), MUST NOT be set under ApiKey. 400 on either violation. |
| `GET /v1/webhooks/{id}` | + `AdminKeyAuth`. Admin reads any subscription; owning tenant resolved from record. |
| `PATCH /v1/webhooks/{id}` | + `AdminKeyAuth`. Primary use case: pause flapping webhook. Audit metadata tagged `actor_type=admin_on_behalf_of`. |
| `DELETE /v1/webhooks/{id}` | + `AdminKeyAuth`. Force-remove. Audit tagged. |
| `POST /v1/webhooks/{id}/test` | + `AdminKeyAuth`. Diagnose reachability. Audit tagged. |
| `GET /v1/webhooks/{id}/deliveries` | + `AdminKeyAuth`. Inspect failure log. |

**Intentionally NOT dual-auth** (provenance footgun):
- `POST /v1/webhooks` (create) — URL, signing secret, event choices are tenant policy.
- `POST /v1/webhooks/{id}/replay` — already admin-only at `/v1/admin/webhooks/{id}/replay`.

**Changes:**

| File | Change |
|---|---|
| `AuthInterceptor.java` | `GET:/v1/webhooks` added to `ADMIN_ALLOWED_ENDPOINTS`. 4 prefix entries added to `ADMIN_ALLOWED_PREFIXES` covering `GET`/`PATCH`/`DELETE`/`POST` on `/v1/webhooks/`. Existing prefix matcher (`reqKey.length() > entry.length()`) correctly excludes bare `POST:/v1/webhooks` (no id) — create stays ApiKey-only by construction. |
| `WebhookTenantController.java` | `list` gains `tenant` query param + bidirectional validation. Per-subscription endpoints route through a new `enforceTenantOwnership(request, sub)` that skips under admin auth. Audit entries for `patch` / `delete` / `test` tag `actor_type=admin_on_behalf_of` vs `api_key`; audit subject is the subscription's owning tenant (not the admin caller). |
| `pom.xml` | `revision` 0.1.25.15 → 0.1.25.16. |

**Design choices:**
- **404 (not 403) preserved across auth types.** Under ApiKey, cross-tenant reads look like not-found — retained. Under admin there's no cross-tenant case, so 404 only fires when the sub genuinely doesn't exist.
- **Audit subject = subscription owner.** Under admin auth the caller has no `tenantId`; the subscription record's `tenant_id` is trusted as the history subject. Matches PolicyController.updatePolicy pattern from v0.1.25.13.
- **Prefix match guards POST create.** `POST:/v1/webhooks/` requires a non-empty suffix, so `POST:/v1/webhooks` (bare create path) correctly doesn't match.

**Tests** (+8 in `WebhookTenantControllerTest`, 25/25 total — was 17):
- list with admin + tenant → 200
- list with admin missing tenant → 400
- list with ApiKey + tenant → 400 (prevents peer-tenant guessing)
- get/update/delete/test/deliveries under admin on cross-tenant subs → 200/204 happy path
- patch/delete/test audit metadata → `actor_type=admin_on_behalf_of`
- create with admin key → 401 (footgun guard)

**Gates.** `mvn test` → **506/506 pass** (was 498; +8). Build clean.

---

### 2026-04-13 — v0.1.25.15 canonical scope validation

Reported during v0.1.25.14 end-to-end testing: user accidentally created a budget with scope `tenant:acme/agentic:codex` — "agentic" is a typo for the canonical kind "agent", but the server happily persisted it with 201 Created. Probing revealed the server was doing essentially no scope validation: `workspace:eng` (no tenant prefix) also worked, as did reverse-ordered kinds. Per `cycles-protocol-v0.yaml` SCOPE DERIVATION (NORMATIVE): *"Canonical ordering is: tenant → workspace → app → workflow → agent → toolset."* Non-canonical scopes silently break downstream enforcement (reservations can't match them) and pollute audit trails with identifiers that break tooling built on the canonical taxonomy.

**New `ScopeValidator` utility** in `cycles-admin-service-api/config/`. Called from `BudgetController.create` and `PolicyController.create`/`.update`. Enforces:

| Rule | Reject | Accept |
|---|---|---|
| First segment must be `tenant:<id>` | `workspace:eng` | `tenant:acme` |
| Canonical kind set only | `tenant:acme/florb:blerp`, `tenant:acme/agentic:codex` | `tenant:acme/agent:codex` |
| Kinds appear in canonical order, no duplicates | `tenant:x/agent:a/workspace:w`, `tenant:x/agent:a/agent:b` | `tenant:x/workspace:w/agent:a` |
| Each id non-empty, ≤128 chars, matches `[A-Za-z0-9._-]+` | `tenant:acme/agent:` , `tenant:acme/agent:bad id` | `tenant:acme-corp.v2/agent:rev_01` |
| Cross-field: scope tenant must match request `tenant_id` | body `tenant_id=acme` + scope `tenant:corp/...` | matching |

**Wildcards in policy `scope_pattern`** — bare `*` as terminal segment (`tenant:acme/*` = all descendants) and id-wildcard (`tenant:acme/agent:*`) both allowed, per spec examples. Mid-chain wildcards rejected (unreachable after match). Budget scopes remain concrete — no wildcards.

**Error shape.** All rejections return 400 INVALID_REQUEST via GovernanceException, with a specific message identifying which rule failed on which segment — easier to self-correct than a generic "invalid scope".

**Tests** (+29):
- `ScopeValidatorTest` — 27 cases covering every rule: bare-tenant accepted, full chain accepted, skipped levels allowed, the exact `agentic:codex` bug the user reported, missing-tenant-prefix, garbage kind, reverse order, duplicate kinds, empty id, disallowed chars, bare-wildcard terminal accepted in patterns, wildcard rejected in budgets, wildcard mid-chain rejected, tenant-cross-check happy + mismatch, `extractTenantId` helper.
- `BudgetControllerTest` — existing test fixtures updated from non-canonical `org/team1` to canonical `tenant:tenant-1/workspace:team1`. Pre-existing scope-filter-allowed test now uses a coherent tenant (filter aligned with authenticated tenant).
- `PolicyControllerTest` — existing `org/*` test fixtures updated to `tenant:tenant-1/*`.
- Admin-on-behalf-of tests updated to use coherent `tenant_id` and scope (the previous placeholder `tenant-acme` / `tenant:acme/...` didn't satisfy cross-field check).

**Spec compliance.** The server now ENFORCES what the spec describes as canonical. No spec change required — this is tightening an existing implicit contract.

**Backward compatibility.** Pre-existing budgets or policies in Redis with non-canonical scopes keep working (read paths don't re-validate). New creates must use canonical scopes; clients sending non-canonical now get 400 with a clear error message instead of silent success.

**Pre-merge review hardening** (post code review of this PR):
- **Tightened id regex** from `[A-Za-z0-9._-]+` to `[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?` — rejects ids with leading/trailing punctuation (`.acme`, `acme.`, `-foo`) which are surprising in audit output and never intentional. Single-char alphanumeric ids and mid-string punctuation (`a.b_c-d.v2`) still accepted.
- **Regression lock** in `ScopeValidatorTest` for the claim "`tenant:*/agent:foo` passes validation". It doesn't — the terminal-id-wildcard rule correctly rejects it. Test documents that lock-in.
- **Documented** via test + comment that `tenant:*` alone is a legal policy pattern at the grammar layer (matches all tenant-rooted scopes), but is blocked by the tenant-cross-check for admin-on-behalf-of creates — preventing system-wide wildcard policies from being attributed to a specific tenant accidentally.
- **Equality branch** of the duplicate-kind check (`kindIdx <= lastKindIdx`) now has a dedicated mid-list test (`workspace/workspace` at kindIdx=1) in addition to the pre-existing boundary test (`agent/agent` at kindIdx=4).
- **Forward-guard comment** in `PolicyController.update`: `scope_pattern` is intentionally absent from `PolicyUpdateRequest` (patterns are immutable per spec); if a future change adds it, the comment directs the engineer to add `ScopeValidator.validatePolicyScopePattern` to prevent PATCH from becoming a non-canonical-scope bypass.

**Tests.** **497/497 pass** (was 459; +38). Coverage check passes.

---

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
