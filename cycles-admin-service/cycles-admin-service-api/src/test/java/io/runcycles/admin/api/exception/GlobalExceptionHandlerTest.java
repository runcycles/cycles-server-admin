package io.runcycles.admin.api.exception;

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
import org.springframework.web.bind.MethodArgumentNotValidException;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        mockRequest = mock(HttpServletRequest.class);
    }

    @Test
    void handleGovernanceException_returnsCorrectStatusAndBody() {
        GovernanceException ex = GovernanceException.tenantNotFound("t1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
        assertThat(response.getBody().getMessage()).contains("t1");
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
        GovernanceException ex = GovernanceException.duplicateResource("Tenant", "t1");

        ResponseEntity<ErrorResponse> response = handler.handleGovernanceException(ex, mockRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void handleValidationException_returns400WithFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "name", "must not be blank"));
        bindingResult.addError(new FieldError("request", "scope", "must not be null"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

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
    void handleGenericException_returns500() {
        Exception ex = new RuntimeException("unexpected");

        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, mockRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("Internal error");
    }
}
