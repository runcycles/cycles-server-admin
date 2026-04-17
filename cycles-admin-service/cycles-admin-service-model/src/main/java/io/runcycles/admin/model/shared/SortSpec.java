package io.runcycles.admin.model.shared;

import java.util.Set;

/**
 * Generic sort specification threaded from controllers to repositories
 * (spec v0.1.25.20). The field set permitted by each endpoint is
 * hard-coded in the controller — the record only carries the already-
 * validated pair so the repository layer never sees an un-vetted field.
 *
 * Null `field` means "use the endpoint's default sort"; null `direction`
 * means DESC (spec default).
 *
 * Helpers `validate` / `resolve` centralise the 400-on-bad-field and
 * default-filling logic so controllers stay thin. They return a new
 * SortSpec rather than mutating to keep the record immutable.
 */
public record SortSpec(String field, SortDirection direction) {

    public static SortSpec of(String field, SortDirection direction) {
        return new SortSpec(field, direction);
    }

    /**
     * Resolve unparsed query params into a validated SortSpec.
     *
     * @param rawField     raw sort_by query param (nullable)
     * @param rawDirection already-parsed SortDirection (nullable — caller
     *                     handles parse errors so we can emit a single
     *                     400 with a uniform message)
     * @param allowedFields per-endpoint whitelist — must be non-empty
     * @param defaultField default used when rawField is null/blank
     * @return validated SortSpec with defaults applied
     * @throws IllegalArgumentException if rawField is non-null and not
     *         in allowedFields. Controllers catch and map to a 400.
     */
    public static SortSpec resolve(String rawField, SortDirection rawDirection,
                                    Set<String> allowedFields, String defaultField) {
        String field = (rawField == null || rawField.isBlank()) ? defaultField : rawField;
        if (!allowedFields.contains(field)) {
            throw new IllegalArgumentException(
                "Invalid sort_by '" + rawField + "'; expected one of: " + allowedFields);
        }
        SortDirection dir = (rawDirection != null) ? rawDirection : SortDirection.DESC;
        return new SortSpec(field, dir);
    }

    public boolean isAscending() { return direction == SortDirection.ASC; }
}
