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

## [0.1.25.51] — 2026-07-11

### Security

- **Fail-closed DISPATCH boundary — the durable confidentiality guarantee (#209).**
  The write-path gates (below) stop NEW offenders and the reconciler (below)
  cleans up STORED ones, but both are point-in-time: a concrete-tenant
  subscription that already holds admin-only selectors and has not yet been
  reconciled would still deliver admin-only events. The guarantee is now
  enforced at DELIVERY, unconditionally: in BOTH live dispatch
  (`WebhookDispatchService`) and replay (`dispatchToSubscription`), an event is
  skipped **per event** when the subscription is concrete-tenant-owned
  (`isSystemOwner == false`) AND the event is admin-only (reusing the
  `WebhookCategoryBoundaryValidator` boundary). The predicate is strictly
  fail-closed: it blocks if EITHER the event's TYPE **or** its (independent,
  cross-plane) CATEGORY is admin-only, and it blocks an unclassifiable record
  (type and category both null) — `Event.category` is not always derived from
  the type, so a malformed or type/category-inconsistent record can't slip
  through to a tenant endpoint. This does not depend on the reconciler having
  run, on stored-selector correctness, or on status transitions — a
  concrete-tenant subscription can never receive an admin-only event. It filters
  per event, so a mixed subscription still receives its tenant-accessible
  events; only the admin-only ones are withheld (counted as `boundary_skipped`).
  `__system__`-owned subscriptions are unaffected.
  - **`/test` exception (documented).** `POST /v1/webhooks/{id}/test` and its
    admin twin POST a spec-defined `system.webhook_test` ping DIRECTLY to the
    subscription URL (not via the dispatch queue), so a tenant-owned
    subscription's own test delivers an admin-category (`system`) synthetic
    event. This is a narrow, explicit EXCEPTION to the invariant: the governance
    spec (the authority) defines the test event as `system.webhook_test`, the
    payload is an owner-triggered probe (`{subscription_id, test:true}`) carrying
    NO real governance/security telemetry, and reclassifying the wire envelope
    would both diverge from the spec and produce the type/category-inconsistent
    record the dispatch boundary now blocks. Confidentiality is intact (no real
    admin telemetry reaches a tenant endpoint); only a synthetic self-test
    carries the `system` label. The spec-side exception is tracked separately.

