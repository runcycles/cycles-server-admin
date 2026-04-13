package io.runcycles.admin.api.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.auth.ApiKeyCreateRequest;
import io.runcycles.admin.model.auth.ApiKeyUpdateRequest;
import io.runcycles.admin.model.auth.ApiKeyValidationRequest;
import io.runcycles.admin.model.budget.BudgetCreateRequest;
import io.runcycles.admin.model.budget.BudgetFundingRequest;
import io.runcycles.admin.model.budget.BudgetUpdateRequest;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.tenant.TenantCreateRequest;
import io.runcycles.admin.model.webhook.ReplayRequest;
import io.runcycles.admin.model.webhook.WebhookCreateRequest;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import io.runcycles.admin.model.webhook.WebhookUpdateRequest;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-3 contract check — DTO CONSTRAINT DRIFT.
 *
 * <p>For every admin request-body DTO used on a {@code @RequestBody}
 * controller parameter, asserts that each property the spec marks
 * {@code required} has a corresponding Java field annotated with one of
 * {@code @NotNull}, {@code @NotBlank}, or {@code @NotEmpty}.
 *
 * <p>Catches the class of drift where the spec tightens a field to
 * {@code required} but the DTO still accepts null — an invalid request
 * would pass Bean Validation, flow into the controller with a null
 * field, and either 500 or silently 200 on structurally-invalid input.
 *
 * <p>DTO field-to-property mapping follows {@code @JsonProperty}; if
 * absent, the field name is compared directly.
 *
 * <p>Deliberately conservative: checks only {@code required → @NotNull}.
 * Ranges ({@code maxLength}/{@code pattern}) vary enough between spec
 * style and Bean Validation that a strict check produces false positives;
 * runtime {@code @Valid} in prod plus the response-schema validator cover
 * most of those cases.
 *
 * <p>This is the admin-side port of cycles-server's
 * {@code DtoConstraintContractTest}, reflecting the admin's
 * {@code @RequestBody} DTOs rather than the runtime-plane reservation DTOs.
 */
class DtoConstraintContractTest {

    /** Request DTOs whose spec schema is a NAMED component (not inline under a path's
     * requestBody). Inline-schema endpoints are omitted because the spec uses them for
     * partial-update bodies with no {@code required} fields anyway — nothing to check.
     * Concretely: {@code BudgetUpdateRequest}, {@code ApiKeyUpdateRequest},
     * {@code ReplayRequest}, {@code TenantUpdateRequest}, {@code PolicyUpdateRequest}
     * are all inline in the admin spec, so they're excluded from this reflective check. */
    private static final List<Class<?>> REQUEST_DTOS = List.of(
            TenantCreateRequest.class,
            BudgetCreateRequest.class,
            BudgetFundingRequest.class,
            PolicyCreateRequest.class,
            ApiKeyCreateRequest.class,
            ApiKeyValidationRequest.class,
            WebhookCreateRequest.class,
            WebhookUpdateRequest.class,
            WebhookSecurityConfig.class
    );

    @Test
    @EnabledIf(value = "io.runcycles.admin.api.contract.ContractValidationConfig#validationEnabled",
               disabledReason = "contract.validation.enabled=false — skipping DTO constraint check")
    void specRequiredFields_haveCorrespondingNotNullOnDto() {
        OpenAPI spec = new OpenAPIV3Parser().readContents(ContractSpecLoader.loadSpec())
                .getOpenAPI();
        Map<String, Schema> schemas = spec.getComponents().getSchemas();

        List<String> violations = new ArrayList<>();
        for (Class<?> dtoClass : REQUEST_DTOS) {
            Schema<?> schema = schemas.get(dtoClass.getSimpleName());
            if (schema == null) {
                violations.add(dtoClass.getSimpleName() + " — no matching spec schema");
                continue;
            }
            List<String> requiredProps = schema.getRequired();
            if (requiredProps == null || requiredProps.isEmpty()) continue;

            for (String prop : requiredProps) {
                Field field = findFieldByJsonName(dtoClass, prop);
                if (field == null) {
                    violations.add(dtoClass.getSimpleName() + "." + prop
                            + " — spec requires this but DTO has no matching field");
                    continue;
                }
                if (!hasNotNullAnnotation(field)) {
                    violations.add(dtoClass.getSimpleName() + "." + prop
                            + " (field " + field.getName()
                            + ") — spec requires this but field lacks @NotNull/@NotBlank/@NotEmpty");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "DTO constraint drift vs spec:\n  - " + String.join("\n  - ", violations));
    }

    private static Field findFieldByJsonName(Class<?> cls, String jsonName) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                JsonProperty jp = f.getAnnotation(JsonProperty.class);
                String name = (jp != null && !jp.value().isEmpty()) ? jp.value() : f.getName();
                if (name.equals(jsonName)) return f;
            }
        }
        return null;
    }

    private static boolean hasNotNullAnnotation(Field field) {
        for (Annotation a : field.getAnnotations()) {
            Class<? extends Annotation> t = a.annotationType();
            if (t == NotNull.class || t == NotBlank.class || t == NotEmpty.class) return true;
        }
        return false;
    }
}
