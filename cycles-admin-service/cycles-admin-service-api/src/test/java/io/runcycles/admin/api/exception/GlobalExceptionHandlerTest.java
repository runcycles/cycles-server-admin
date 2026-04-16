package io.runcycles.admin.api.exception;

import io.runcycles.admin.api.service.AuditFailureService;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest mockRequest;
    private AuditFailureService auditFailure;

    @BeforeEach
    void setUp() {
        auditFailure = mock(AuditFailureService.class);
        handler = new GlobalExceptionHandler(auditFailure);
        mockRequest = mock(HttpServletRequest.class);
    }

    @Test
    void handleGovernanceException_returnsCorrectStatusAndBody() {
        GovernanceException ex = GovernanceException.tenantNotFound("tenant-1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
        assertThat(response.getBody().getMessage()).contains("tenant-1");
        assertThat(response.getBody().getRequestId()).isNotNull();
    }

    @Test
    void handleGovernanceException_withDetails_includesDetails() {
        GovernanceException ex = new GovernanceException(
                ErrorCode.BUDGET_EXCEEDED, "Over limit", 409,
                Map.of("scope", "org/team1"));

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getDetails()).containsEntry("scope", "org/team1");
    }

    @Test
    void handleGovernanceException_budgetFrozen_returns409() {
        GovernanceException ex = GovernanceException.budgetFrozen("scope1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.BUDGET_FROZEN);
    }

    @Test
    void handleGovernanceException_duplicateResource_returns409() {
        GovernanceException ex = GovernanceException.duplicateResource("Tenant", "tenant-1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void handleValidationException_returns400WithFieldErrors() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "name", "must not be blank"));
        bindingResult.addError(new FieldError("request", "scope", "must not be null"));
        MethodParameter param = new MethodParameter(Object.class.getDeclaredMethod("toString"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).contains("name");
        assertThat(response.getBody().getMessage()).contains("scope");
    }

    @Test
    void handleMalformedJson_returns400() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);

        ResponseEntity<ErrorResponse> response = handler.handleMalformedJson(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
    }

    @Test
    void handleMissingParam_returns400() throws Exception {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("tenant_id", "String");

        ResponseEntity<ErrorResponse> response = handler.handleMissingParam(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).contains("tenant_id");
    }

    @Test
    void handleTypeMismatch_returns400() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "limit", null, new NumberFormatException("For input string: \"abc\""));

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).contains("limit");
        assertThat(response.getBody().getMessage()).contains("abc");
    }

    @Test
    void handleGenericException_returns500() {
        Exception ex = new RuntimeException("unexpected");

        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("Internal error");
    }

    @Test
    void handleGenericException_wrappedGovernanceException_delegatesToGovernanceHandler() {
        GovernanceException governance = GovernanceException.budgetNotFound("scope1");

        ResponseEntity<ErrorResponse> response = handler.handleGenericException(governance, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.BUDGET_NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    @Test
    void handleConstraintViolation_returns400WithViolationDetails() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("create.request.scope");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be null");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(response.getBody().getMessage()).contains("scope");
        assertThat(response.getBody().getMessage()).contains("must not be null");
    }

    @Test
    void handleGovernanceException_nullDetails_excludesDetailsField() {
        GovernanceException ex = new GovernanceException(ErrorCode.INTERNAL_ERROR, "Something went wrong", 500);

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getBody().getDetails()).isNull();
    }

    @Test
    void handleGovernanceException_apiKeyNotFound_returns404() {
        GovernanceException ex = GovernanceException.apiKeyNotFound("key-123");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(response.getBody().getMessage()).contains("key-123");
    }

    @Test
    void handleGovernanceException_budgetClosed_returns409() {
        GovernanceException ex = GovernanceException.budgetClosed("scope1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.BUDGET_CLOSED);
    }

    @Test
    void resolveRequestId_whenRequestIsNull_returnsUuid() {
        GovernanceException ex = GovernanceException.tenantNotFound("tenant-1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, null);

        assertThat(response.getBody().getRequestId()).isNotNull();
        // Should be a valid UUID format
        assertThat(response.getBody().getRequestId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void resolveRequestId_whenRequestHasNoRequestIdAttribute_returnsUuid() {
        // mockRequest returns null for getAttribute by default (no REQUEST_ID_ATTRIBUTE set)
        GovernanceException ex = GovernanceException.tenantNotFound("tenant-1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getBody().getRequestId()).isNotNull();
        assertThat(response.getBody().getRequestId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void resolveRequestId_whenRequestHasRequestIdAttribute_returnsAttribute() {
        when(mockRequest.getAttribute("requestId")).thenReturn("custom-req-id-123");

        GovernanceException ex = GovernanceException.tenantNotFound("tenant-1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getBody().getRequestId()).isEqualTo("custom-req-id-123");
    }

    @SuppressWarnings("unchecked")
    @Test
    void handleConstraintViolation_pathWithoutDot_usesFullPath() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("scope");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("scope");
        assertThat(response.getBody().getMessage()).contains("must not be blank");
    }

    // --- v0.1.25.20: audit-on-failure — every @ExceptionHandler branch must
    // emit a failure audit entry via AuditFailureService.logFailure before
    // the error response is returned. Locks the contract so future refactors
    // can't silently drop audit coverage on 4xx/5xx paths. ---

    @Test
    void handleGovernanceException_writesFailureAudit() {
        GovernanceException ex = GovernanceException.tenantNotFound("tenant-1");

        handler.handleGovernanceException(ex, mockRequest);

        verify(auditFailure).logFailure(eq(mockRequest), eq(404),
                eq(ErrorCode.TENANT_NOT_FOUND), anyString(), isNull());
    }

    @Test
    void handleValidationException_writesFailureAudit() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "name", "must not be blank"));
        MethodParameter param = new MethodParameter(Object.class.getDeclaredMethod("toString"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        handler.handleValidationException(ex, mockRequest);

        verify(auditFailure).logFailure(eq(mockRequest), eq(400),
                eq(ErrorCode.INVALID_REQUEST), anyString(), isNull());
    }

    @SuppressWarnings("unchecked")
    @Test
    void handleConstraintViolation_writesFailureAudit() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("create.request.scope");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be null");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        handler.handleConstraintViolation(ex, mockRequest);

        verify(auditFailure).logFailure(eq(mockRequest), eq(400),
                eq(ErrorCode.INVALID_REQUEST), anyString(), isNull());
    }

    @Test
    void handleMissingParam_writesFailureAudit() throws Exception {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("tenant_id", "String");

        handler.handleMissingParam(ex, mockRequest);

        verify(auditFailure).logFailure(eq(mockRequest), eq(400),
                eq(ErrorCode.INVALID_REQUEST), anyString(), isNull());
    }

    @Test
    void handleTypeMismatch_writesFailureAudit() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "limit", null, new NumberFormatException("bad"));

        handler.handleTypeMismatch(ex, mockRequest);

        verify(auditFailure).logFailure(eq(mockRequest), eq(400),
                eq(ErrorCode.INVALID_REQUEST), anyString(), isNull());
    }

    @Test
    void handleMalformedJson_writesFailureAudit() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);

        handler.handleMalformedJson(ex, mockRequest);

        verify(auditFailure).logFailure(eq(mockRequest), eq(400),
                eq(ErrorCode.INVALID_REQUEST), eq("Malformed request body"), isNull());
    }

    @Test
    void handleGenericException_writesFailureAudit_withExceptionClassInExtras() {
        Exception ex = new RuntimeException("unexpected boom");

        handler.handleGenericException(ex, mockRequest);

        // Generic-branch audit carries exception_class in extras so ops can
        // correlate audit entry to server log line post-incident.
        verify(auditFailure).logFailure(eq(mockRequest), eq(500),
                eq(ErrorCode.INTERNAL_ERROR), eq("unexpected boom"),
                any(Map.class));
    }

    @Test
    void handleGenericException_wrappedGovernance_delegatesWithoutDoubleAudit() {
        // Single-write invariant: when a GovernanceException reaches the
        // generic branch it must delegate to handleGovernanceException, which
        // does exactly ONE audit write. The generic branch must not also
        // write (would produce two rows for the same failure).
        GovernanceException governance = GovernanceException.budgetNotFound("scope1");

        handler.handleGenericException(governance, mockRequest);

        verify(auditFailure, times(1)).logFailure(eq(mockRequest), eq(404),
                eq(ErrorCode.BUDGET_NOT_FOUND), anyString(), isNull());
        verify(auditFailure, never()).logFailure(eq(mockRequest), eq(500),
                eq(ErrorCode.INTERNAL_ERROR), anyString(), any());
    }
}
