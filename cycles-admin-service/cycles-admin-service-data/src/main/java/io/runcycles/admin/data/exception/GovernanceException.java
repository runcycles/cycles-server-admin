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
        return new GovernanceException(ErrorCode.BUDGET_NOT_FOUND, "Budget not found for provided scope: " + scope, 404);
    }
    public static GovernanceException apiKeyNotFound(String id) {
        return new GovernanceException(ErrorCode.NOT_FOUND, "API key not found: " + id, 404);
    }
    public static GovernanceException duplicateResource(String resource, String id) {
        return new GovernanceException(ErrorCode.DUPLICATE_RESOURCE, resource + " exists: " + id, 409);
    }
    public static GovernanceException insufficientFunds(String scope) {
        return new GovernanceException(ErrorCode.BUDGET_EXCEEDED, "Insufficient funds: " + scope, 409);
    }
    public static GovernanceException budgetFrozen(String scope) {
        return new GovernanceException(ErrorCode.BUDGET_FROZEN, "Budget is frozen: " + scope, 409);
    }
    public static GovernanceException budgetClosed(String scope) {
        return new GovernanceException(ErrorCode.BUDGET_CLOSED, "Budget is closed: " + scope, 409);
    }
    public static GovernanceException policyNotFound(String id) {
        return new GovernanceException(ErrorCode.NOT_FOUND, "Policy not found: " + id, 404);
    }
    public static GovernanceException unitMismatch(String expected, String actual) {
        return new GovernanceException(ErrorCode.UNIT_MISMATCH,
            "Unit mismatch: expected " + expected + " but got " + actual, 400);
    }

    public static GovernanceException scopeFilterDenied(String scope) {
        return new GovernanceException(ErrorCode.FORBIDDEN,
            "API key scope_filter does not permit access to scope: " + scope, 403);
    }
    public static GovernanceException webhookNotFound(String id) {
        return new GovernanceException(ErrorCode.WEBHOOK_NOT_FOUND, "Webhook subscription not found: " + id, 404);
    }
    public static GovernanceException webhookUrlInvalid(String url, String reason) {
        return new GovernanceException(ErrorCode.WEBHOOK_URL_INVALID,
            "Webhook URL invalid: " + url + " (" + reason + ")", 400);
    }
    public static GovernanceException eventNotFound(String id) {
        return new GovernanceException(ErrorCode.EVENT_NOT_FOUND, "Event not found: " + id, 404);
    }
    public static GovernanceException replayInProgress(String subscriptionId) {
        return new GovernanceException(ErrorCode.REPLAY_IN_PROGRESS,
            "Replay already in progress for subscription: " + subscriptionId, 409);
    }
}
