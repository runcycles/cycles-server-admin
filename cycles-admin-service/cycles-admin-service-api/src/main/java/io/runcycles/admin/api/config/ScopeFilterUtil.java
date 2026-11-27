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
    public static void enforceScopeFilter(HttpServletRequest request, String scope) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        if (!allowedScopes(request).test(scope)) throw GovernanceException.scopeFilterDenied(scope);
    }

    @SuppressWarnings("unchecked")
    public static java.util.function.Predicate<String> allowedScopes(HttpServletRequest request) {
        Object attr = request.getAttribute("authenticated_scope_filter");
        if (attr == null) return scope -> true;
        List<String> filters = (List<String>) attr;
        return scope -> filters.isEmpty() || (scope != null && filters.stream()
            .filter(filter -> filter != null && !filter.isBlank())
            .anyMatch(filter -> matchesScope(scope, filter)));
    }

    /**
     * Checks if a scope path matches a filter entry using segment-based matching.
     * Scope paths look like "tenant:acme/workspace:eng/agent:bot1".
     * Filter "workspace:eng" matches if the scope contains that exact segment.
     * Filter "agent:*" matches if the scope contains any segment starting with "agent:".
     */
    static boolean matchesScope(String scopePath, String filter) {
        String[] segments = scopePath.split("/");
        // Wildcard filters must be "key:*" format (e.g. "agent:*"), not bare "*"
        if (filter.endsWith(":*") && filter.length() > 2) {
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