- **Admin-plane webhook boundary — closes the CARRIER source (#209).**
  `POST /v1/admin/webhooks?tenant_id=X` and `PATCH /v1/admin/webhooks/{id}`
  applied no boundary validation, so an operator / admin-on-behalf-of could
  place admin-only event **types OR categories** (`api_key`, `policy`,
  `webhook`, `system`) on a subscription owned by a concrete tenant X. Since
  event matching unions types and categories, X — which controls that
  subscription's endpoint URL and signing secret — then received admin-only
  governance/security telemetry for its tenant. The 0.1.25.50 fix closed the
  tenant-plane *injection* path; this closes the admin-plane *carrier* source.
  Both admin write paths now validate `event_types` AND `event_categories`
  against the tenant-accessible boundary (`budget.*`, `reservation.*`,
  `tenant.*`) when the target is a **concrete tenant** — create validates
  against the `tenant_id` param, update against the **effective resulting
  selectors** (stored ∪ request) on the STORED subscription's `tenant_id` —
  rejecting admin-only entries with 400 `INVALID_REQUEST`. Validating the
  effective result means a status-only reactivation (`{"status":"ACTIVE"}`)
  cannot re-enable a disabled offender that still holds admin-only selectors.
  Governance spec revision v0.1.25.40 (pending, runcycles/cycles-protocol#129)
  makes this normative.
  - **`__system__` carve-out:** admin-only selectors remain allowed on
    `__system__`-owned subscriptions (no `tenant_id` param, or an
    `__system__` target) — legitimate system-wide monitoring, not
    tenant-owned. A null/omitted owner is treated the same; a blank
    (whitespace-only) `tenant_id` is NOT exempt (treated as concrete and
    validated).

- **Replay honors the subscription's own selectors (#209).**
  `POST /v1/admin/webhooks/{id}/replay` filtered historical events only by
  the request's optional `event_types` + the subscription's `scope_filter`,
  never by the subscription's OWN `event_types`/`event_categories`, and did
  not check status — so replaying a concrete-tenant budget-only subscription
  could dispatch historical ADMIN-only events to its tenant-controlled
  endpoint (a direct bypass of this boundary; likely pre-existing). Replay now
  intersects delivered events with the subscription's own selectors (spec
  `replayEvents`: "all event types the subscription is subscribed to") and is
  a no-op on a non-`ACTIVE` subscription — so a DISABLED offender receives
  nothing via replay either. Status is additionally **re-checked at dispatch
  time** (in `dispatchToSubscription`, after the initial guard) so a
  subscription disabled concurrently mid-replay stops delivering (closes a
  status TOCTOU). The fail-closed dispatch boundary above also applies on the
  replay path, so even an ACTIVE offender delivers none of its admin-only
  historical events.
  - **Cap-then-filter under-delivery fixed (no-miss AND no-duplicate).** Replay
    previously fetched a single page capped at `max_events` and then applied the
    selector/scope/type filters AS post-hoc stream filters — so if the first
    `max_events` (default 100) events in the `[from,to]` window didn't match but
    later ones did, replay queued ZERO despite matching events existing. Replay
    now takes ONE bounded, ordered, de-duplicated event-id list for the window
    (a single `ZRANGEBYSCORE`, `EventRepository.listEventIdsInRange`) and
    hydrates + filters in fixed batches over it, applying every filter — request
    `event_types` ∩ the subscription's own selectors ∩ `scope_filter` ∩ the
    ownership boundary — while accumulating up to `max_events` **deliverable**
    events (so the cap counts deliverable events, not scanned ones), then
    delivers them in chronological order (spec `replayEvents`). Reading the whole
    ordered member set up front (rather than walking a millisecond score cursor)
    avoids three cursor hazards on the ms-scored index: equal-timestamp members
    skipped when the cursor advanced by `score+1`, a hydration-thinned short page
    misread as range exhaustion, and duplicate pages when a cursor member had
    vanished. No change to `EventRepository.list()` or its cursor, so other
    event-listing callers are unaffected.
  - **Replay is ALL-OR-NARROW (a success is complete for its window).**
    `max_events` is NOT a resumable pagination cursor — `ReplayResponse` carries
    no continuation position, so a partial result could not be resumed losslessly
    (distinct timestamps: the caller never learns the last replayed timestamp;
    same-millisecond events: an inclusive `from` would repeat or skip). So a
    SUCCESSFUL replay delivers EVERY deliverable event in `[from,to]`; it never
    returns a partial with an implied "continue". Two fail-fast **400
    `INVALID_REQUEST`** cases (thrown BEFORE any delivery is enqueued — no partial
    side effects; the replay lock is released):
    - the window's candidate count exceeds the server scan limit
      (`webhook.replay.max-scan`, default 20000) — completeness can't be
      guaranteed over the unscanned tail — *"replay window too large: it exceeds
      the replay scan limit of N events; narrow the from/to range"*; or
    - the fully-scanned window holds MORE than `max_events` deliverable events —
      *"replay window contains more than max_events (N) deliverable events;
      narrow the from/to range, or raise max_events (up to 1000)"*.

    Otherwise (fully scanned, ≤ `max_events` deliverable) ALL are delivered,
    chronologically. There is no "advance `from` to continue" / "lower
    `max_events` to page" guidance — `max_events` bounds the window size, it is
    not a continuation cursor.
  - **`max_events` is validated `[1,1000]` (400, not silently clamped).**
    `ReplayRequest.max_events` now carries `@Min(1) @Max(1000)` (jakarta
    validation) and the replay endpoint's `@RequestBody` is `@Valid`, so
    `max_events=0` or `max_events=1001` is rejected with **400** (matching the
    governance spec's declared `minimum:1`/`maximum:1000`). The prior
    `Math.min(max_events, 1000)` silent clamp is removed. Omitting `max_events`
    still defaults to 100.
  - **Enqueue is BEST-EFFORT, and `events_queued` reports the accepted count
    (governance #130).** Selection is complete for the window, but per-event
    enqueue onto the dispatch queue is best-effort: on a transient backend
    failure `events_queued` may be fewer than the events selected. A **loud WARN**
    (`replay_id`, `subscription_id`, `selected`, `queued`) is logged whenever
    `events_queued < selected` so a degraded backend is operator-observable.
    Replay is **NOT idempotent** — delivery IDs are random, so a retry may
    duplicate already-queued deliveries.

- **Bulk RESUME routed through boundary validation (#209).**
  `POST /v1/admin/webhooks/bulk-action` with `action: RESUME` reached `ACTIVE`
  via `webhookService.update` directly, bypassing the single-op effective
  -selector validation — so a PAUSED concrete-tenant offender could be bulk
  -resumed. RESUME now validates the stored (effective) selectors against the
  owning tenant before reactivating; an offender fails the row
  (`INVALID_TRANSITION`) instead of resuming.

- **BEHAVIOR CHANGE — previously-allowed capability removed.** In 0.1.25.50
  the admin plane deliberately did NOT restrict categories (documented as
  "the boundary is tenant-plane-only, not a global tightening"), so
  admin-only categories on a concrete-tenant subscription via
  `/v1/admin/webhooks` returned 201/200. That is now a 400 — a security
  correction, because a tenant-owned row exposes the endpoint URL + signing
  secret to the tenant. **Migration:** if you were monitoring a specific
  tenant's admin-only events (e.g. `api_key.*`, `policy.*`) via a tenant-owned
  subscription pointed at an operator endpoint, replace it with a
  **`__system__`-owned** subscription (create with no `tenant_id` param)
  filtered by **`event_categories`** to the admin classes you need. Because
  `__system__` is in the dispatch union for every tenant, that subscription
  receives those admin events for ALL tenants; select the specific tenant
  **client-side** on the envelope's `tenant_id`. Note on `scope_filter`:
  `api_key.*`, `webhook.*` and `system.*` events are **null-scoped**, and a
  `scope_filter` excludes null-scoped events, so it would deliver none of
  them — client-side `tenant_id` filtering is the general solution. `policy.*`
  events carry a real tenant-bounded `scope` and CAN be `scope_filter`ed if
  you only need policy events. The `__system__` row is not tenant-owned, so
  its URL and secret stay operator-controlled.

### Fixed

- **One-time STORAGE HYGIENE for pre-existing offender rows (#209, d2).** A
  startup reconciler (idempotent, resumable, no new API surface) brings STORED
  selectors in line with the delivery behavior the dispatch boundary already
  enforces. **This is not the security mechanism** — the fail-closed dispatch
  boundary (above) is; the reconciler is best-effort cleanup and, because
  dispatch already blocks the leak, admin-only selectors on a concrete-tenant
  row are already non-functional. The strip/disable actions skip already
  -`DISABLED` rows (idempotent); the NORMALIZE and INDEX actions run regardless
  of status. Per row:
  - **Concrete-tenant rows carrying admin-only types/categories:** the
    admin-only selectors are **STRIPPED** and tenant-accessible ones kept
    (`STRIPPED_ADMIN_SELECTORS`). Because dispatch already withholds those
    admin events, stripping is not a behavior change and has no collateral on
    legitimate tenant-accessible deliveries; it makes storage match effective
    behavior. If stripping empties BOTH selector lists (the row would then
    match every event) it is DISABLED instead (`STRIPPED_AND_DISABLED`).
  - **Empty-both match-ALL rows** on any owner (`__system__` included — the
    system carve-out exempts admin selectors only, not the "at least one
    selector" invariant) are **DISABLED** (`DISABLED_EMPTY_BOTH`).
  - **Null-owner rows** (corruption): normalized to the `__system__` owner and
    added to the `webhooks:__system__` dispatch index (`NORMALIZED_NULL_OWNER`),
    removing the limbo state (exempt from repair yet absent from every dispatch
    index) rather than leaving them un-indexed. The owner rewrite (SET) and the
    index add (SADD) run in ONE atomic Lua op, so a partial failure can never
    persist the owner while leaving the row un-indexed. This runs even for a
    DISABLED null-owner row.
  - **System-owned rows MISSING from the index** (e.g. left behind by a prior
    partial normalization): membership repaired via an idempotent SADD,
    independent of status (`INDEXED_SYSTEM_MEMBER`).

  Every action is loudly logged. **Robustness:** it `SSCAN`s the index in
  batches (bounded memory), writes via an atomic compare-and-set + index add
  (never clobbers a concurrent operator update — a CAS miss is a counted failure
  and retried), and runs on a **managed daemon executor** that is interrupted and
  awaited on Spring context shutdown (`@PreDestroy`) so it stops cleanly instead
  of leaking a thread sleeping through a retry backoff. Startup/readiness is
  never blocked; an incomplete pass (row error or CAS miss) is retried with
  exponential backoff, and if retries are exhausted a loud `ERROR` alert is
  emitted and the service stays up (hygiene must not brick the service — and
  delivery is already safe via the dispatch boundary). Configure with
  `webhook.category-boundary.reconcile-on-startup` (default `true`) and
  `webhook.category-boundary.reconcile-dry-run` (default `false` — `true`
  REPORTS the actions in the logs without mutating anything). Operators can
  also find offenders with the `redis-cli`/`jq` recipe from the [0.1.25.50]
  Security note.

- **Replay `events_queued` no longer over-reports (#209).** `dispatchToSubscription`
  returned `true` even when the delivery row was persisted but the `LPUSH` onto
  `dispatch:pending` failed (the enqueue error was swallowed), so replay counted
  a delivery the worker would never pick up. `createDelivery` now returns whether
  the row was actually ENQUEUED, and `dispatchToSubscription` (and the live
  dispatch metric) propagate it — `events_queued` counts only real enqueues.

## [0.1.25.50] — 2026-07-10

### Security

- **Tenant-plane webhook `event_categories` are now validated against the
  tenant-accessible boundary.** `POST /v1/webhooks` and
  `PATCH /v1/webhooks/{id}` validated `event_types` (budget.*,
  reservation.*, tenant.* only) but never `event_categories` — and event
  matching treats categories as an ADDITIVE union with types. A tenant API
  key could therefore create or update a subscription with one allowed
  `event_type` plus an admin-only category (`api_key`, `policy`, `webhook`,
  `system`) and receive admin-only event classes for its tenant (e.g.
  `api_key.created` payloads). Both paths now reject admin-only categories
  with 400 `INVALID_REQUEST`, same message style as the `event_types` check
  (governance spec revision v0.1.25.38, merged 2026-07-10 —
  cycles-protocol#126). The boundary applies to
  the tenant-plane endpoint regardless of caller — admin-on-behalf-of PATCH
  included — matching the existing `event_types` behavior; admin categories
  belong on `/v1/admin/webhooks/*`.

  **Operator note — audit existing subscriptions:** on 0.1.25.49 and
  earlier, tenant-created subscriptions may already carry admin-only
  categories, and a legacy PATCH could also leave a subscription with
  **both** `event_types` and `event_categories` empty, which matches
  **every** event class (delivery-side wildcard). Both classes are still
  deliverable until repaired. This single pass flags both — it scans only
  subscription keys (`webhook:whsub_*`; secrets live under
  `webhook:secret:*` and indexes under `webhooks:*`, neither of which
  matches) and tolerates any non-JSON value via `fromjson?`:

  ```bash
  redis-cli --scan --pattern 'webhook:whsub_*' \
    | while read -r k; do redis-cli GET "$k"; done \
    | jq -rR 'fromjson? // empty
        | (.event_categories // []) as $cats
        | (.event_types // []) as $types
        | ($cats - ["budget","reservation","tenant"]) as $admin
        | if ($admin | length) > 0 then
            "ADMIN_CATEGORIES \(.subscription_id) \(.tenant_id) \($admin)"
          elif ($types | length) == 0 and ($cats | length) == 0 then
            "MATCH_ALL \(.subscription_id) \(.tenant_id)"
          else empty end'
  ```

  `ADMIN_CATEGORIES` rows carry admin-only categories; `MATCH_ALL` rows are
  the empty-both wildcards. Repair each with a `PATCH /v1/webhooks/{id}`
  that sets a valid `event_types`/`event_categories` (this release rejects
  the offending shapes), or delete it. Subscriptions created via
  `/v1/admin/webhooks` by operators are legitimate carriers of admin
  categories — check `tenant_id` provenance against your admin-provisioned
  list, or the `createTenantWebhook` (tenant-plane) vs
  `createWebhookSubscription` (admin-plane) audit-log entries — before
  repairing an `ADMIN_CATEGORIES` hit.

### Fixed

- **PATCH can no longer produce a match-ALL subscription.** Create requires
  a non-empty `event_types`, but update accepted `"event_types": []` — and
  a subscription with BOTH `event_types` and `event_categories` empty
  matches every event class (delivery-side wildcard). Update now returns
  400 `INVALID_REQUEST` ("Subscription must retain at least one event_type
  or event_category") on both the tenant and admin planes when a PATCH
  would leave both empty. Category-only subscriptions (clearing
  `event_types` while categories remain) are still legal.

## [0.1.25.49] — 2026-07-10

### Changed

- **BEHAVIOR CHANGE — webhook `scope_filter` matching is now spec-conformant
  (wildcard semantics).** `WebhookRepository.matchesScope` previously did
  literal prefix matching: any event scope that `startsWith(scope_filter)`
  matched, a spec-style trailing-`/*` filter matched nothing (the `*` was
  compared literally), and events with a **null** scope matched every filter.
  The admin OpenAPI spec (`scope_filter`: *"Optional scope pattern to narrow
  event matching. Supports wildcards: "tenant:acme-corp/*" matches all scopes
  under acme-corp. If omitted, matches all scopes within the tenant."*) is the
  authority, so the matcher now implements:
  - null/blank filter — matches every event, including null-scope events
    (unchanged);
  - bare `*` — matches every event that **has** a scope; null-scope and
    blank-scope events are excluded;
  - trailing `*` (e.g. `tenant:acme-corp/*`) — prefix match on the filter
    minus the `*`; matches all scopes **under** the base (children only — the
    bare base scope `tenant:acme-corp` does not match, nor does the degenerate
    `tenant:acme-corp/` with an empty child segment);
  - no trailing `*` — **exact** match only (case-sensitive); child scopes no
    longer match; any non-trailing `*` is a literal character;
  - non-blank filter + null **or blank** event scope — **not** delivered
    (a blank scope is treated as unscoped).

  **Migration:**
  - *Descendants only:* a prefix-style filter such as `tenant:acme-corp` or
    `tenant:acme-corp/` must be rewritten as `tenant:acme-corp/*` to keep
    matching child scopes — without the wildcard it now matches only an
    exactly-equal event scope.
  - *Base scope + descendants:* the old prefix matcher **also matched the
    exact base scope** (`tenant:acme-corp` matched an event scoped exactly
    `tenant:acme-corp`), so migrating to `tenant:acme-corp/*` alone **drops
    base-scope events**. A single `scope_filter` cannot express "base plus
    descendants" — to preserve the old coverage, create **two**
    subscriptions: one with the exact filter `tenant:acme-corp` and one with
    the wildcard `tenant:acme-corp/*`.
  - *Sibling prefixes:* the old character-wise matching also caught
    lexical-sibling scopes such as `tenant:acme-corpX` or
    `tenant:acme-corp-eu`. Those matches were accidental; anyone relying on
    them must now add explicit filters (exact or `…/*`) for each such scope.
  - *Unscoped events:* subscriptions that relied on receiving **unscoped**
    (null-scope) events despite having a `scope_filter` must drop the filter
    (or add a second, unfiltered subscription) to keep receiving them.

### Fixed

- **Webhook replay now honors `scope_filter`.** `POST
  /v1/admin/webhooks/{subscription_id}/replay` queued every event in the
  requested time range (after the request's `event_types` filter) directly to
  the subscription, bypassing scope matching entirely — a replay could
  deliver events the subscription would never have received live. Replay now
  applies the same spec-conformant matcher as live dispatch
  (`WebhookRepository.matchesScope`, exposed publicly for exactly this reuse)
  before queuing; the replay request's `event_types` filtering behavior is
  unchanged.

### Compatibility

- No HTTP request/response schema, Redis data model, or Lua change. The
  changes are in event→subscription matching at delivery time
  (`findMatchingSubscriptions`) and in replay event selection; which
  subscriptions receive a given event (live or replayed) can change as
  described above.
- Release prep: prod compose self-pin → `0.1.25.49`; bundled full-stack
  `cycles-server` pin → `0.1.25.47` (runtime TENANT_CLOSED Rule 2 guard +
  webhook matcher parity — the runtime half of the matcher convergence this
  release completes; see that release's notes). `cycles-server-events` stays
  `0.1.25.22`.

## [0.1.25.48] — 2026-07-04

### Fixed

- **Cascade event payloads now map to their own typed class.** The four
  `*_via_tenant_cascade` EventTypes were registered in
  `EventPayloadTypeMapping` against nearest-fit lifecycle classes
  (`EventDataBudgetLifecycle` / `EventDataSystem` / `EventDataApiKey`), but
  the payload `TenantCloseCascadeService` actually emits (flat
  `prior_status` / `new_status` / `cascade_reason` + object identity) fits
  none of them — so the warn-only payload-shape validator flagged every
  cascade emission. New `EventDataTenantCascade` model class mirrors the
  spec schema of the same name (governance spec v0.1.25.35); all four kinds
  remap to it. No wire change — `Event.data` was and remains the same
  emitted map; only the internal validation/registry class changed.

### Compatibility

- No HTTP request/response, Redis, event-wire, or spec-surface change.
- Release prep: prod compose self-pin → `0.1.25.48`; bundled full-stack
  pins bumped to the 2026-07-04 security releases (`cycles-server`
  `0.1.25.46` — public-endpoint 429 rate limiting; `cycles-server-events`
  `0.1.25.22` — delivery-time SSRF guard; see those releases' notes for
  the `allow_http`/CIDR operator note on webhook targets).

## [0.1.25.47] — 2026-06-26

### Fixed

- Production Compose image tags now point at `cycles-server-admin:0.1.25.47`.
- The admin runtime image now includes a built-in readiness healthcheck and
  starts Java through `exec java $JAVA_OPTS -jar app.jar`, so the JVM receives
  container SIGTERM directly and operators can tune JVM flags through
  `JAVA_OPTS`.
- Production Compose now sets conservative JVM options for the admin container
  through `JAVA_OPTS`.
- Full-stack production Compose now references current published sibling images:
  `cycles-server:0.1.25.44` and `cycles-server-events:0.1.25.20`.
- Full-stack production Compose now passes `ADMIN_API_KEY` to the runtime
  service, disables runtime public SpringDoc/Swagger and tenant-labelled custom
  Prometheus metrics, and probes runtime readiness rather than aggregate health.
- Full-stack production Compose now publishes the events management port `9980`
  and probes `/actuator/health/readiness`; the worker app port `7980` remains
  internal.
- README/OPERATIONS deployment docs now reflect required production Compose
  environment variables, current Spring Boot/Jedis versions, protected docs
  defaults, `JAVA_OPTS`, and the events management-port split.

### Compatibility

- Deployment and documentation hardening only. No admin HTTP schema, Redis data
  model, event payload, webhook wire, Lua, or cycles-protocol spec change.

## [0.1.25.46] — 2026-06-26

### Changed

- **Jedis 7.5.0 → 7.5.2** — aligns the admin service with `cycles-server` (already
  on 7.5.2) and picks up the latest 7.5.x Redis-client patches.
- **springdoc-openapi 2.8.16 → 2.8.17** — latest 2.8.x, matching `cycles-server`.

### Compatibility

- Dependency-currency only. No HTTP request/response, Redis data model, Lua,
  event, webhook-wire, or spec change.

## [0.1.25.45] — 2026-06-25

### Security

- Hardened production Compose Redis defaults: Redis no longer publishes
  `6379`, requires `REDIS_PASSWORD`, authenticates health checks, and restarts
  unless stopped.
- Protected aggregate actuator, OpenAPI, and Swagger endpoints with
  `X-Admin-API-Key`; exact liveness and readiness probe endpoints remain open.
- Production Compose now requires `WEBHOOK_SECRET_ENCRYPTION_KEY` and starts
  the admin service with `WEBHOOK_SECRET_ENCRYPTION_REQUIRED=true`, causing
  startup to fail closed when webhook secret encryption is required but no key
  is configured.
- Webhook test delivery now re-validates stored target URLs against the
  current SSRF policy before opening an outbound connection.

### Changed

- Production health checks and release smoke tests now use
  `/actuator/health/readiness`, which includes Redis readiness.
- Generated API docs are disabled by default unless `API_DOCS_ENABLED=true`.
- Production Compose enables per-source, per-process throttling for repeated
  401/403 authentication failures; throttled responses return
  `429 LIMIT_EXCEEDED` without writing another failure audit row.
- Production Compose image tags now point at `0.1.25.45`.

### Compatibility

- No admin request/response body, Redis key/value, Lua, event payload, webhook
  payload, or cycles-protocol spec change.
- Operational tooling that reads protected actuator or docs endpoints must send
  `X-Admin-API-Key`. Kubernetes-style liveness/readiness probes do not need an
  admin key.

## [0.1.25.44] — 2026-06-24

### Fixed

- Added common CR/LF flattening for dynamic operator-log fields across
  exception, auth rejection, controller side-effect, bulk-row, event dispatch,
  webhook test/replay, webhook security config, and audit fallback logs.
- Extended the same flattening to the data-plane repository, idempotency, and
  config logs (api-key, audit, budget, event, policy, tenant, webhook,
  webhook-delivery) via a shared `LogSanitizer`, so request-derived strings
  logged below the controller layer cannot inject log lines either.
- Flattened caller-supplied `X-Request-Id` at filter ingress and sanitized
  request-attribute helper methods before those values are reused in auth,
  exception, audit, and controller logs.
- Kept the v0.1.25.43 operational context while preventing request/config/
  exception strings from injecting misleading log lines.

### Compatibility

- No admin HTTP request/response, Redis key/value, Lua, event payload, webhook
  wire, or spec change.

## [0.1.25.43] — 2026-06-24

### Changed

- **Ops logging context review.** Admin API exception, auth rejection,
  controller side-effect, webhook replay/test, event dispatch, idempotency,
  audit, and Redis repository logs now include stable operational identifiers
  such as method/path/route, status/error code, tenant, key id, budget/policy/
  webhook/event/delivery ids, request id, trace id, correlation id, queue names,
  and exception class where available.
- Replaced the ambiguous `Landed in governance exception handler` log with a
  structured `Governance exception handled` record that names the endpoint,
  error code, status, request id, trace id, and exception class.
- Startup, CORS, Redis, and webhook security config logs now use structured
  fields instead of banner-style or context-poor text.
- Sensitive or noisy values are avoided in logs: API-key/idempotency secrets are
  not emitted, idempotency keys are fingerprinted, and webhook test delivery
  failures log `target_host` instead of the full subscriber URL.

### Compatibility

- No admin HTTP request/response shape change.
- No Redis key, queue, Lua, or payload-shape change.
- No cycles-protocol spec change.

## [0.1.25.42] — 2026-06-24

Security and dependency-hygiene rollup accumulated since v0.1.25.41. No code
or wire-format changes — pom, CI, and ops only. The headline is the Apache
Tomcat CVE patch; the same release boundary also rolls up a Jedis major bump,
a Spring Boot patch, and supply-chain/CI hardening.

### Changed

- **Tomcat 10.1.54 → 10.1.55** (security). Re-introduced the
  `<tomcat.version>10.1.55</tomcat.version>` override to close 3 CRITICAL +
  3 HIGH + 1 LOW `tomcat-embed-core` CVEs (CVE-2026-43515 / -43512 / -41293
  CRITICAL; -43513 / -42498 / -41284 HIGH; -43514 LOW) that Trivy flagged
  against Spring Boot 3.5.14's managed 10.1.54. Same override pattern as the
  retained `commons-lang3` pin; remove once Spring Boot's BOM manages
  10.1.55+. Full CVE breakdown in [`AUDIT.md`](AUDIT.md).
- **Jedis 6.2.0 → 7.5.0** (major). Redis-client major bump aligning the
  admin service with the rest of the fleet. All call sites use stable APIs;
  CI's full suite (782+ tests) passes on 7.5.0.
- **Spring Boot 3.5.14 → 3.5.15** (patch). Upstream patch release. The
  `commons-lang3 3.18.0` override is retained — SB 3.5.15's BOM still manages
  3.17.0, where CVE-2025-48924 is unfixed.
- **Container log rotation.** All four `docker-compose*.yml` files gain a
  shared `json-file` logging anchor (`max-size: 10m`, `max-file: 5`), capping
  each container at 50 MB to prevent unbounded `*-json.log` growth on
  long-running deployments.

### Security / CI

- Supply-chain hardening: OpenSSF Scorecard workflow, all third-party GitHub
  Action SHAs pinned, workflow token permissions tightened, Alpine `gnutls`
  CVE-2026-33845 patched in the image, and a two-phase Trivy-gated release
  build (scan before push).
- Trivy container gates (release + PR) now set `limit-severities-for-sarif:
  true` so `exit-code` honors the declared `HIGH,CRITICAL` severity. In
  `format: sarif` mode the action otherwise gates on any fixable finding of
  any severity. A known fixable MEDIUM (`jackson-databind` CVE-2026-54515,
  fix awaiting the unreleased jackson 2.21.5) is reported but no longer
  blocks publish; HIGH/CRITICAL still fail the gate.

## [0.1.25.41] — 2026-04-26

Dependency hygiene aligning all three Cycles services (events / server /
admin) on the same Spring Boot patch and Redis client major. No code or
wire-format changes — pom-only patch.

### Changed

- **Spring Boot 3.5.13 → 3.5.14.** Patch upgrade picking up upstream
  security hardening (constant-time comparison for remote DevTools
  secret, `RandomValuePropertySource` switched to `SecureRandom`,
  hostname verification applied consistently for Cassandra/RabbitMQ
  SSL) plus symlink-handling fixes in `ApplicationPidFileWriter` /
  `ApplicationTemp`. Mirrors the events-server bump shipped in
  `cycles-server-events` v0.1.25.12 and the protocol-server bump
  shipped in `cycles-server` v0.1.25.18.
- **Drop `<tomcat.version>10.1.54</tomcat.version>` override.** Spring
  Boot 3.5.14's BOM now manages Tomcat 10.1.54 directly (verified
  against `spring-boot-dependencies-3.5.14.pom`), so the explicit pin
  added in v0.1.25.33 to close CVE-2026-34483 / CVE-2026-34487 is
  redundant. Same effective Tomcat version, smaller pom diff for
  future Spring Boot bumps.
- **Jedis 5.2.0 → 6.2.0** (major). Aligns with `cycles-server-events`
  (6.2.0 since v0.1.25.12) and `cycles-server` (6.2.0 in v0.1.25.18)
  on a single Redis-client major across the fleet. Jedis 6.1.0
  explicitly restored binary compatibility for `SetParams` (#4225
  upstream); all call sites use stable APIs and all 782 tests pass
  on 6.2.0.

### Retained

- `<commons-lang3.version>3.18.0</commons-lang3.version>` override
  stays — Spring Boot 3.5.14's BOM still manages commons-lang3 at
  3.17.0 (CVE-2025-48924 unfixed there), so the explicit 3.18.0 pin
  added in v0.1.25.34 is still required. Override comment updated to
  reference SB 3.5.14.

## [0.1.25.40] — 2026-04-23

Post-review correctness pass on v0.1.25.39 webhook lifecycle Events. No new
spec surface, no wire-format changes — strictly better output for
`listEvents` consumers.

### Fixed

- **Actor parity on single-op webhook lifecycle emits.** `createWebhookSubscription`,
  `updateWebhookSubscription`, and `deleteWebhookSubscription` now populate
  `Actor.keyId` from `authenticated_key_id`, matching the bulk-action path.
  Audit consumers see consistent API-key attribution across single-op and
  bulk emits.
- **`changed_fields` is now a real diff.** `updateWebhookSubscription`
  previously listed any field present in the PATCH body even when the value
  matched the subscription's current value. It now compares each provided
  field against the prior snapshot and only records genuine mutations.
  Consequence: a PATCH that re-sends existing values on every field AND
  does not flip status is a true no-op and emits nothing (matching spec
  v0.1.25.33 §6281). `signing_secret` retains presence-based detection —
  the subscription's stored secret is not safely comparable to the
  plaintext request value.
- **Correlation-id uniqueness under defensive paths.** The `"no-req"`
  literal fallback in `webhook_update:<sub_id>:<request_id>` and
  `webhook_bulk_action:<action>:<request_id>` (used only when
  `RequestIdFilter` did not populate the request attribute) is replaced
  with a UUID fallback (`req_<uuid>`). Prevents collision of concurrent
  correlation IDs if the filter misfires.

### Compatibility

Internal-only fixes. No EventType/schema/endpoint changes. Existing
consumers of `listEvents` see strictly-better data: `changed_fields`
shrinks on identity-PATCHes and `actor.key_id` now populates on single-op
lifecycle events.

## [0.1.25.39] — 2026-04-23

### Added

- **Webhook lifecycle Events** (spec v0.1.25.33). The single-op
  `createWebhookSubscription` / `updateWebhookSubscription` /
  `deleteWebhookSubscription` endpoints and the bulk-action
  `POST /v1/admin/webhooks/bulk-action` (PAUSE / RESUME / DELETE) now
  emit matching lifecycle Events. Previously these mutations wrote only
  an `AuditLogEntry` and left `listEvents` blind to webhook provisioning
  and state transitions. Six new `EventType` values — `webhook.created`,
  `webhook.updated`, `webhook.paused`, `webhook.resumed`,
  `webhook.disabled`, `webhook.deleted` — map 1:1 to the new
  `EventDataWebhookLifecycle` payload schema (`subscription_id`,
  `tenant_id`, `previous_status`, `new_status`, `changed_fields`,
  `disable_reason`).
- **Bulk correlation_id**: every row emitted by a single
  `bulkActionWebhooks` invocation shares
  `correlation_id = webhook_bulk_action:<action>:<request_id>`. The
  shared `request_id` ties the per-row Events to the invocation's
  single `AuditLogEntry`. Single-op correlation_ids:
  `webhook_create:<id>`, `webhook_update:<id>:<request_id>`,
  `webhook_delete:<id>`.
- **Transition-accurate typing**: a `status=PAUSED` PATCH on an
  `ACTIVE` subscription emits `webhook.paused`; a `status=ACTIVE`
  PATCH on a `PAUSED` subscription emits `webhook.resumed`; a PATCH
  that touches other fields (or no-ops on status) emits
  `webhook.updated` with the touched fields enumerated in
  `changed_fields`. Skipped and failed bulk rows
  (`ALREADY_IN_TARGET_STATE`, `ALREADY_DELETED`, `INVALID_TRANSITION`,
  `TENANT_CLOSED`) never emit — emission is bound to actual state
  transition.

### Internal

- `EventType` enum gains 6 new values; `EventPayloadTypeMapping`
  registers all six against the new `EventDataWebhookLifecycle` class.
  `EventPayloadContractTest` automatically covers the new mappings.
- `webhook.disabled` is emitted here **only** for completeness of the
  enum + schema registry; the runtime emission point for
  dispatcher-driven auto-disable lives in `cycles-server-events` (wired
  in a follow-up patch release).
- Four event lanes of bulk coverage (skipped → no emit, failed → no
  emit, emit exception → response unaffected, correlation_id shape).

Compatibility: additive-only — new enum values and a new payload schema
are non-breaking for existing consumers (typical OpenAPI codegen
ignores unknown enum values).

## [0.1.25.38.1] — 2026-04-23

### Fixed

- **README v0.1.25.x catalog backfilled** (.32 through .38). The catalog
  had stopped at v0.1.25.31; seven paragraph-shaped entries now cover
  the lenient read-side deserialization change, the Spring Boot /
  Tomcat / commons-lang3 CVE patches, the tenant-close cascade + Rule 2
  guard, the Rule 2 coverage completion, the bounded-convergence retry
  fix, and the per-row Events on bulk-action endpoints. Pure
  documentation — no code or wire change.

### Internal

- **Test hygiene:** payload-map `ArgumentCaptor` added to the five
  budget bulk-action emit tests (`CREDIT`, `DEBIT`, `RESET`,
  `RESET_SPENT`, `REPAY_DEBT`) and the three tenant bulk-action emit
  tests (`SUSPEND`, `REACTIVATE`, `CLOSE`). The previous assertions
  used `any()` for the payload argument and could not catch
  `EventDataBudgetLifecycle` / `EventDataTenantLifecycle` wire-shape
  drift; the captured map is now checked for the core keys
  (`scope`/`unit`/`operation`/`previous_state`/`new_state` for budget;
  `tenant_id`/`new_status` for tenant; `cascade` sub-map with
  `budgets_closed`/`webhooks_disabled`/`api_keys_revoked` on CLOSE).
- **Code hygiene:** `emitBulkFundEvent` now mirrors the single-op
  dual-auth `ActorType` conditional (`authenticated_tenant_id` present
  → `API_KEY`, otherwise `ADMIN`). Today bulk is AdminKeyAuth-gated so
  this always resolves to `ADMIN`, but aligning with the single-op
  pattern prevents silent mis-attribution if bulk auth is ever
  broadened. `TenantController` bulk + single-op ADMIN-hardcodes left
  intact with `// TODO actor-parity` comments — the single-op omits
  `keyId` and aligning would be a wire-shape change, deferred.

No behavior change, no API change, no spec change.

## [0.1.25.38] — 2026-04-22

### Added

- **Per-row Events on bulk-action endpoints** (spec v0.1.25.32). Previously,
  `POST /v1/admin/budgets/bulk-action` and `POST /v1/admin/tenants/bulk-action`
  wrote only a single aggregate `AuditLogEntry` per invocation; the matching
  single-op endpoints (`POST /fund`, `PATCH /tenants/{id}`) emitted a per-op
  lifecycle Event. Operators watching `listEvents` had a blind spot whenever
  the same logical operation went through the bulk path.
  - `bulkActionBudgets` now emits one Event per successfully-mutated row,
    typed by action (`budget.funded` / `budget.debited` / `budget.reset` /
    `budget.reset_spent` / `budget.debt_repaid`), with
    `correlation_id = budget_bulk_action:<action>:<request_id>`.
  - `bulkActionTenants` now emits one *parent* Event per successfully-mutated
    row (`tenant.suspended` / `tenant.reactivated` / `tenant.closed`), with
    `correlation_id = tenant_bulk_action:<action>:<request_id>`. For
    action=CLOSE this is in addition to the existing
    `tenant_close_cascade:<tenant_id>:<request_id>` cascade fan-out events,
    which are unchanged (complementary correlation_ids — operators tracing
    the bulk invocation query the bulk id; operators tracing a specific
    tenant's close query the cascade id).
  - Skipped rows (`ALREADY_IN_TARGET_STATE`) and failed rows emit no
    Event — matching single-op discipline and avoiding false signal.

### Unchanged

- No wire / OpenAPI / DTO contract change; `EventType` enum unchanged
  (existing kinds reused, no new enum values). Aggregate `AuditLogEntry`
  per invocation unchanged — spec's "one audit entry per bulk op" rule
  is preserved. Close-cascade semantics unchanged.

## [0.1.25.37] — 2026-04-21

### Fixed

- **Tenant-close cascade now actually reconverges on retry** (spec
  v0.1.25.31 Rule 1(c) bounded-convergence). Prior releases gated the
  cascade behind `isFreshClose` in the PATCH path and
  `ALREADY_IN_TARGET_STATE` in bulk-action: a mid-cascade crash left
  the tenant CLOSED but any straggler children remained non-terminal,
  and re-issuing the close was silently a no-op. The documented
  recovery path in `OPERATIONS.md` (re-issue the close; cascade is
  idempotent and picks up stragglers) now matches the code.

  - `PATCH /v1/admin/tenants/{id}` with `status=CLOSED` re-invokes
    the cascade on every request regardless of prior status. The
    parent event falls through to `tenant.updated` (not
    `tenant.closed`) on a retry so consumers don't see a duplicate
    close event; child `*_via_tenant_cascade` events still fire for
    any rows the retry actually transitions.
  - `POST /v1/admin/tenants/bulk-action` with `action=CLOSE` skips
    the redundant `repo.update` on already-CLOSED rows but still runs
    the cascade. If any children transition, the row is bucketed as
    `succeeded`; if the cascade is a full no-op, the row is bucketed
    as `skipped` with `reason=ALREADY_IN_TARGET_STATE` — keeping the
    bulk-action response honest about whether state changed.

### Unchanged

- No wire / OpenAPI / DTO contract change. Cascade service semantics
  unchanged — already idempotent per-child (repository cascade
  queries filter by non-terminal status). Only the controller-level
  gates that prevented re-entry were removed.

## [0.1.25.36] — 2026-04-20

### Added

- **Rule 2 terminal-owner mutation guard coverage completed** —
  closes the conformance gap flagged in v0.1.25.35's AUDIT entry.
  Any mutation on an object whose owning tenant is `CLOSED` now
  returns `409 TENANT_CLOSED` from every admin-mutating endpoint,
  per spec v0.1.25.29 (MUST).

  New guard callsites:
  - `POST /v1/admin/policies`, `PATCH /v1/admin/policies/{id}`
  - `POST /v1/admin/api-keys`, `PATCH /v1/admin/api-keys/{id}`,
    `DELETE /v1/admin/api-keys/{id}`
  - `POST /v1/admin/webhooks`, `PATCH /v1/admin/webhooks/{id}`,
    `DELETE /v1/admin/webhooks/{id}`,
    `POST /v1/admin/webhooks/{id}/test`,
    `POST /v1/admin/webhooks/{id}/replay`
  - `DELETE /v1/webhooks/{id}`, `POST /v1/webhooks/{id}/test`
  - Per-row in `POST /v1/admin/budgets/bulk-action` and
    `POST /v1/admin/webhooks/bulk-action` — closed-owner rows land
    in `failed[]` with `error_code: "TENANT_CLOSED"`; sibling rows
    still proceed.

### Unchanged

- No wire / OpenAPI / DTO contract change. `TENANT_CLOSED` already
  shipped in the shared `ErrorCode` enum in v0.1.25.35; .36 adds
  only callsites and the row-level classifier branch.

## [0.1.25.35] — 2026-04-20

### Added

- **Tenant-close cascade (spec v0.1.25.29 Rule 1)** — when a tenant
  transitions to `CLOSED`, owned objects now transition atomically to
  their terminal states in the same request:
  - **BudgetLedger** → `CLOSED` (stamps `closed_at`; releases any
    outstanding `reserved` amount).
  - **WebhookSubscription** → `DISABLED`.
  - **ApiKey** → `REVOKED` (stamps `revoked_at`, reason
    `tenant_closed`).
  - Any budget that had `reserved > 0` at close time also emits an
    aggregate `reservation.released_via_tenant_cascade` event with the
    total released amount.

  All touched rows produce one audit entry and one event apiece, and
  every audit + event in the cascade shares the originating request's
  `request_id` + `trace_id` plus a dedicated
  `correlation_id = tenant_close_cascade:<tenant_id>:<request_id>` so
  operators can JOIN by any of the three. Cascade is triggered by both
  `PATCH /admin/tenants/{id}` with `status=CLOSED` and the
  `tenants:bulk-action` `CLOSE` path; idempotent when the tenant is
  already closed.

- **`TENANT_CLOSED` mutation guard (spec v0.1.25.29 Rule 2)** — new
  shared error code (409). Mutating operations on any object whose
  owning tenant is `CLOSED` now short-circuit with
  `error_code=TENANT_CLOSED` instead of reaching the state-machine
  layer. Initial scope:
  - `POST /admin/budgets` (create)
  - `PATCH /admin/budgets/{scope}` (update)
  - `POST /admin/budgets/{scope}:fund`
  - `POST /admin/budgets/{scope}:freeze`
  - `POST /admin/budgets/{scope}:unfreeze`
  - `POST /admin/webhooks` (create)
  - `PATCH /admin/webhooks/{id}` (update — closes the "DISABLED
    webhook on a closed tenant can be re-enabled" gap without widening
    `WebhookStatus`)

  A tenant id that is null/blank or whose lookup fails defers to the
  caller's own validation — the guard never masks a 404 as a 409.

- **Four cascade event kinds** on `EventType` (spec v0.1.25.29):
  `BUDGET_CLOSED_VIA_TENANT_CASCADE`,
  `RESERVATION_RELEASED_VIA_TENANT_CASCADE`,
  `WEBHOOK_DISABLED_VIA_TENANT_CASCADE`,
  `API_KEY_REVOKED_VIA_TENANT_CASCADE`. New `WEBHOOK` event category
  anchors the webhook cascade kind. All four are registered in
  `EventPayloadTypeMapping` and covered by `EventPayloadContractTest`.

### Changed

- **Tenant close audit + event payload** now carries a
  `cascade_summary` map (`budgets_closed`, `webhooks_disabled`,
  `api_keys_revoked`, `reservations_released`) so downstream consumers
  can read the cascade outcome without a follow-up query.

### Internal

- New services: `TenantCloseCascadeService` (cascade orchestrator,
  sequential-idempotent; one transaction per child object; continues
  on individual-row failures to avoid half-cascaded state), and
  `TerminalOwnerMutationGuard` (Rule 2 check, called explicitly from
  each guarded controller site rather than via a servlet interceptor
  — avoids request-body pre-consumption for the tenant-in-body
  mutations).
- 17 new unit tests pin the cascade fan-out, correlation-id
  composition, reservation-release emission threshold, and guard
  behavior across blank/malformed/missing inputs. No wire break —
  additive events + additive error code.

## [0.1.25.34] — 2026-04-19

### Security

- **commons-lang3 3.18.0 pin** (CVE-2025-48924) — follow-up to the
  v0.1.25.33 Spring Boot bump; Trivy flagged one remaining HIGH
  finding that SB 3.5.13's BOM didn't cover. Added
  `<commons-lang3.version>3.18.0</commons-lang3.version>` property
  override alongside the Tomcat override; removable when SB ships a
  release with 3.18.0+ managed. No API surface change.

## [0.1.25.33] — 2026-04-19

### Security

- **Spring Boot 3.5.11 → 3.5.13 + Tomcat 10.1.54 pin** — closes 4
  HIGH/CRITICAL CVEs against `tomcat-embed-core 10.1.52`
  (CVE-2026-29145 CRITICAL, CVE-2026-29129 HIGH, CVE-2026-34483
  HIGH, CVE-2026-34487 HIGH). SB 3.5.13 brings 10.1.53 transitively;
  the `<tomcat.version>10.1.54</tomcat.version>` property override
  picks up the remaining two. Patch-level bump within the same
  3.5.x line — no API surface change.

## [0.1.25.32] — 2026-04-18

### Changed

- **Lenient deserialization on cross-plane read schemas** — `Event` and
  `WebhookDelivery` now set `@JsonIgnoreProperties(ignoreUnknown = true)`
  at the class level. Runtime (`cycles-server`) is the authoritative
  writer of `event:*` and `delivery:*` Redis records; admin only reads
  them. Previously the admin POJOs were annotated
  `ignoreUnknown = false` — so if runtime shipped an additive field in
  a patch release, admin's `listEvents` / `listWebhookDeliveries` would
  throw `UnrecognizedPropertyException` until admin lockstep-updated
  the POJO. This violated the "additive fields are safe" invariant the
  admin/runtime split is built on. Now runtime can ship additive fields
  in any patch without forcing an admin release.
- **Hygiene:** removed a dead `@JsonIgnoreProperties(ignoreUnknown = false)`
  from `ErrorResponse`. That annotation was never reachable at runtime
  (admin writes `ErrorResponse` to the wire; no reader path exists
  inside admin), so it was inert. Removed to reduce noise.

### Unchanged (scope discipline)

- **Strict mode preserved** on admin-owned schemas: `WebhookSubscription`,
  `Tenant`, `Budget`, `Policy`, `ApiKey`, every `EventData*` subtype,
  every `Bulk*Request` / `Bulk*Filter`, every `*CreateRequest` /
  `*UpdateRequest`. Admin writes these; a typo there is an
  admin-internal bug and must fail loudly. The lenient tolerance is
  scoped to schemas runtime writes.

### Internal

- No wire contract change — this is a read-side tolerance adjustment
  in admin only.
- Added two test cases (`event_tolerantOfUnknownFieldAddedByRuntime`,
  `webhookDelivery_tolerantOfUnknownFieldAddedByRuntime`) to pin the
  invariant. A future regression — someone re-adding
  `ignoreUnknown = false` — would fail these tests.

## [0.1.25.31] — 2026-04-18

### Added

- **W3C Trace Context cross-surface correlation** — server impl of
  spec v0.1.25.28 (cycles-protocol PRs #56 + #58). Adds a third
  correlation tier on top of the existing `request_id` /
  `correlation_id`, spanning an HTTP request, every event it emits,
  the audit entry it produces, and any outbound webhook delivery. The
  v0.1.25.28 spec patch adds `trace_id` / `trace_flags` /
  `traceparent_inbound_valid` to the `WebhookDelivery` schema so this
  server's webhook-delivery payloads conform cleanly against the
  strict admin spec.

  **`trace_id`** (optional, `^[0-9a-f]{32}$`) on response bodies:

  - `ErrorResponse.trace_id` — populated on every error response.
  - `AuditLogEntry.trace_id` — populated on every audit entry written
    for an HTTP-originated operation.
  - `Event.trace_id` — populated on every event emitted inside a
    servlet request (auto-propagated from `RequestContextHolder`, so
    none of the 13 existing `emit(...)` call sites changed signature).

  **`X-Cycles-Trace-Id` response header** — emitted on every response
  (2xx, 4xx, 5xx). Clients ignore unknown response headers per HTTP
  contract so this is non-breaking.

  **Inbound precedence** on `traceparent` → `X-Cycles-Trace-Id` →
  server-generate. Malformed inbound correlation headers are tolerated
  (fall through to next rule); the server never rejects a request on a
  bad correlation header. Valid W3C `traceparent` trace-flags are
  captured for outbound preservation on webhook delivery.

  **New query params** on `GET /v1/admin/audit/logs` and
  `GET /v1/admin/events`:

  | Param | Type | Purpose |
  |---|---|---|
  | `trace_id` | 32-hex string | Exact-match JOIN on W3C trace id. |
  | `request_id` | string | Exact-match JOIN on the per-HTTP-request id. |

  Post-hydration predicate, null-safe — entries with null field values
  (historical writes, off-request emissions, internal sweeper work)
  cannot satisfy a supplied filter value.

  **Outbound webhook delivery** — `WebhookDelivery` now persists
  `trace_id` + `trace_flags` + `traceparent_inbound_valid` so the
  separate `cycles-server-events` sidecar can construct an outbound
  `traceparent` header preserving inbound trace-flags when the inbound
  `traceparent` was valid, defaulting to `01` (sampled) otherwise.

  **Spec:** `cycles-governance-admin-v0.1.25.yaml` info.version bumps
  to `0.1.25.27`. Fully additive — no field removals, no type changes,
  no required-field additions. Historical entries without `trace_id`
  continue to round-trip through strict Jackson.

## [0.1.25.30] — 2026-04-18

### Changed

- **Bulk-action audit metadata enrichment.** The single
  `AuditLogEntry` emitted per bulk-action invocation
  (`bulkActionTenants`, `bulkActionWebhooks`, `bulkActionBudgets`)
  now carries the full per-row outcome arrays plus filter echo plus
  wall-clock duration. Additive change only — existing metadata keys
  are unchanged. Spec `cycles-governance-admin-v0.1.25.yaml`
  info.version stays at `0.1.25.26`; `AuditLogEntry.metadata` is
  already typed `object` with `additionalProperties: true` so key
  additions are spec-compatible with no bump.

  New keys on `GET /v1/admin/audit/logs` entries with
  `operation ∈ { bulkActionTenants, bulkActionWebhooks, bulkActionBudgets }`:

  | Key | Type | Purpose |
  |---|---|---|
  | `succeeded_ids` | `string[]` | Per-row ids of successful operations — paper trail. |
  | `failed_rows` | `BulkActionRowOutcome[]` | Full `id + error_code + message` per failure. |
  | `skipped_rows` | `BulkActionRowOutcome[]` | Full `id + reason` per skip — distinguishes `ALREADY_IN_TARGET_STATE` from `ALREADY_DELETED`. |
  | `filter` | object | Normalized filter echoed as-is — reconstructs operator intent from audit alone. |
  | `duration_ms` | int64 | Handler-entry → audit-emit wall-clock for SLO triage. |

  Key order is preserved by server-side `LinkedHashMap`: `action`,
  `total_matched`, `succeeded`, `failed`, `skipped`, `succeeded_ids`,
  `failed_rows`, `skipped_rows`, `filter`, `idempotency_key`,
  `duration_ms`. Dashboards rendering the map in field-insertion
  order (JSON object iteration in most runtimes) stay stable.

- **Triage workflow.** Before 0.1.25.30, triaging a failed bulk-op
  required the operator's own copy of the synchronous response
  envelope or re-running the op (unacceptable for destructive
  actions like DELETE / DEBIT). Now the audit log entry alone is
  sufficient: ops picks the entry by `operation` + `tenant_id` +
  time window, reads `failed_rows[].error_code` and `message` to
  classify, and narrows the filter for a targeted re-run.

### Migration notes

- **Worst-case audit row size ~40 KB.** 500-row bulk-action cap ×
  ~80 B per outcome + filter echo + fixed keys. Within Redis
  value-size comfort range; no new limits required. Audit tooling
  that caps on entry-level JSON size should review. No effect on
  tenant-scoped ops (no bulk-action endpoints on the tenant plane).
- **Dashboards parsing bulk-action audit entries** gain five fields
  they may or may not display — fully backward compatible for
  consumers that ignore unknown keys.

### Unchanged

- Response shape and HTTP semantics of all three bulk-action
  endpoints. This release only enriches what persists to the audit
  index; the synchronous envelope returned to callers is byte-
  identical to 0.1.25.29.
- Auth model, idempotency semantics, safety gates.

## [0.1.25.29] — 2026-04-18

### Added

- **Budget bulk-action endpoint.** Closes
  [cycles-server-admin#99](https://github.com/runcycles/cycles-server-admin/issues/99)
  ("Bulk Budget Reset at Tenant or Parent-Scope Level"). Aligns
  with spec `cycles-governance-admin-v0.1.25.yaml` info.version
  `0.1.25.26`. Operators rolling over a billing period previously
  had to iterate `listBudgets` + per-row `fundBudget` — painful for
  a tenant with dozens/hundreds of ledgers and no atomic count-gate
  to catch drift between preview and apply.
- `POST /v1/admin/budgets/bulk-action` — AdminKeyAuth only (tenants
  cannot bulk-mutate their own budgets; per-budget `fundBudget`
  remains available). Five actions reusing `FundingOperation`:
  `CREDIT`, `DEBIT`, `RESET`, `REPAY_DEBT`, `RESET_SPENT`. Request:
  `{ filter, action, amount?, spent?, reason?, expected_count?,
  idempotency_key }` — `amount` required for all 5 actions; `spent`
  honored only on `RESET_SPENT`. Response mirrors the
  tenant/webhook bulk-action envelope: `{ action, total_matched,
  succeeded[], failed[], skipped[], idempotency_key }`.
- **Filter mirrors `listBudgets`.** `filter.tenant_id` REQUIRED
  (cross-tenant bulk explicitly out of scope per spec — 400 if
  blank). Optional: `scope_prefix`, `unit`, `status`, `over_limit`,
  `has_debt`, `utilization_min`, `utilization_max`, `search`.
  Zero-drift match semantics — the preview via `listBudgets` and
  the bulk apply use the same `BudgetListFilters` matcher, so
  row set parity is guaranteed.
- **Per-row classification.** `succeeded` rows carry the ledger
  scope as `id`. `failed` rows carry `error_code`:
  `BUDGET_EXCEEDED` (DEBIT would make remaining negative),
  `INVALID_TRANSITION` (unit mismatch / FROZEN / CLOSED),
  `NOT_FOUND` (ledger deleted between match and apply),
  `INTERNAL_ERROR`. `skipped` rows carry
  `reason=ALREADY_IN_TARGET_STATE` (currently only
  REPAY_DEBT on `debt==0`). Per-row failures never abort the batch.
- **Double-apply protection.** Per-row idempotency key derived as
  `{bulkKey}:{scope}:{unit}` is passed into
  `BudgetRepository.fund`, so the existing Lua fund-idempotency
  cache short-circuits any row whose prior run actually landed at
  the ledger. Retry-the-failed-set on a tighter filter cannot
  double-apply CREDIT / DEBIT / RESET / RESET_SPENT / REPAY_DEBT.
- **Safety gates** identical to tenant / webhook bulk-action
  (0.1.25.26): (1) empty filter rejected on the DTO boundary; (2)
  15-min idempotency replay returns cached envelope; (3) > 500
  matches → 400 `LIMIT_EXCEEDED` with `details.total_matched`; (4)
  `expected_count` mismatch → 409 `COUNT_MISMATCH` with
  `details.total_matched`.

### Unchanged

- Response shape, auth, and idempotency semantics for every
  pre-0.1.25.29 endpoint. No wire changes on existing surfaces.

## [0.1.25.28.1] — 2026-04-18

### Fixed

- **Nightly audit-soak invariant AS4 extended for the `__admin__`
  sentinel tier.** Test-only fix. v0.1.25.28 split the pre-auth
  `tenant_id` sentinel into `__unauth__` and `__admin__`, but
  `AuditFailureSoakIntegrationTest` still summed only
  `__unauth__ + tenant-soak` in its per-tier equality check —
  missing the admin-plane 4xx tier. Run
  [24599992000](https://github.com/runcycles/cycles-server-admin/actions/runs/24599992000)
  failed with `expected 14000, was 8923` (shortfall of exactly 5077,
  the 400-response count). Production audit write path was
  correct throughout; AS3 (`written + error + sampled-out ==
  total_requests`) and AS4 first invariant (`globalDelta ==
  written`) both passed on the failing run. No server / spec / data
  changes; single test file updated.

## [0.1.25.28] — 2026-04-17

### Changed

- **Audit `tenant_id` sentinel split.** Aligns with spec
  `cycles-governance-admin-v0.1.25.yaml` info.version `0.1.25.25`. The
  previous single `"<unauthenticated>"` sentinel conflated two very
  different request populations: pre-auth failures (missing / invalid /
  revoked key — potential DDoS noise) and platform-admin-authenticated
  requests that aren't scoped to any one tenant (governance ops, cross-
  tenant reads, admin-plane 4xx/5xx). This release splits them:
  - **`__admin__`** (new) — written for any request authenticated via
    `X-Admin-API-Key`. Rides the **authenticated-tier TTL**
    (`audit.retention.authenticated.days`, default 400d) and is
    **never sampled** regardless of `audit.sample.unauthenticated`.
    Admin-plane compliance signals now persist at full fidelity.
  - **`__unauth__`** (renamed from `<unauthenticated>`) — written for
    pre-auth failures only. Rides the short unauthenticated-tier TTL
    (default 30d) and is subject to the DDoS sampling gate.
  - **`<unauthenticated>`** (legacy) — no longer emitted. Historical
    rows written by v0.1.25.20..v0.1.25.27 still route to the
    unauthenticated-tier TTL so they age out on the correct schedule;
    no row silently flips to long retention just because the sentinel
    label changed.
- **URL-safe format.** Double-underscore delimiters replace angle
  brackets. Tenant grammar `^[a-z0-9-]+$` excludes underscores so no
  collision with a real tenant id is possible; underscores are RFC 3986
  unreserved, so `GET /v1/admin/audit/logs?tenant_id=__admin__` needs
  no percent encoding. The previous sentinel required URL-encoding
  (`%3Cunauthenticated%3E`) and produced cosmetically-broken log IDs
  and URLs.

### Migration notes for ops / dashboard tooling

- Auditor queries and dashboard filters hard-coded to
  `?tenant_id=<unauthenticated>` will stop matching fresh writes the
  moment 0.1.25.28 ships, but will still match historical rows that
  actually carry that literal. Migrate to:
  - `?tenant_id=__unauth__` — the pre-auth-failure slice only (same
    population the old sentinel matched, minus admin-plane entries).
  - `?tenant_id=__admin__` — the new platform-admin slice.
- Existing Redis rows are **not rewritten**. They age out naturally
  under the unauth-tier TTL. No operator action required for cutover.
- `audit.retention.authenticated.days` now governs retention of
  admin-plane failures in addition to tenant-authenticated entries.
  If you had tuned `audit.retention.unauthenticated.days` to something
  other than the default, review whether that still makes sense — it
  now applies only to pre-auth failures and legacy rows.

### Forward-compat client contract

- Adding a fourth sentinel in the future remains backwards-compatible
  — existing queries for `__admin__` / `__unauth__` continue to work;
  only new auditor flows need to learn about the new value. Sentinel
  names are stable per the cycles-protocol v0.1.25.25 CHANGELOG.

## [0.1.25.27] — 2026-04-17

### Added

- **Audit log filter DSL upgrade on `GET /v1/admin/audit/logs`.** Aligns
  with spec `cycles-governance-admin-v0.1.25.yaml` info.version
  `0.1.25.24`. Adds four new query parameters and promotes two
  existing parameters from scalar to array so ops auditors can slice
  the audit trail without client-side merging:
  - `error_code` (`array<string>`, maxItems 25, `explode=false`) —
    exact-or-IN-list on `AuditLogEntry.error_code`. Case-sensitive.
    NULL entry `error_code` (success rows) MUST NOT match. Unknown
    codes match nothing (forward-compat: newer clients sending a
    newly-added enum value MUST NOT cause a 400 against an older
    server).
  - `error_code_exclude` (`array<string>`, maxItems 25, `explode=false`)
    — NOT-IN-list. NULL entry `error_code` MUST always pass (hiding
    noisy codes MUST NOT silently hide successes). MAY combine with
    `error_code` (AND-composed: "narrow to set A, minus subset B").
  - `status_min`, `status_max` (integer, 100..599 inclusive) — range
    predicate on entry `status`. Mutex with exact `status` (server
    returns 400 `INVALID_REQUEST` on the combination). `status_min >
    status_max` returns 400. NULL entry `status` does not silently
    pass the range.
  - `operation`, `resource_type` — promoted from `string` to
    `array<string>` with `explode=false`. Formal wire contract is the
    `explode=false` comma-separated form (`?p=a,b`); a single scalar
    `?p=a` still parses as a one-element list (byte-identical
    back-compat). Spring's `@RequestParam List<String>` also accepts
    the repeated form `?p=a&p=b` as an implementation convenience —
    documented in the spec as a server `MAY`, clients MUST NOT rely
    on it.
- **`search` match set extended** on `listAuditLogs`. Previously
  matched `resource_id` OR `log_id`; now also matches `error_code` OR
  `operation` (case-insensitive substring, unchanged 128-char cap).
  Closes the free-text gap where `?search=BUDGET` missed
  `BUDGET_EXCEEDED` and `?search=createBudget` missed the
  operation field.

### Changed

- `AuditRepository.list(...)` gains five trailing parameters
  (`operations`, `resourceTypes`, `errorCodes`, `errorCodeExcludes`,
  `statusMin`, `statusMax`). Pre-0.1.25.27 shorter overloads are
  preserved as thin shims that delegate with trailing `null`s, so
  every pre-existing caller and test mock keeps working unchanged.
- Controller enforces the cross-parameter constraints OpenAPI can't
  express (mutex, bounds, `min <= max`, per-list-param maxItems 25)
  and returns 400 `INVALID_REQUEST` with a specific diagnostic
  message on violation.

### Unchanged

- Cursor-stability invariant (v0.1.25.25): all filter and search
  predicates applied **before** cursor commitment, so a second page
  with the same filter set returns the strict suffix of the first.
- Response shape, `has_more` / `next_cursor` semantics, 128-char
  `search` cap, and the existing time-indexed sort path. Callers
  omitting all new params see byte-identical wire output to
  v0.1.25.26.

## [0.1.25.26] — 2026-04-17

### Added

- **Filter-driven bulk lifecycle actions on tenants and webhooks.**
  Closes the remaining bullet of governance spec v0.1.25.21. Two
  new admin-only endpoints:
  - `POST /v1/admin/tenants/bulk-action` — actions `SUSPEND`,
    `REACTIVATE`, `CLOSE`; filter fields `status`,
    `parent_tenant_id`, `observe_mode`, `search`.
  - `POST /v1/admin/webhooks/bulk-action` — actions `PAUSE`,
    `RESUME`, `DELETE`; filter fields `tenant_id`, `status`,
    `event_type`, `search`.
  Request: `{ filter, action, expected_count?, idempotency_key }`.
  Response: `{ action, total_matched, succeeded[], failed[],
  skipped[], idempotency_key }` where each `BulkActionRowOutcome` is
  `{ id, error_code?, message?, reason? }`. Per-row failures never
  abort the batch — response is HTTP 200 with `succeeded.size +
  failed.size + skipped.size == total_matched`.
- **Four safety gates** on both endpoints: (1) empty filter → 400
  `INVALID_REQUEST`; (2) idempotency replay returns cached envelope
  (15-min TTL, keyed by `(endpoint, idempotency_key)`); (3) >500
  matches → 400 `LIMIT_EXCEEDED` with `details.total_matched`; (4)
  `expected_count` mismatch → 409 `COUNT_MISMATCH` with
  `details.total_matched` — preview→submit anti-footgun.
- **New shared `IdempotencyStore` primitive** for bulk-action.
  Redis-backed, corrupt / unparseable entries degrade to
  `Optional.empty()` (can't brick the endpoint), store-failure is
  logged not thrown (mutation already succeeded; losing the
  idempotency entry is degraded-experience, not a correctness bug).
  `BudgetController.fund` idempotency remains Lua-atomic and was
  intentionally NOT migrated onto the shared store — externalizing
  the idempotency check would split the atomicity with the balance
  mutation. See `IdempotencyStore.java` javadoc for the rationale.
- Spec lineage: paired with cycles-protocol PR #51 (spec v0.1.25.23)
  which adds `COUNT_MISMATCH` and `LIMIT_EXCEEDED` to the `ErrorCode`
  enum. The v0.1.25.21 prose already REQUIRED these codes; PR #51
  was the enum-consistency fix so response validators don't reject
  the spec-compliant server.

### Unchanged

- Response shape, auth model, and idempotency semantics for every
  pre-0.1.25.26 endpoint. No wire changes on existing surfaces.

## [0.1.25.25] — 2026-04-17

### Added

- **Free-text `search` on six admin list endpoints.** Closes the
  first bullet of governance spec v0.1.25.21. Optional `search`
  query parameter on `listTenants`, `listBudgets`, `listApiKeys`,
  `listAuditLogs`, `listWebhookSubscriptions`, `listEvents`.
  Case-insensitive substring match; `maxLength: 128`; empty string
  MUST be treated as absent; combines with every other filter using
  AND. Per-endpoint match fields (OR-combined within an endpoint):
  - `listTenants` — `tenant_id`, `name`
  - `listBudgets` — `tenant_id`, `scope`
  - `listApiKeys` — `key_id`, `name`
  - `listAuditLogs` — `resource_id`, `log_id`
  - `listWebhookSubscriptions` — `subscription_id`, `url`
  - `listEvents` — `correlation_id`, `scope`
  Closes the dashboard workflow gap where "find tenant matching
  'acme'" required client-side filtering over a truncated page-1
  slice of the full list (silent false negatives at scale).
- **Shared `SearchSpec` validator** (`cycles-admin-service-model`)
  locking the `trim → empty-check → length-check` ordering so
  trailing whitespace cannot bypass the 128-char cap.
- **Cursor-stability invariant** (watch-item #1): every repository
  applies the `search` predicate before cursor commitment. A second
  page with the same `search` value returns the strict suffix of
  the first. Asserted explicitly in `EventRepositoryTest` and
  `AuditRepositoryTest` cursor-walk tests.

### Changed

- Repository interfaces gain an optional `search` overload.
  Pre-0.1.25.25 signatures are preserved as thin shims delegating
  with `search = null`. Existing mocks and call sites unchanged.

### Unchanged

- Response shape, cursor chain, and default row order on all six
  endpoints. Callers omitting `search` see byte-identical output to
  v0.1.25.24.

## [0.1.25.24] — 2026-04-16

### Added

- **`sort_by` + `sort_dir` on six admin list endpoints.** Aligns with
  spec `cycles-governance-admin-v0.1.25.yaml` info.version `0.1.25.20`,
  which adds §V4 server-side sort to `listTenants`, `listApiKeys`,
  `listBudgets`, `listWebhookSubscriptions`, `listEvents`, and
  `listAuditLogs`. Per-endpoint whitelists (`sort_by` values) are
  enforced at the controller boundary; `sort_dir` ∈ {`asc`, `desc`}.
  Unknown `sort_by` or `sort_dir` → 400 `INVALID_REQUEST`.
- **Per-endpoint sort defaults:**
  - `listTenants`, `listApiKeys` → `created_at DESC` (matches pre-0.1.25.24 ordering)
  - `listBudgets` → `utilization DESC` (operator-ergonomics default; **changes default row order** vs. pre-0.1.25.24 — callers that relied on the prior `SMEMBERS` set-iteration order must pass `sort_by=created_at&sort_dir=desc` to restore it)
  - `listWebhookSubscriptions` → `consecutive_failures DESC` (operator-ergonomics default; **changes default row order** vs. pre-0.1.25.24 — same migration note as `listBudgets`)
  - `listEvents`, `listAuditLogs` → `timestamp DESC` (matches pre-0.1.25.24 ordering)
  - Absent `sort_dir` → DESC (spec default).
- **Total-order cursor under sort.** Each endpoint's comparator is
  null-safe on every whitelisted field with a primary-key tie-breaker
  (`tenant_id`, `key_id`, `ledger_id`, `whsub_id`, `event_id`,
  `log_id`), so cursor resume under any sort is deterministic.
- **Per-endpoint whitelists.** Spec v0.1.25.20 §V4 enums:
  - `listTenants` — `tenant_id`, `name`, `status`, `created_at`
  - `listApiKeys` — `key_id`, `name`, `tenant_id`, `status`, `created_at`, `expires_at`
  - `listBudgets` — `tenant_id`, `scope`, `unit`, `status`, `commit_overage_policy`, `utilization`, `debt`
  - `listWebhookSubscriptions` — `url`, `tenant_id`, `status`, `consecutive_failures`
  - `listEvents` — `event_type`, `category`, `scope`, `tenant_id`, `timestamp`
  - `listAuditLogs` — `timestamp`, `operation`, `resource_type`, `tenant_id`, `key_id`, `status`

### Changed

- Repository interfaces for the six affected endpoints gained an
  optional `SortSpec` overload. Pre-0.1.25.24 signatures are preserved
  as thin shims that delegate with `SortSpec = null`, so every
  pre-existing caller and test mock keeps working unchanged.
- Callers requesting non-default sort on time-indexed endpoints
  (`listEvents`, `listAuditLogs`) should narrow `from`/`to` when
  hitting very large windows — the server hydrates up to
  `SORTED_HYDRATE_CAP = 2000` IDs from the filter-matching set before
  applying the in-memory sort, so a broad time window with a
  non-timestamp sort will cap at 2000 rows per page after filtering.

### Migration note

For callers that pin to pre-0.1.25.24 default row order on `listBudgets`
or `listWebhookSubscriptions`, pass `sort_by=created_at&sort_dir=desc`
explicitly. The other four list endpoints (`listTenants`, `listApiKeys`,
`listEvents`, `listAuditLogs`) keep the pre-0.1.25.24 default row order
unchanged when `sort_by`/`sort_dir` are both omitted.

### Unchanged

- Response shape and cursor-chain semantics on all six endpoints —
  `has_more`, `next_cursor`, pagination over `limit` rows.
- No dashboard wire-up in this release — the `cycles-dashboard`
  `useSort` composable still sorts client-side; a follow-up PR against
  the dashboard repo will push sort specs to the server.

## [0.1.25.23] — 2026-04-16

### Added

- **`BudgetLedger.tenant_id` exposed on the wire.** Aligns with
  spec `cycles-governance-admin-v0.1.25.yaml` info.version
  `0.1.25.19`, which classifies `tenant_id` as an OPTIONAL response
  field on every `BudgetLedger` returned by the service. Prior to
  `0.1.25.23` the field existed on the Java model but was annotated
  `@JsonIgnore`, so cross-tenant list responses (introduced in
  `0.1.25.22`) returned rows with no wire-level tenant attribution —
  dashboards had to scope-string-parse to display per-row tenant
  context, which the spec explicitly does not guarantee.

### Wire format

- `BudgetLedger` now serializes `tenant_id` as snake_case JSON.
  `@JsonInclude(NON_NULL)`: when the backing value is null (legacy
  ledgers stored before tenant_id was tracked, edge-case paths), the
  field is omitted rather than emitted as `"tenant_id": null`. This
  matches the spec's OPTIONAL (not REQUIRED) classification.

### Notes for upgraders

- **Additive, non-breaking.** Clients that ignore unknown response
  fields observe no change; clients that consume `tenant_id` get
  per-row tenant attribution in both per-tenant and cross-tenant
  list responses without additional requests.
- Pre-v0.1.25.19 spec clients with `additionalProperties: false`
  validation would fail on the new field; upgrade the spec in
  lock-step with the server.

## [0.1.25.22] — 2026-04-16

### Added

- **Cross-tenant list for `GET /v1/admin/api-keys`.** `tenant_id` is
  now optional under AdminKeyAuth. Absent → walks every tenant in the
  global `tenants` set in sorted order; returns a composite cursor
  `{tenantId}|{keyId}` so follow-up pages resume inside the correct
  tenant boundary. Present → existing per-tenant path, unchanged
  (cursor stays `{keyId}`). ApiKeyAuth callers always resolve to
  their own tenant regardless of query string — no cross-tenant leak.
- **Cross-tenant list for `GET /v1/admin/budgets`.** Same shape:
  `tenant_id` optional under AdminKeyAuth; cross-tenant mode returns
  `{tenantId}|{ledgerId}`. Under ApiKeyAuth, scoped to the
  authenticated tenant.
- **Four new optional budget filter params** on `GET /v1/admin/budgets`:
  - `over_limit` (boolean) — include only ledgers with
    `is_over_limit == true`.
  - `has_debt` (boolean) — include only ledgers with `debt.amount > 0`.
  - `utilization_min` (double, `[0, 1]`) — lower bound on
    `spent / allocated`. `allocated == 0` treats utilization as 0.
  - `utilization_max` (double, `[0, 1]`) — upper bound on
    `spent / allocated`.
  Filters AND-combine. Applied **before** cursor traversal so
  pagination stays stable across filter changes.
  `utilization_min > utilization_max` → `400 INVALID_REQUEST`
  (cross-parameter rule OpenAPI cannot express in-schema).
- **Deleted-cursor-tenant handling** on both cross-tenant list paths.
  If the tenant named by the cursor was removed between pages, the
  walk skips forward to the first tenant whose id sorts strictly
  after the cursor tenant and serves it from the start. Previously
  the walk would stall at empty, implying end-of-data even when
  later tenants still had rows.

### Wire format

Additive. Existing per-tenant pagination (with `tenant_id` required
and cursor = `{keyId}` / `{ledgerId}`) is byte-identical to 0.1.25.21.
Only new callers that omit `tenant_id` or pass the new budget filter
params see the v0.1.25.22 surface.

### Notes for upgraders

- No action required if you already scope list calls with `tenant_id`.
- Dashboards that surface keys / budgets across many tenants should
  swap N+1 per-tenant loops for the single cross-tenant call — closes
  the fan-out storm at thousand-tenant scale.
- Callers must parse the cross-tenant cursor on the **first** `|`
  (tenant IDs never contain `|`; ledger / key IDs MAY). Treat the
  cursor as opaque and pass it back unchanged; do not split or
  normalize it in client code.
- Spec: governance `cycles-governance-admin-v0.1.25.yaml`
  `info.version` = `0.1.25.18`.

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

[0.1.25.47]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.47
[0.1.25.46]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.46
[0.1.25.45]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.45
[0.1.25.44]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.44
[0.1.25.43]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.43
[0.1.25.42]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.42
[0.1.25.41]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.41
[0.1.25.40]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.40
[0.1.25.39]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.39
[0.1.25.38]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.38
[0.1.25.37]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.37
[0.1.25.36]: https://github.com/runcycles/cycles-server-admin/releases/tag/v0.1.25.36
[0.1.25.35]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.35
[0.1.25.34]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.34
[0.1.25.33]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.33
[0.1.25.32]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.32
[0.1.25.31]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.31
[0.1.25.30]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.30
[0.1.25.29]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.29
[0.1.25.28]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.28
[0.1.25.27]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.27
[0.1.25.26]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.26
[0.1.25.25]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.25
[0.1.25.24]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.24
[0.1.25.23]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.23
[0.1.25.22]: https://github.com/runcycles/cycles-server-admin/releases/tag/0.1.25.22
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
