package io.runcycles.admin.data.exception;

import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GovernanceException")
class GovernanceExceptionTest {

    @Test
    @DisplayName("3-arg constructor sets fields and details is null")
    void constructor_threeArgs_detailsIsNull() {
        GovernanceException ex = new GovernanceException(ErrorCode.INTERNAL_ERROR, "something broke", 500);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ex.getMessage()).isEqualTo("something broke");
        assertThat(ex.getHttpStatus()).isEqualTo(500);
        assertThat(ex.getDetails()).isNull();
    }

    @Test
    @DisplayName("4-arg constructor sets fields including details map")
    void constructor_fourArgs_setsDetails() {
        Map<String, Object> details = Map.of("field", "value");
        GovernanceException ex = new GovernanceException(ErrorCode.INVALID_REQUEST, "bad request", 400, details);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ex.getMessage()).isEqualTo("bad request");
        assertThat(ex.getHttpStatus()).isEqualTo(400);
        assertThat(ex.getDetails()).containsEntry("field", "value");
    }

    @Test
    @DisplayName("tenantNotFound() returns TENANT_NOT_FOUND with 404")
    void tenantNotFound_returnsCorrectException() {
        GovernanceException ex = GovernanceException.tenantNotFound("t-123");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
        assertThat(ex.getMessage()).contains("t-123");
    }

    @Test
    @DisplayName("budgetNotFound() returns BUDGET_NOT_FOUND with 404")
    void budgetNotFound_returnsCorrectException() {
        GovernanceException ex = GovernanceException.budgetNotFound("org/team1");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUDGET_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
        assertThat(ex.getMessage()).contains("org/team1");
    }

    @Test
    @DisplayName("apiKeyNotFound() returns NOT_FOUND with 404")
    void apiKeyNotFound_returnsCorrectException() {
        GovernanceException ex = GovernanceException.apiKeyNotFound("key_abc");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
        assertThat(ex.getMessage()).contains("key_abc");
    }

    @Test
    @DisplayName("duplicateResource() returns DUPLICATE_RESOURCE with 409")
    void duplicateResource_returnsCorrectException() {
        GovernanceException ex = GovernanceException.duplicateResource("Tenant", "t-123");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("Tenant").contains("t-123");
    }

    @Test
    @DisplayName("insufficientFunds() returns BUDGET_EXCEEDED with 409")
    void insufficientFunds_returnsCorrectException() {
        GovernanceException ex = GovernanceException.insufficientFunds("org/team1");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("org/team1");
    }

    @Test
    @DisplayName("budgetFrozen() returns BUDGET_FROZEN with 409")
    void budgetFrozen_returnsCorrectException() {
        GovernanceException ex = GovernanceException.budgetFrozen("org/team1");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUDGET_FROZEN);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("org/team1");
    }

    @Test
    @DisplayName("budgetClosed() returns BUDGET_CLOSED with 409")
    void budgetClosed_returnsCorrectException() {
        GovernanceException ex = GovernanceException.budgetClosed("org/team1");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUDGET_CLOSED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("org/team1");
    }
}
