# Contract Testing

Runtime validation of admin-server MockMvc responses against the authoritative OpenAPI spec.

## What it catches

Any 2xx response whose body drifts from `cycles-governance-admin-v0.1.25.yaml` on `cycles-protocol@main` — missing required fields, extra fields (when `additionalProperties: false`), type mismatches, enum violations, `minLength`/`maxLength`/`minimum`/`maximum` constraint violations. Applies to every controller whose test imports `ContractValidationConfig`.

## What it doesn't catch

- **Endpoints that don't exist.** If the server is missing a spec endpoint entirely, no test hits it, so nothing validates. Structural diff (`OpenApiContractDiffTest` — planned Phase 2) closes this.
- **4xx / 5xx responses.** Only 2xx is validated. Error shapes are deliberately out of scope — they're negative-path tests and the server's error paths are already covered by unit tests.
- **Request-side parameter constraints.** Ignored by design — `@Valid` enforces them in production, and several tests deliberately send out-of-range query params to verify server clamping.

## Enablement gate

Disabled by default. Enable via one of:

```bash
# System property (preferred in Maven commands)
mvn verify -Dcontract.validation.enabled=true

# Environment variable (preferred in CI)
CONTRACT_VALIDATION_ENABLED=true mvn verify
```

When the gate is off, `ContractValidationConfig` becomes a no-op: no spec fetch, no matcher attached. Tests run exactly as they did before the infrastructure landed.

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

`ContractSpecLoader.loadSpec()` fetches `https://raw.githubusercontent.com/runcycles/cycles-protocol/main/cycles-governance-admin-v0.1.25.yaml` on first use, caches to `target/contract/spec.yaml` with a 1-hour TTL.

- **Local dev:** fetch once per hour. Fast iteration, light on network.
- **CI:** fresh workspace = cache miss = always fetch. Catches cross-repo drift on every build.
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

## Why two distinct validation layers are planned

1. **Runtime validation (this doc).** Wraps MockMvc. Catches "the server returned the wrong shape for this endpoint." Requires a test that exercises the endpoint.
2. **Structural diff (Phase 2, not yet built).** Compares SpringDoc's `/api-docs` output against the pinned spec at build time. Catches "the server doesn't declare endpoint X at all." Doesn't need a test to exist.

Together they cover both "wrong shape" and "wrong surface". Individually each has meaningful blind spots.

## Dependencies

- `com.atlassian.oai:swagger-request-validator-mockmvc:2.44.9` — the validator. Known incompat with `swagger-request-validator-springmvc` on Spring Framework 6 (`HandlerInterceptorAdapter` removed) — we use the MockMvc variant instead, which wires via `MockMvcBuilderCustomizer.alwaysExpect()`.
- `org.openapitools.openapidiff:openapi-diff-core:2.1.7` — planned Phase 2 structural diff.

Both are test-scope in `cycles-admin-service-api/pom.xml`.

## Turning it on permanently

When `cycles-protocol@main` and the admin server are both confirmed drift-free:

1. In `ContractValidationConfig.validationEnabled()`, swap the default from `false` to `true`.
2. Add a `-Dcontract.validation.enabled=false` escape hatch documentation for anyone needing to bypass locally (e.g. offline).
3. Bump the server patch version, record in AUDIT.md.

That's PR-E.
