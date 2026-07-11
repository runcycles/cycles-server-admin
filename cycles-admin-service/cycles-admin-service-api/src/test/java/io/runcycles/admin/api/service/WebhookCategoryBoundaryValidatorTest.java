package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.model.event.EventCategory;
import io.runcycles.admin.model.event.EventType;
import io.runcycles.admin.model.shared.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookCategoryBoundaryValidatorTest {

    private final WebhookCategoryBoundaryValidator validator = new WebhookCategoryBoundaryValidator();

    // ---- isSystemTarget ----

    @Test
    void isSystemTarget_trueForSentinelNullBlank() {
        assertThat(validator.isSystemTarget("__system__")).isTrue();
        assertThat(validator.isSystemTarget(null)).isTrue();
        assertThat(validator.isSystemTarget("  ")).isTrue();
    }

    @Test
    void isSystemTarget_falseForConcreteTenant() {
        assertThat(validator.isSystemTarget("tenant-1")).isFalse();
    }

    // ---- validateEventTypes ----

    @Test
    void validateEventTypes_adminOnly_throws400() {
        assertThatThrownBy(() -> validator.validateEventTypes(List.of(EventType.API_KEY_CREATED)))
            .isInstanceOf(GovernanceException.class)
            .hasFieldOrPropertyWithValue("httpStatus", 400)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST)
            .hasMessageContaining("admin-only");
    }

    @Test
    void validateEventTypes_tenantAccessible_ok_andNullNoop() {
        assertThatCode(() -> validator.validateEventTypes(
            List.of(EventType.BUDGET_CREATED, EventType.RESERVATION_DENIED, EventType.TENANT_CREATED)))
            .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateEventTypes(null)).doesNotThrowAnyException();
    }

    // ---- validateEventCategories ----

    @Test
    void validateEventCategories_adminOnly_throws400() {
        for (EventCategory admin : List.of(EventCategory.API_KEY, EventCategory.POLICY,
                EventCategory.WEBHOOK, EventCategory.SYSTEM)) {
            assertThatThrownBy(() -> validator.validateEventCategories(List.of(admin)))
                .as(admin.name())
                .isInstanceOf(GovernanceException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400)
                .hasMessageContaining("admin-only");
        }
    }

    @Test
    void validateEventCategories_tenantAccessible_ok_andNullNoop() {
        assertThatCode(() -> validator.validateEventCategories(
            List.of(EventCategory.BUDGET, EventCategory.RESERVATION, EventCategory.TENANT)))
            .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateEventCategories(null)).doesNotThrowAnyException();
    }

    // ---- validateForTarget: the admin-plane gate ----

    @Test
    void validateForTarget_systemTarget_skipsValidation() {
        // Admin-only on __system__ is allowed (not tenant-owned).
        assertThatCode(() -> validator.validateForTarget("__system__",
            List.of(EventType.API_KEY_CREATED), List.of(EventCategory.SYSTEM)))
            .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForTarget(null,
            null, List.of(EventCategory.WEBHOOK)))
            .doesNotThrowAnyException();
    }

    @Test
    void validateForTarget_concreteTenant_adminCategory_throws400() {
        assertThatThrownBy(() -> validator.validateForTarget("tenant-1",
            List.of(EventType.BUDGET_CREATED), List.of(EventCategory.API_KEY)))
            .isInstanceOf(GovernanceException.class)
            .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void validateForTarget_concreteTenant_adminEventType_throws400() {
        assertThatThrownBy(() -> validator.validateForTarget("tenant-1",
            List.of(EventType.POLICY_CREATED), null))
            .isInstanceOf(GovernanceException.class)
            .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void validateForTarget_concreteTenant_tenantAccessible_ok() {
        assertThatCode(() -> validator.validateForTarget("tenant-1",
            List.of(EventType.BUDGET_CREATED), List.of(EventCategory.RESERVATION)))
            .doesNotThrowAnyException();
    }
}
