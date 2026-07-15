# Contract Testing

Runtime validation of admin-server MockMvc responses against the authoritative OpenAPI spec.

## What it catches

Any 2xx response whose body drifts from the reviewed, commit-pinned `cycles-governance-admin-v0.1.25.yaml` — missing required fields, extra fields (when `additionalProperties: false`), type mismatches, enum violations, `minLength`/`maxLength`/`minimum`/`maximum` constraint violations. Applies to every controller whose test imports `ContractValidationConfig`.

## What it doesn't catch

- **Endpoints that don't exist.** If the server is missing a spec endpoint entirely, no test hits it, so nothing validates. Structural diff (`OpenApiContractDiffTest` — planned Phase 2) closes this.
- **4xx / 5xx responses.** Only 2xx is validated. Error shapes are deliberately out of scope — they're negative-path tests and the server's error paths are already covered by unit tests.
- **Request-side parameter constraints.** Ignored by design — `@Valid` enforces them in production, and several tests deliberately send out-of-range query params to verify server clamping.

## Enablement gate

**Enabled by default as of v0.1.25.11.** Every controller test that imports `ContractValidationConfig` runs under contract validation unless explicitly disabled.

Disable for offline / air-gapped dev (where the pinned `cycles-protocol` fetch would fail):

```bash
# System property
mvn verify -Dcontract.validation.enabled=false

# Environment variable
CONTRACT_VALIDATION_ENABLED=false mvn verify
```

When the gate is off, `ContractValidationConfig` becomes a no-op: no spec fetch, no matcher attached.

## How to opt a controller test in

One-line change:

```java
@WebMvcTest(FooController.class)
@Import({MetricsTestConfiguration.class, ContractValidationConfig.class})  // <-- add
class FooControllerTest {
    ...
}
```

That's it. No per-test-method changes — the validator auto-applies to every `mockMvc.perform(...)` via the `MockMvcBuilderCustomizer` Spring picks up from the imported config.

## Where the spec comes from

`ContractSpecLoader.loadSpec()` fetches the spec at cycles-protocol commit `469840bb2f41ce35650c89405ea12fc56e847c76` on first use and caches it under `target/contract/spec-<revision>.yaml` with a 1-hour TTL. Pinning makes PR results reproducible; use `-Dcontract.spec.url=...` while coordinating a spec upgrade, then advance the reviewed commit deliberately.

The nightly `contract-drift` job compares that reviewed revision's admin YAML
with `cycles-protocol/main`. It fails on upstream drift, forcing a deliberate
spec review and pin advance without making ordinary CI depend on a moving branch.

- **Local dev:** fetch once per hour. Fast iteration, light on network.
- **CI:** fresh workspace = cache miss = always fetch the reviewed revision. The pin is advanced deliberately when the protocol spec changes.
- **Air-gapped / override:** `-Dcontract.spec.url=file:///path/to/local.yaml`

Per-build cache lives under `target/`, cleaned by `mvn clean`.

## Debugging a failure

When a test fails with `OpenApiValidationException`, the exception body is a structured JSON list of `messages`. The important fields per message:

- `key` — rule name, e.g. `validation.response.body.schema.required`
- `message` — human-readable detail with the exact JSON path
- `context.requestPath`, `requestMethod`, `responseStatus` — where the drift happened
- `context.pointers.instance` — JSON Pointer into the response body

Example:

```json
{
  "key": "validation.response.body.schema.required",
  "message": "Object has missing required properties ([\"ledgers\"])",
  "context": {
    "requestPath": "/v1/balances",
    "responseStatus": 200,
    "requestMethod": "GET"
  }
}
```

## Two complementary layers

1. **Runtime validation** (`ContractValidationConfig` + `@Import` in each controller test). Wraps MockMvc. Catches "the server returned the wrong shape for this endpoint." Requires a test that exercises the endpoint. Skips infra paths (`/api-docs`, `/v3/api-docs`, `/swagger-ui`, `/actuator`) since they're not in the admin spec.
2. **Structural diff** (`OpenApiContractDiffTest`). Compares SpringDoc's `/v3/api-docs` output against the pinned spec at build time. Catches:
   - **Missing endpoints** — in spec but server doesn't implement.
   - **Extra endpoints** — server has but spec doesn't document.
   - **Breaking operation divergence** — endpoint in both, but signature (parameters, request body, response codes, security) incompatibly diverges.

Only `INCOMPATIBLE`-level operation differences fail the build. `COMPATIBLE` (non-breaking) and `METADATA` (description / summary text) diffs are ignored — SpringDoc's auto-generated OpenAPI doesn't match the hand-written spec at deep `$ref` / styling level, and forcing exact match would be noise. Deep response-body shape is the runtime validator's job anyway.

Together they cover both "wrong shape" (runtime) and "wrong surface" (structural). Individually each has meaningful blind spots.

## Dependencies

- `com.atlassian.oai:swagger-request-validator-mockmvc:2.44.9` — the validator. Known incompat with `swagger-request-validator-springmvc` on Spring Framework 6 (`HandlerInterceptorAdapter` removed) — we use the MockMvc variant instead, which wires via `MockMvcBuilderCustomizer.alwaysExpect()`.
- `org.openapitools.openapidiff:openapi-diff-core:2.1.7` — planned Phase 2 structural diff.

Both are test-scope in `cycles-admin-service-api/pom.xml`.

## History

- v0.1.25.10 — infrastructure landed (ContractSpecLoader + ContractValidationConfig), gate default OFF.
- v0.1.25.11 — gate default flipped ON after confirming zero drift against `cycles-protocol@main` across all 432 tests.
- v0.1.25.11.x follow-up — `OpenApiContractDiffTest` adds Phase 2 structural diff. Respects the same gate (skipped when OFF).
