package io.runcycles.admin.api.config;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Canonical scope validation per the Cycles Protocol.
 *
 * <p>Until v0.1.25.15 the server accepted any string as a budget/policy
 * scope -- including garbage like {@code workspace:eng} (no tenant prefix)
 * or {@code tenant:acme/florb:blerp} (nonsense kind). This lets operators
 * create canonically-invalid ledgers and policies that silently won't
 * match anything during enforcement, and pollutes the audit trail with
 * scopes that break downstream tooling.
 *
 * <p>Per {@code cycles-protocol-v0.yaml} SCOPE DERIVATION (NORMATIVE):
 * <blockquote>Canonical ordering is: tenant -> workspace -> app
 * -> workflow -> agent -> toolset.</blockquote>
 *
 * <p>Scope grammar enforced here:
 * <ul>
 *   <li>First segment MUST be {@code tenant:<id>}.</li>
 *   <li>Each subsequent segment MUST be {@code <kind>:<id>} where kind is
 *       drawn from the canonical set and appears in canonical order
 *       (strictly increasing index; no backwards or duplicate kinds).</li>
 *   <li>Each {@code <id>} is non-empty, at most 128 chars, and matches
 *       {@code [A-Za-z0-9._-]+}. That's broad enough for real-world
 *       identifiers (uuids, slugs, dotted-version strings) without letting
 *       URL-unsafe characters through.</li>
 * </ul>
 *
 * <p>Policy {@code scope_pattern} uses the same grammar with one addition:
 * {@code *} is allowed in place of the id (e.g. {@code tenant:acme/*}) and
 * also as a trailing catch-all segment (e.g. {@code tenant:acme/agent:*}).
 * Wildcards on the kind itself (e.g. a wildcard kind segment followed by
 * an id) are NOT accepted
 * because they lead to unbounded enforcement scope and weren't in any
 * existing test fixtures -- the spec examples only wildcard the id.
 */
public final class ScopeValidator {

    private ScopeValidator() {}

    // Canonical kinds in priority order. Budget / policy scopes that skip
    // levels are allowed (tenant:x/agent:a without a workspace), but they
    // MUST appear in this order.
    private static final List<String> CANONICAL_KINDS = List.of(
        "tenant", "workspace", "app", "workflow", "agent", "toolset"
    );

    // Ids must start AND end with an alphanumeric. Middle can contain
    // `.`, `_`, or `-`. Prevents leading/trailing punctuation like
    // `.acme`, `acme.`, `-foo`, `foo_` which are surprising in audit
    // output and rarely intentional. Single-char ids like `a` are fine
    // (the optional middle group handles that).
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?");
    private static final int MAX_ID_LEN = 128;

    /**
     * Validates a concrete scope (no wildcards) used on budgets.
     *
     * @throws GovernanceException with 400 INVALID_REQUEST and a specific
     *     message identifying which segment broke which rule.
     */
    public static void validateBudgetScope(String scope) {
        validate(scope, /*allowWildcards=*/false, "scope");
    }

    /**
     * Validates a policy scope pattern; wildcards are allowed in place of
     * ids per the spec examples ({@code tenant:acme/*},
     * {@code tenant:acme/agent:*}).
     */
    public static void validatePolicyScopePattern(String scopePattern) {
        validate(scopePattern, /*allowWildcards=*/true, "scope_pattern");
    }

    /**
     * Validates that the tenant id encoded in {@code tenant:<id>} matches
     * the tenant_id the caller supplied separately. Prevents a write that
     * creates a budget under one tenant but targets the wrong tenant's
     * scope -- a subtle bug that's easy to miss in manual curl testing.
     */
    public static void validateScopeMatchesTenant(String scope, String expectedTenantId) {
        String actual = extractTenantId(scope);
        if (actual != null && !actual.equals(expectedTenantId)) {
            throw new GovernanceException(
                ErrorCode.INVALID_REQUEST,
                "Scope tenant '" + actual + "' does not match request tenant_id '" + expectedTenantId + "'",
                400);
        }
    }

    /**
     * Extracts the tenant id from {@code tenant:<id>[/...]}. Returns null
     * if the scope doesn't start with {@code tenant:} -- callers should
     * have already run validate() first to catch that case; this helper
     * is only for the cross-field check.
     */
    public static String extractTenantId(String scope) {
        if (scope == null || !scope.startsWith("tenant:")) return null;
        int end = scope.indexOf('/');
        String rest = (end < 0) ? scope.substring("tenant:".length()) : scope.substring("tenant:".length(), end);
        return rest.isEmpty() ? null : rest;
    }

    // ─────────────────────────────────────────────────────────────

    private static void validate(String scope, boolean allowWildcards, String fieldName) {
        if (scope == null || scope.isBlank()) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                fieldName + " must not be blank", 400);
        }
        String[] segments = scope.split("/", -1);
        int lastKindIdx = -1;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    fieldName + " has an empty segment (leading, trailing, or consecutive '/')", 400);
            }
            // Spec shorthand: a bare terminal "*" means "all descendants of
            // the prior scope" (e.g. "tenant:acme/*" = every scope rooted
            // at acme). Only valid in policy scope_pattern, never in a
            // concrete budget scope, and must be terminal.
            if ("*".equals(segment)) {
                if (!allowWildcards) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        fieldName + " wildcards are not allowed in budget scopes (segment '*')", 400);
                }
                if (i != segments.length - 1) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        fieldName + " wildcard '*' must be the final segment", 400);
                }
                if (i == 0) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        fieldName + " must start with 'tenant:<id>' before any wildcard", 400);
                }
                // Bare-* terminal is valid; stop here — no kind/id to check.
                return;
            }
            int colon = segment.indexOf(':');
            if (colon <= 0 || colon == segment.length() - 1) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    fieldName + " segment '" + segment + "' must be of form '<kind>:<id>'", 400);
            }
            String kind = segment.substring(0, colon);
            String id = segment.substring(colon + 1);

            int kindIdx = CANONICAL_KINDS.indexOf(kind);
            if (kindIdx < 0) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    fieldName + " segment '" + segment + "' uses non-canonical kind '" + kind
                        + "'. Allowed: " + CANONICAL_KINDS, 400);
            }
            if (i == 0 && kindIdx != 0) {
                // First segment MUST be tenant. Not just any canonical kind --
                // every concrete scope is rooted at a tenant by protocol.
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    fieldName + " must start with 'tenant:<id>' (got '" + kind + ":...')", 400);
            }
            if (kindIdx <= lastKindIdx) {
                throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                    fieldName + " kind '" + kind + "' appears out of canonical order "
                        + "(canonical order is " + CANONICAL_KINDS + "; same kind may not repeat)", 400);
            }
            lastKindIdx = kindIdx;

            // Id validation -- wildcards only where allowed.
            if ("*".equals(id)) {
                if (!allowWildcards) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        fieldName + " wildcards are not allowed in budget scopes (segment '" + segment + "')", 400);
                }
                // Wildcards must be terminal -- anything after a wildcard
                // would never be reachable during match.
                if (i != segments.length - 1) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        fieldName + " wildcard '*' must be the final segment's id", 400);
                }
            } else {
                if (id.length() > MAX_ID_LEN) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        fieldName + " segment '" + segment + "' id exceeds " + MAX_ID_LEN + " characters", 400);
                }
                if (!ID_PATTERN.matcher(id).matches()) {
                    throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                        fieldName + " segment '" + segment + "' id contains disallowed characters "
                            + "(allowed: letters, digits, '.', '_', '-')", 400);
                }
            }
        }
    }
}
