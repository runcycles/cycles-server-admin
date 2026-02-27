package io.runcycles.admin.data.exception;
import io.runcycles.admin.model.shared.ErrorCode;
import lombok.Getter;
import java.util.Map;
@Getter
public class GovernanceException extends RuntimeException {
    private final ErrorCode errorCode;
    private final int httpStatus;
    private final Map<String, Object> details;
    public GovernanceException(ErrorCode errorCode, String message, int httpStatus) {
        this(errorCode, message, httpStatus, null);
    }
    public GovernanceException(ErrorCode errorCode, String message, int httpStatus, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.details = details;
    }
    public static GovernanceException tenantNotFound(String id) {
        return new GovernanceException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found: " + id, 404);
    }
    public static GovernanceException budgetNotFound(String scope) {
        return new GovernanceException(ErrorCode.BUDGET_NOT_FOUND, "Budget not found: " + scope, 404);
    }
    public static GovernanceException duplicateResource(String resource, String id) {
        return new GovernanceException(ErrorCode.DUPLICATE_RESOURCE, resource + " exists: " + id, 409);
    }
}
