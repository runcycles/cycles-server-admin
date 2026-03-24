package io.runcycles.admin.api.config;

import io.runcycles.admin.data.exception.GovernanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScopeFilterUtil")
class ScopeFilterUtilTest {

    // --- matchesScope ---

    @Test
    void matchesScope_exactSegmentMatch() {
        assertThat(ScopeFilterUtil.matchesScope("tenant:acme/workspace:eng", "workspace:eng")).isTrue();
    }

    @Test
    void matchesScope_noMatch() {
        assertThat(ScopeFilterUtil.matchesScope("tenant:acme/workspace:sales", "workspace:eng")).isFalse();
    }

    @Test
    void matchesScope_wildcardMatchesAnyValue() {
        assertThat(ScopeFilterUtil.matchesScope("tenant:acme/agent:bot1", "agent:*")).isTrue();
    }

    @Test
    void matchesScope_wildcardNoMatch() {
        assertThat(ScopeFilterUtil.matchesScope("tenant:acme/workspace:eng", "agent:*")).isFalse();
    }

    @Test
    void matchesScope_firstSegment() {
        assertThat(ScopeFilterUtil.matchesScope("tenant:acme", "tenant:acme")).isTrue();
    }

    @Test
    void matchesScope_partialValueNoMatch() {
        assertThat(ScopeFilterUtil.matchesScope("tenant:acme/workspace:engineering", "workspace:eng")).isFalse();
    }

    // --- enforceScopeFilter ---

    @Test
    void enforceScopeFilter_nullScopeFilter_allows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // No scope_filter attribute set
        ScopeFilterUtil.enforceScopeFilter(request, "tenant:acme/workspace:eng");
        // Should not throw
    }

    @Test
    void enforceScopeFilter_emptyScopeFilter_allows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticated_scope_filter", List.of());
        ScopeFilterUtil.enforceScopeFilter(request, "tenant:acme/workspace:eng");
        // Should not throw
    }

    @Test
    void enforceScopeFilter_nullScope_skips() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticated_scope_filter", List.of("workspace:eng"));
        ScopeFilterUtil.enforceScopeFilter(request, null);
        // Should not throw
    }

    @Test
    void enforceScopeFilter_blankScope_skips() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticated_scope_filter", List.of("workspace:eng"));
        ScopeFilterUtil.enforceScopeFilter(request, "");
        // Should not throw
    }

    @Test
    void enforceScopeFilter_matchingScope_allows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticated_scope_filter", List.of("workspace:eng"));
        ScopeFilterUtil.enforceScopeFilter(request, "tenant:acme/workspace:eng");
        // Should not throw
    }

    @Test
    void enforceScopeFilter_nonMatchingScope_throws403() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticated_scope_filter", List.of("workspace:eng"));

        assertThatThrownBy(() ->
                ScopeFilterUtil.enforceScopeFilter(request, "tenant:acme/workspace:sales"))
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    @Test
    void enforceScopeFilter_multipleFilters_anyMatch_allows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticated_scope_filter", List.of("workspace:eng", "workspace:sales"));
        ScopeFilterUtil.enforceScopeFilter(request, "tenant:acme/workspace:sales");
        // Should not throw — matches second filter
    }

    @Test
    void enforceScopeFilter_wildcardFilter_allows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticated_scope_filter", List.of("agent:*"));
        ScopeFilterUtil.enforceScopeFilter(request, "tenant:acme/workspace:eng/agent:bot1");
        // Should not throw — matches wildcard
    }
}
