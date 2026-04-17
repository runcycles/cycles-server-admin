package io.runcycles.admin.model.shared;

/**
 * Free-text `search` query-param normalizer for admin list endpoints
 * (spec v0.1.25.21). The spec requires empty-string to be treated as
 * absent and longer than 128 characters to be rejected with HTTP 400.
 *
 * Validator order is locked to {@code trim → empty-check → length-check}
 * so that trailing whitespace cannot bypass the 128-char cap by inflating
 * the wire-level length.
 *
 * Controllers call {@link #resolve(String)} and map
 * {@link IllegalArgumentException} to a 400 {@code INVALID_REQUEST}.
 * Repositories receive a nullable {@code String}: null/blank means "no
 * search filter"; any non-null value is already trimmed and length-bounded.
 *
 * Per-endpoint match-field sets live in the repository layer — this
 * primitive only owns normalisation, not matching semantics.
 */
public final class SearchSpec {

    public static final int MAX_LENGTH = 128;

    private SearchSpec() {}

    /**
     * Normalise a raw {@code search} query-param value.
     *
     * @param raw raw request param; may be null
     * @return trimmed non-empty string, or null when the filter is absent
     * @throws IllegalArgumentException if the trimmed value exceeds
     *         {@value #MAX_LENGTH} characters
     */
    public static String resolve(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "search exceeds maxLength " + MAX_LENGTH
                + " (got " + trimmed.length() + ")");
        }
        return trimmed;
    }

    /**
     * Case-insensitive substring match. Any null input (needle or
     * haystack) returns false so callers can chain with OR across the
     * per-endpoint match-field set without null-guarding at every site.
     */
    public static boolean matches(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }
}
