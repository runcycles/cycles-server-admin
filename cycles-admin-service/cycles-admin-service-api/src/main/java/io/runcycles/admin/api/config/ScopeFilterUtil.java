package io.runcycles.admin.api.config;

import io.runcycles.admin.data.exception.GovernanceException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Enforces API key scope_filter restrictions per the governance spec.
 * A scope_filter entry restricts the key to scopes containing a matching segment.
 * Example: scope_filter=["workspace:eng"] allows "tenant:acme/workspace:eng" but not "tenant:acme/workspace:sales".
 * Wildcard: "agent:*" matches any scope containing an "agent:" segment.
 */
public final class ScopeFilterUtil {

    private ScopeFilterUtil() {}

    /**
     * Validates that the given scope is permitted by the API key's scope_filter.
     * If scope_filter is null or empty, all scopes are allowed (no restriction).
     * If the scope is null or blank, the check is skipped (list queries with no scope param).
     *
     * @throws GovernanceException with FORBIDDEN if scope does not match any filter entry
     */
    @SuppressWarnings("unchecked")
    public static void enforceScopeFilter(HttpServletRequest request, String scope) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        Object attr = request.getAttribute("authenticated_scope_filter");
        if (attr == null) {
            return;
        }
        List<String> scopeFilter = (List<String>) attr;
        if (scopeFilter.isEmpty()) {
            return;
        }
        for (String filter : scopeFilter) {
            if (matchesScope(scope, filter)) {
                return;
            }
        }
        throw GovernanceException.scopeFilterDenied(scope);
    }

    /**
     * Checks if a scope path matches a filter entry using segment-based matching.
     * Scope paths look like "tenant:acme/workspace:eng/agent:bot1".
     * Filter "workspace:eng" matches if the scope contains that exact segment.
     * Filter "agent:*" matches if the scope contains any segment starting with "agent:".
     */
    static boolean matchesScope(String scopePath, String filter) {
        String[] segments = scopePath.split("/");
        if (filter.endsWith(":*")) {
            // Wildcard: "agent:*" matches any segment starting with "agent:"
            String prefix = filter.substring(0, filter.length() - 1); // "agent:"
            for (String segment : segments) {
                if (segment.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
        // Exact segment match
        for (String segment : segments) {
            if (segment.equals(filter)) {
                return true;
            }
        }
        return false;
    }
}
