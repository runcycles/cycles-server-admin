package io.runcycles.admin.api.config;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeValidatorTest {

    // ─── Budget scope: happy path ─────────────────────────────────

    @Test void bareTenantAccepted() {
        ScopeValidator.validateBudgetScope("tenant:acme");
    }

    @Test void tenantWorkspaceAccepted() {
        ScopeValidator.validateBudgetScope("tenant:acme/workspace:prod");
    }

    @Test void fullCanonicalChainAccepted() {
        ScopeValidator.validateBudgetScope(
            "tenant:acme/workspace:prod/app:checkout/workflow:order/agent:reviewer/toolset:llm");
    }

    @Test void skippedLevelsAllowed() {
        // workspace and app omitted — allowed as long as order is preserved.
        ScopeValidator.validateBudgetScope("tenant:acme/agent:reviewer");
    }

    @Test void idsWithDotsDashesUnderscoresAccepted() {
        ScopeValidator.validateBudgetScope(
            "tenant:acme-corp.v2/workspace:prod_01/agent:rev-bot.v3");
    }

    // ─── Budget scope: rejections ─────────────────────────────────

    @Test void missingTenantPrefixRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("workspace:eng"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("must start with 'tenant:<id>'");
    }

    @Test void nonCanonicalKindRejected() {
        // The exact bug the user reported: "agentic:codex" is a typo for
        // "agent:codex". Server was silently accepting it pre-v0.1.25.15.
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/agentic:codex"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("non-canonical kind 'agentic'");
    }

    @Test void garbageKindRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/florb:blerp"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("non-canonical kind 'florb'");
    }

    @Test void reverseOrderRejected() {
        // agent appears before workspace — violates canonical ordering.
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/agent:a/workspace:w"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("out of canonical order");
    }

    @Test void duplicateKindRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/agent:a/agent:b"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("out of canonical order");
    }

    @Test void emptyIdRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/agent:"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("'<kind>:<id>'");
    }

    @Test void missingColonRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/agent_reviewer"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("'<kind>:<id>'");
    }

    @Test void consecutiveSlashesRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme//agent:a"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("empty segment");
    }

    @Test void trailingSlashRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("empty segment");
    }

    @Test void nullRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope(null))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("must not be blank");
    }

    @Test void blankRejected() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("   "))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("must not be blank");
    }

    @Test void disallowedCharactersInIdRejected() {
        // Space is not in [A-Za-z0-9._-].
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/agent:bad id"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("disallowed characters");
    }

    @Test void wildcardsRejectedInBudgetScope() {
        // Budgets must be concrete; wildcards belong in policy patterns.
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/*"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("wildcards are not allowed");
    }

    @Test void allErrorsReturn400() {
        try {
            ScopeValidator.validateBudgetScope("workspace:eng");
        } catch (GovernanceException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e.getHttpStatus()).isEqualTo(400);
        }
    }

    // ─── Policy scope_pattern: wildcard accepted ──────────────────

    @Test void policyPatternAllDescendantsAccepted() {
        // The most common policy pattern from spec examples.
        ScopeValidator.validatePolicyScopePattern("tenant:acme/*");
    }

    @Test void policyPatternAgentWildcardAccepted() {
        ScopeValidator.validatePolicyScopePattern("tenant:acme/agent:*");
    }

    @Test void policyPatternBareTenantAccepted() {
        ScopeValidator.validatePolicyScopePattern("tenant:acme");
    }

    @Test void policyPatternMidChainWildcardRejected() {
        // Wildcard must be terminal — anything after it is unreachable
        // during the match walk.
        assertThatThrownBy(() ->
            ScopeValidator.validatePolicyScopePattern("tenant:acme/*/agent:a"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("final segment");
    }

    // ─── Cross-field: scope tenant vs request tenant_id ──────────

    @Test void scopeTenantMatchesRequestTenantAccepted() {
        ScopeValidator.validateScopeMatchesTenant("tenant:acme/workspace:prod", "acme");
    }

    @Test void scopeTenantMismatchRejected() {
        // The dashboard's admin-on-behalf-of flow could otherwise send
        // tenant_id=acme but scope=tenant:corp/... and silently file the
        // ledger under the wrong tenant from a bookkeeping perspective.
        assertThatThrownBy(() ->
            ScopeValidator.validateScopeMatchesTenant("tenant:corp/agent:a", "acme"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("does not match request tenant_id");
    }

    @Test void nonTenantScopeIsIgnoredByTenantCheck() {
        // validateScopeMatchesTenant is a cross-field guard; the
        // well-formedness check (validateBudgetScope) runs first and
        // would have already failed on a non-tenant-prefix scope. So
        // the tenant check should silently pass rather than NPE.
        ScopeValidator.validateScopeMatchesTenant("workspace:eng", "acme");
    }

    // ─── extractTenantId helper ───────────────────────────────────

    @Test void extractTenantIdFromBareTenant() {
        assertThat(ScopeValidator.extractTenantId("tenant:acme")).isEqualTo("acme");
    }

    @Test void extractTenantIdFromChain() {
        assertThat(ScopeValidator.extractTenantId("tenant:acme-corp/workspace:prod"))
            .isEqualTo("acme-corp");
    }

    @Test void extractTenantIdReturnsNullForNonTenant() {
        assertThat(ScopeValidator.extractTenantId("workspace:eng")).isNull();
        assertThat(ScopeValidator.extractTenantId(null)).isNull();
        assertThat(ScopeValidator.extractTenantId("")).isNull();
    }

    // ─── Post-review hardening (v0.1.25.15 pre-merge) ─────────────

    // Regression lock for the reviewer's CRITICAL claim that
    // "tenant:*/agent:foo would pass validation" — it should NOT.
    // The terminal-id-wildcard rule rejects id=="*" when it's not
    // the final segment.
    @Test void policyPatternTenantIdWildcardWithMoreSegmentsRejected() {
        assertThatThrownBy(() ->
            ScopeValidator.validatePolicyScopePattern("tenant:*/agent:foo"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("must be the final segment");
    }

    // Bare "tenant:*" as a complete policy pattern IS accepted today
    // (semantically: match all tenant-rooted scopes). Lock in behavior:
    // the grammar validator accepts it, but the admin-on-behalf-of
    // tenant-cross-check rejects it at the controller layer because
    // extractTenantId returns "*" and no tenant_id matches "*". This
    // prevents admin operators from creating system-wide policies via
    // /v1/admin/policies (they'd need a future system-policy endpoint).
    @Test void policyPatternBareTenantWildcardAcceptedByGrammar() {
        ScopeValidator.validatePolicyScopePattern("tenant:*");
    }
    @Test void policyPatternBareTenantWildcardRejectedByTenantCrossCheck() {
        assertThatThrownBy(() ->
            ScopeValidator.validateScopeMatchesTenant("tenant:*", "acme"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("does not match request tenant_id");
    }

    // The `kindIdx <= lastKindIdx` check must catch equality, not just
    // strict-less-than. Original duplicate test used agent:a/agent:b
    // (both at kindIdx 4) — this adds a mid-list kind to verify the
    // branch isn't accidentally strict-less-than somewhere.
    @Test void duplicateWorkspaceKindRejected() {
        assertThatThrownBy(() ->
            ScopeValidator.validateBudgetScope("tenant:acme/workspace:a/workspace:b"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("out of canonical order");
    }

    // Tightened regex: ids must start and end with alphanumeric.
    // Prevents leading-dot, trailing-dot, leading-dash, etc. — these
    // are syntactically noisy and never intentional in practice.
    @Test void leadingDotInIdRejected() {
        assertThatThrownBy(() ->
            ScopeValidator.validateBudgetScope("tenant:.acme"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("disallowed characters");
    }
    @Test void trailingDotInIdRejected() {
        assertThatThrownBy(() ->
            ScopeValidator.validateBudgetScope("tenant:acme."))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("disallowed characters");
    }
    @Test void leadingDashInIdRejected() {
        assertThatThrownBy(() ->
            ScopeValidator.validateBudgetScope("tenant:-acme"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("disallowed characters");
    }
    @Test void singleAlphanumericIdAccepted() {
        // Regression: the tightened regex must still accept single-char ids.
        ScopeValidator.validateBudgetScope("tenant:a");
    }
    @Test void mixedPunctuationMidIdAccepted() {
        ScopeValidator.validateBudgetScope("tenant:a.b_c-d.v2");
    }

    @Test void emptyTenantIdExtractsAsNull() {
        assertThat(ScopeValidator.extractTenantId("tenant:")).isNull();
        assertThat(ScopeValidator.extractTenantId("tenant:/workspace:prod")).isNull();
    }

    @Test void policyPatternCannotStartWithBareWildcard() {
        assertThatThrownBy(() -> ScopeValidator.validatePolicyScopePattern("*"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("must start with 'tenant:<id>'");
    }

    @Test void budgetScopeRejectsIdWildcardForm() {
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:acme/agent:*"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("wildcards are not allowed");
    }

    @Test void overlongIdRejected() {
        String overlong = "a".repeat(129);
        assertThatThrownBy(() -> ScopeValidator.validateBudgetScope("tenant:" + overlong))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("exceeds 128 characters");
    }
}
