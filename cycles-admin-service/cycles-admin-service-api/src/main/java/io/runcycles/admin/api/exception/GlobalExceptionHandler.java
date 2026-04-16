package io.runcycles.admin.api.exception;

import io.runcycles.admin.api.filter.RequestIdFilter;
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
        return attr != null ? attr.toString() : UUID.randomUUID().toString();
    }

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<ErrorResponse> handleGovernanceException(GovernanceException ex, HttpServletRequest request) {
        LOG.info("Landed in governance exception handler: clazz={}", ex.getClass());
        auditFailure.logFailure(request, ex.getHttpStatus(), ex.getErrorCode(), ex.getMessage(), null);
        return ResponseEntity.status(ex.getHttpStatus()).body(ErrorResponse.builder()
            .error(ex.getErrorCode()).message(ex.getMessage()).requestId(resolveRequestId(request)).details(ex.getDetails()).build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", "));
        String fullMessage = "Validation failed: " + message;
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, fullMessage, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.builder().error(ErrorCode.INVALID_REQUEST).message(fullMessage).requestId(resolveRequestId(request)).build());
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
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, fullMessage, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.builder().error(ErrorCode.INVALID_REQUEST).message(fullMessage).requestId(resolveRequestId(request)).build());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = "Missing required parameter: " + ex.getParameterName();
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, message, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.builder().error(ErrorCode.INVALID_REQUEST).message(message).requestId(resolveRequestId(request)).build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue();
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, message, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.builder().error(ErrorCode.INVALID_REQUEST).message(message).requestId(resolveRequestId(request)).build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        auditFailure.logFailure(request, HttpStatus.BAD_REQUEST.value(), ErrorCode.INVALID_REQUEST, "Malformed request body", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.builder().error(ErrorCode.INVALID_REQUEST).message("Malformed request body").requestId(resolveRequestId(request)).build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        LOG.error("Unhandled exception: clazz={}", ex.getClass(), ex);
        if (ex instanceof GovernanceException) {
            // Dispatch-order safety: if a GovernanceException ever reaches the
            // generic branch (Spring's handler-order behaviour corner cases),
            // defer to the dedicated handler — it already writes the audit
            // entry, so we must NOT double-write.
            return handleGovernanceException((GovernanceException) ex, request);
        }
        // Record metadata captures exception class for post-incident triage.
        // Message intentionally generic on the wire ("Internal error") — full
        // class and stack stay in the server logs, but the class name in the
        // audit entry lets ops correlate an audit row to the matching log line.
        Map<String, Object> extras = new HashMap<>();
        extras.put("exception_class", ex.getClass().getName());
        auditFailure.logFailure(request, HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCode.INTERNAL_ERROR, ex.getMessage(), extras);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.builder()
                .error(ErrorCode.INTERNAL_ERROR)
                .message("Internal error")
                .requestId(resolveRequestId(request))
                .build());
    }
}
