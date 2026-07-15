package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.model.audit.AuditLogListResponse;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.SearchSpec;
import io.runcycles.admin.model.shared.SortDirection;
import io.runcycles.admin.model.shared.SortSpec;
import io.runcycles.admin.api.support.PageSlice;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
@RestController @RequestMapping("/v1/admin/audit") @Tag(name = "Audit")
public class AuditController {
    // Per spec v0.1.25.20. timestamp is default — auditors expect
    // newest-first by default, matching the existing cursor chain.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "timestamp", "operation", "resource_type", "tenant_id", "key_id", "status");
    private static final String DEFAULT_SORT_FIELD = "timestamp";

    // Spec v0.1.25.24: per-list-param cap. Wider than the current ErrorCode
    // enum (23 values) with headroom; tight enough to keep request bodies
    // bounded and the O(n) contains() in the matcher cheap.
    private static final int MAX_LIST_PARAM_VALUES = 25;

    // Spec v0.1.25.24: HTTP status range bounds for status_min / status_max.
    private static final int MIN_HTTP_STATUS = 100;
    private static final int MAX_HTTP_STATUS = 599;

    @Autowired private AuditRepository repository;
    @GetMapping("/logs") @Operation(operationId = "listAuditLogs")
    public ResponseEntity<AuditLogListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String key_id,
            @RequestParam(required = false) List<String> operation,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) List<String> resource_type,
            @RequestParam(required = false) String resource_id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_dir,
            @RequestParam(required = false) List<String> error_code,
            @RequestParam(required = false) List<String> error_code_exclude,
            @Parameter(schema = @Schema(type = "integer", minimum = "100", maximum = "599"))
            @RequestParam(required = false) Integer status_min,
            @Parameter(schema = @Schema(type = "integer", minimum = "100", maximum = "599"))
            @RequestParam(required = false) Integer status_max,
            @Parameter(description = "Filter by W3C trace id (32 lowercase hex chars)",
                schema = @Schema(pattern = "^[0-9a-f]{32}$"))
            @RequestParam(required = false) String trace_id,
            @RequestParam(required = false) String request_id) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        SortSpec sortSpec = parseSortSpec(sort_by, sort_dir);
        String searchNorm = parseSearch(search);

        // Spec v0.1.25.24 validation. OpenAPI can't express the cross-param
        // constraints (status vs status_min/max mutex, status_min <= status_max),
        // so enforce at the controller edge and fail fast with INVALID_REQUEST.
        if (status != null && (status_min != null || status_max != null)) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "status cannot be combined with status_min or status_max", 400);
        }
        if (status_min != null && (status_min < MIN_HTTP_STATUS || status_min > MAX_HTTP_STATUS)) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "status_min must be in [" + MIN_HTTP_STATUS + ", " + MAX_HTTP_STATUS + "]", 400);
        }
        if (status_max != null && (status_max < MIN_HTTP_STATUS || status_max > MAX_HTTP_STATUS)) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "status_max must be in [" + MIN_HTTP_STATUS + ", " + MAX_HTTP_STATUS + "]", 400);
        }
        if (status_min != null && status_max != null && status_min > status_max) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST,
                "status_min must be <= status_max", 400);
        }

        List<String> operations;
        List<String> resourceTypes;
        List<String> errorCodes;
        List<String> errorCodeExcludes;
        try {
            operations = parseCodeList(operation, "operation");
            resourceTypes = parseCodeList(resource_type, "resource_type");
            errorCodes = parseCodeList(error_code, "error_code");
            errorCodeExcludes = parseCodeList(error_code_exclude, "error_code_exclude");
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }

        var logs = repository.list(tenant_id, key_id, operations, status, resourceTypes, resource_id,
            from, to, cursor, effectiveLimit + 1, sortSpec, searchNorm,
            errorCodes, errorCodeExcludes, status_min, status_max, trace_id, request_id);
        var page = PageSlice.from(logs, effectiveLimit);
        logs = page.items();
        AuditLogListResponse response = AuditLogListResponse.builder()
            .logs(logs)
            .hasMore(page.hasMore())
            .nextCursor(page.hasMore() ? logs.get(logs.size() - 1).getLogId() : null)
            .build();
        return ResponseEntity.ok(response);
    }

    /**
     * Parse sort_by / sort_dir query params into a validated SortSpec.
     * See TenantController.parseSortSpec for the shared rationale.
     */
    private SortSpec parseSortSpec(String sortBy, String sortDir) {
        SortDirection direction;
        try {
            direction = SortDirection.fromWire(sortDir);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
        try {
            return SortSpec.resolve(sortBy, direction, ALLOWED_SORT_FIELDS, DEFAULT_SORT_FIELD);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
    }

    private String parseSearch(String raw) {
        try {
            return SearchSpec.resolve(raw);
        } catch (IllegalArgumentException e) {
            throw new GovernanceException(ErrorCode.INVALID_REQUEST, e.getMessage(), 400);
        }
    }

    /**
     * Normalise an IN-list query param (spec v0.1.25.24). Spring binds
     * {@code List<String>} from both comma-separated ({@code ?p=a,b})
     * and repeated ({@code ?p=a&p=b}) forms; this helper collapses either
     * shape into a trimmed, deduplicated, size-capped list.
     *
     * <p>Steps: flatten each element by splitting on comma → trim →
     * drop empties → dedupe (LinkedHashSet preserves first-seen order) →
     * cap at {@link #MAX_LIST_PARAM_VALUES}.
     *
     * <p>Values are NOT validated against any enum — unknown codes match
     * nothing at the filter layer, which is the forward-compat contract
     * for cross-version clients (a newer client sending a newly-added
     * ErrorCode value won't 400 against an older server).
     *
     * @return normalised list, or {@code null} if the input is null/empty
     *         after normalisation (repository treats null as "no filter")
     * @throws IllegalArgumentException if the normalised list exceeds the
     *         per-param cap
     */
    private static List<String> parseCodeList(List<String> raw, String paramName) {
        if (raw == null || raw.isEmpty()) return null;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String element : raw) {
            if (element == null) continue;
            for (String piece : element.split(",")) {
                String trimmed = piece.trim();
                if (!trimmed.isEmpty()) {
                    seen.add(trimmed);
                }
            }
        }
        if (seen.isEmpty()) return null;
        if (seen.size() > MAX_LIST_PARAM_VALUES) {
            throw new IllegalArgumentException(
                paramName + " exceeds maxItems " + MAX_LIST_PARAM_VALUES
                + " (got " + seen.size() + ")");
        }
        return new ArrayList<>(seen);
    }
}
