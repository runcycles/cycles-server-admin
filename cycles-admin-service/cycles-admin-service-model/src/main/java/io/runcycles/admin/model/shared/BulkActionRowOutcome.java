package io.runcycles.admin.model.shared;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
/**
 * Per-row result in a bulk-action response envelope (spec v0.1.25.21).
 * The same shape is used for entries of succeeded / failed / skipped arrays
 * across both tenant and webhook bulk-action endpoints.
 *
 * <p>Field presence is array-specific:
 * <ul>
 *   <li>{@code succeeded[]} entries carry only {@code id}.</li>
 *   <li>{@code failed[]} entries carry {@code id}, {@code errorCode}, {@code message}.</li>
 *   <li>{@code skipped[]} entries carry {@code id}, {@code reason}.</li>
 * </ul>
 * {@link JsonInclude.Include#NON_NULL} keeps the wire shape clean.
 *
 * <p>Known {@code errorCode} values: INVALID_TRANSITION, NOT_FOUND,
 * PERMISSION_DENIED, INTERNAL_ERROR. Known {@code reason} values:
 * ALREADY_IN_TARGET_STATE, ALREADY_DELETED. Servers MAY introduce
 * additional codes; clients MUST treat unknown codes as INTERNAL_ERROR.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkActionRowOutcome {
    @JsonProperty("id") private String id;
    @JsonProperty("error_code") private String errorCode;
    @JsonProperty("message") private String message;
    @JsonProperty("reason") private String reason;
}
