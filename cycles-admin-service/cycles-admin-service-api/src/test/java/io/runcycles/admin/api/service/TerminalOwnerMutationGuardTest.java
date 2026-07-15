package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.TenantRepository;
import io.runcycles.admin.data.repository.WebhookRepository;
import io.runcycles.admin.model.shared.ErrorCode;
import io.runcycles.admin.model.tenant.Tenant;
import io.runcycles.admin.model.tenant.TenantStatus;
import io.runcycles.admin.model.webhook.WebhookSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Spec v0.1.25.29 Rule 2 unit coverage.
 *
 * <p>Pins the guard's contract: CLOSED-tenant owners make mutations 409
 * {@code TENANT_CLOSED}; ACTIVE/SUSPENDED owners pass through; missing /
 * malformed inputs defer to the caller's own validation rather than
 * masking them as 409s.
 */
@ExtendWith(MockitoExtension.class)
class TerminalOwnerMutationGuardTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private WebhookRepository webhookRepository;
    private TerminalOwnerMutationGuard guard;

    @BeforeEach
    void setUp() {
        guard = new TerminalOwnerMutationGuard();
        ReflectionTestUtils.setField(guard, "tenantRepository", tenantRepository);
        ReflectionTestUtils.setField(guard, "webhookRepository", webhookRepository);
    }

    @Test
    void assertTenantOpen_closedTenant_throws409TenantClosed() {
        when(tenantRepository.get("t-closed")).thenReturn(
            Tenant.builder().tenantId("t-closed").status(TenantStatus.CLOSED).build());

        assertThatThrownBy(() -> guard.assertTenantOpen("t-closed"))
            .isInstanceOfSatisfying(GovernanceException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TENANT_CLOSED);
                assertThat(e.getHttpStatus()).isEqualTo(409);
                assertThat(e.getMessage()).contains("t-closed");
            });
    }

    @Test
    void assertTenantOpen_activeTenant_passesThrough() {
        when(tenantRepository.get("t-active")).thenReturn(
            Tenant.builder().tenantId("t-active").status(TenantStatus.ACTIVE).build());

        guard.assertTenantOpen("t-active");
    }

    @Test
    void assertTenantOpen_suspendedTenant_passesThrough() {
        when(tenantRepository.get("t-susp")).thenReturn(
            Tenant.builder().tenantId("t-susp").status(TenantStatus.SUSPENDED).build());

        guard.assertTenantOpen("t-susp");
    }

    @Test
    void assertTenantOpen_nullRepositoryResult_passesThrough() {
        when(tenantRepository.get("t-raced-away")).thenReturn(null);

        guard.assertTenantOpen("t-raced-away");
    }

    @Test
    void assertTenantOpen_nullOrBlank_noLookup() {
        // A controller that has no tenant id yet (e.g. still computing it from
        // the request) must not be rejected with 409 — the caller's own
        // validation handles the missing-id case.
        guard.assertTenantOpen(null);
        guard.assertTenantOpen("");
        guard.assertTenantOpen("   ");
    }

    @Test
    void assertTenantOpen_tenantNotFound_defersToCaller() {
        // If the tenant lookup itself fails (NOT_FOUND, etc.), the guard stays
        // silent so the calling controller surfaces its own canonical error
        // rather than the guard masking it as a 409.
        when(tenantRepository.get("ghost")).thenThrow(GovernanceException.tenantNotFound("ghost"));

        guard.assertTenantOpen("ghost");
    }

    @Test
    void assertOpenForScope_closedTenantPrefix_throws() {
        when(tenantRepository.get("acme")).thenReturn(
            Tenant.builder().tenantId("acme").status(TenantStatus.CLOSED).build());

        assertThatThrownBy(() -> guard.assertOpenForScope("tenant:acme/workspace:eng"))
            .isInstanceOf(GovernanceException.class);
    }

    @Test
    void assertOpenForScope_bareTenantPrefix_throws() {
        when(tenantRepository.get("acme")).thenReturn(
            Tenant.builder().tenantId("acme").status(TenantStatus.CLOSED).build());

        assertThatThrownBy(() -> guard.assertOpenForScope("tenant:acme"))
            .isInstanceOf(GovernanceException.class);
    }

    @Test
    void assertOpenForScope_malformed_skipped() {
        // ScopeValidator is authoritative for scope-grammar 400s — the guard
        // must not shadow those with a misleading 409.
        guard.assertOpenForScope("workspace:eng");   // no tenant: prefix
        guard.assertOpenForScope(null);
        guard.assertOpenForScope("");
        guard.assertOpenForScope("tenant:");         // empty tenant id
        guard.assertOpenForScope("tenant:/kind:x");  // empty tenant id before separator
    }

    @Test
    void assertOpenForWebhook_closedOwner_throws() {
        when(webhookRepository.findById("sub_1")).thenReturn(
            WebhookSubscription.builder().subscriptionId("sub_1").tenantId("t-closed").build());
        when(tenantRepository.get("t-closed")).thenReturn(
            Tenant.builder().tenantId("t-closed").status(TenantStatus.CLOSED).build());

        assertThatThrownBy(() -> guard.assertOpenForWebhook("sub_1"))
            .isInstanceOfSatisfying(GovernanceException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TENANT_CLOSED));
    }

    @Test
    void assertOpenForWebhook_unknownSubscription_skipped() {
        when(webhookRepository.findById("missing")).thenThrow(
            GovernanceException.webhookNotFound("missing"));

        guard.assertOpenForWebhook("missing");
    }

    @Test
    void assertOpenForWebhook_nullRepositoryResult_skipped() {
        when(webhookRepository.findById("raced-away")).thenReturn(null);

        guard.assertOpenForWebhook("raced-away");
    }

    @Test
    void assertOpenForWebhook_blank_skipped() {
        guard.assertOpenForWebhook(null);
        guard.assertOpenForWebhook("");
    }

    @Test
    void extractTenantIdFromScope_handlesEdgeCases() {
        assertThat(TerminalOwnerMutationGuard.extractTenantIdFromScope("tenant:acme")).isEqualTo("acme");
        assertThat(TerminalOwnerMutationGuard.extractTenantIdFromScope("tenant:acme/kind:x"))
            .isEqualTo("acme");
        assertThat(TerminalOwnerMutationGuard.extractTenantIdFromScope("tenant:acme/kind:x/sub:y"))
            .isEqualTo("acme");
        assertThat(TerminalOwnerMutationGuard.extractTenantIdFromScope("tenant:")).isNull();
        assertThat(TerminalOwnerMutationGuard.extractTenantIdFromScope("tenant:/kind:x")).isNull();
        assertThat(TerminalOwnerMutationGuard.extractTenantIdFromScope("workspace:eng")).isNull();
        assertThat(TerminalOwnerMutationGuard.extractTenantIdFromScope(null)).isNull();
    }
}
