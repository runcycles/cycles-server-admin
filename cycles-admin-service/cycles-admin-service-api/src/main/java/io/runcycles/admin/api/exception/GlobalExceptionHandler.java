package io.runcycles.admin.api.exception;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.shared.ErrorResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<ErrorResponse> handleGovernanceException(GovernanceException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(ErrorResponse.builder()
            .error(ex.getErrorCode()).message(ex.getMessage()).requestId(UUID.randomUUID().toString()).details(ex.getDetails()).build());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.builder().error(ErrorCode.INTERNAL_ERROR).message("Internal error").requestId(UUID.randomUUID().toString()).build());
    }
}
