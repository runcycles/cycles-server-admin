package io.runcycles.admin.api.exception;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import static io.runcycles.admin.api.logging.LogSanitizer.safe;
import io.runcycles.admin.api.service.AuditFailureService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerMapping;
import java.util.*;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditFailureService auditFailure;

    public GlobalExceptionHandler(AuditFailureService auditFailure) {
        this.auditFailure = auditFailure;
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request != null ? request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) : null;
        if (attr != null) {
            return safe(attr);
        }
        String generated = UUID.randomUUID().toString();
        if (request != null) {
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, generated);
        }
        return generated;
    }

    private String resolveTraceId(HttpServletRequest request) {
        Object attr = request != null ? request.getAttribute(TraceContextFilter.TRACE_ID_ATTRIBUTE) : null;
        return safe(attr);
    }

    private ErrorResponse.ErrorResponseBuilder errorBuilder(HttpServletRequest request) {
        return ErrorResponse.builder()
                .requestId(resolveRequestId(request))
                .traceId(resolveTraceId(request));
    }

    private String method(HttpServletRequest request) {
        return request != null ? request.getMethod() : null;
    }

    private String path(HttpServletRequest request) {
        return safe(request != null ? request.getRequestURI() : null);
    }

    private String route(HttpServletRequest request) {
        Object attr = request != null ? request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) : null;
        return safe(attr);
    }

    private void logRequestError(HttpServletRequest request, int status, ErrorCode error, String message) {
        LOG.warn("Admin request rejected: method={} path={} route={} status={} error={} request_id={} trace_id={} message={}",
                method(request), path(request), route(request), status, error, resolveRequestId(request),
                resolveTraceId(request), safe(message));
    }

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<ErrorResponse> handleGovernanceException(GovernanceException ex, HttpServletRequest request) {
        LOG.info("Governance exception handled: method={} path={} route={} status={} error={} request_id={} trace_id={} exception_class={} message={}",
                method(request), path(request), route(request), ex.getHttpStatus(), ex.getErrorCode(),
                resolveRequestId(request), resolveTraceId(request), ex.getClass().getName(), safe(ex.getMessage()));
        auditFailure.logFailure(request, ex.getHttpStatus(), ex.getErrorCode(), ex.getMessage(), null);
        return ResponseEntity.status(ex.getHttpStatus()).body(errorBuilder(request)
            .error(ex.getErrorCode()).message(ex.getMessage()).details(ex.getDetails()).build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", "));
        String fullMessage = "Validation failed: " + message;
        logRequestError(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, fullMessage);
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, fullMessage, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(errorBuilder(request).error(ErrorCode.INVALID_REQUEST).message(fullMessage).build());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
            .map(v -> {
                String path = v.getPropertyPath().toString();
                String param = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                return param + ": " + v.getMessage();
            })
            .collect(Collectors.joining(", "));
        String fullMessage = "Validation failed: " + message;
        logRequestError(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, fullMessage);
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, fullMessage, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(errorBuilder(request).error(ErrorCode.INVALID_REQUEST).message(fullMessage).build());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = "Missing required parameter: " + ex.getParameterName();
        logRequestError(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, message);
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, message, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(errorBuilder(request).error(ErrorCode.INVALID_REQUEST).message(message).build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue();
        logRequestError(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "'");
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, message, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(errorBuilder(request).error(ErrorCode.INVALID_REQUEST).message(message).build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        logRequestError(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, "Malformed request body");
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, "Malformed request body", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(errorBuilder(request).error(ErrorCode.INVALID_REQUEST).message("Malformed request body").build());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        String allowed = supported == null || supported.isEmpty()
                ? ""
                : supported.stream().map(HttpMethod::name).sorted().collect(Collectors.joining(", "));
        String message = "Request method '" + ex.getMethod() + "' is not supported"
                + (allowed.isEmpty() ? " for this endpoint" : "; supported methods: " + allowed);
        logRequestError(request, HttpStatus.METHOD_NOT_ALLOWED.value(), ErrorCode.INVALID_REQUEST, message);
        auditFailure.logFailure(request, HttpStatus.METHOD_NOT_ALLOWED.value(),
                ErrorCode.INVALID_REQUEST, message, null);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(HttpMethod[]::new));
        }
        return response.body(errorBuilder(request)
                .error(ErrorCode.INVALID_REQUEST)
                .message(message)
                .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        if (ex instanceof GovernanceException) {
            // Dispatch-order safety: if a GovernanceException ever reaches the
            // generic branch (Spring's handler-order behaviour corner cases),
            // defer to the dedicated handler — it already writes the audit
            // entry, so we must NOT double-write.
            return handleGovernanceException((GovernanceException) ex, request);
        }
        LOG.error("Unhandled admin exception: method={} path={} route={} status={} error={} request_id={} trace_id={} exception_class={}",
                method(request), path(request), route(request), HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCode.INTERNAL_ERROR, resolveRequestId(request), resolveTraceId(request),
                ex.getClass().getName(), ex);
        // Record metadata captures exception class for post-incident triage.
        // Message intentionally generic on the wire ("Internal error") — full
        // class and stack stay in the server logs, but the class name in the
        // audit entry lets ops correlate an audit row to the matching log line.
        Map<String, Object> extras = new HashMap<>();
        extras.put("exception_class", ex.getClass().getName());
        auditFailure.logFailure(request, HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCode.INTERNAL_ERROR, ex.getMessage(), extras);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(errorBuilder(request)
                .error(ErrorCode.INTERNAL_ERROR)
                .message("Internal error")
                .build());
    }
}
