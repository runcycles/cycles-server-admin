package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.PolicyRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.policy.Policy;
import io.runcycles.admin.model.policy.PolicyCreateRequest;
import io.runcycles.admin.model.policy.PolicyListResponse;
import io.runcycles.admin.model.policy.PolicyStatus;
import io.runcycles.admin.model.policy.PolicyUpdateRequest;
import io.runcycles.admin.api.config.ScopeFilterUtil;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.model.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
// v0: policies (caps, overage overrides, TTL overrides, rate limits) are stored for future consumption.
// Runtime enforcement by the protocol server is deferred to a future version.
@RestController @RequestMapping("/v1/admin/policies") @Tag(name = "Policies")
public class PolicyController {
    private static final Logger LOG = LoggerFactory.getLogger(PolicyController.class);
    @Autowired private PolicyRepository repository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @PostMapping @Operation(operationId = "createPolicy")
    public ResponseEntity<Policy> create(@Valid @RequestBody PolicyCreateRequest request, HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, request.getScopePattern());
        // v0.1.25.14 dual-auth (spec v0.1.25.13). Same shape as
        // BudgetController.create — admin caller MUST send tenant_id in body,
        // tenant caller MUST NOT.
        String authTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        boolean isAdminAuth = authTenantId == null;
        if (isAdminAuth) {
            if (request.getTenantId() == null || request.getTenantId().isBlank()) {
                throw new GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "tenant_id is required in the request body when using admin key authentication", 400);
            }
        } else if (request.getTenantId() != null) {
            throw new GovernanceException(
                io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                "tenant_id MUST NOT be set when using API key authentication (tenant is inferred from the key)", 400);
        }
        String tenantId = isAdminAuth ? request.getTenantId() : authTenantId;
        Policy policy = repository.create(tenantId, request);
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(tenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("policy").resourceId(policy.getPolicyId())
            .operation("createPolicy")
            .status(201)
            .metadata(Map.of("name", request.getName(), "scope_pattern", request.getScopePattern(),
                // Sourced from ActorType.@JsonValue so an enum rename can't
                // silently drift the audit-log format.
                "actor_type", (isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY).getValue()))
            .build());
        try {
            ActorType actorType = isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY;
            eventService.emit(EventType.POLICY_CREATED, tenantId, request.getScopePattern(), "cycles-admin",
                Actor.builder().type(actorType)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                objectMapper.convertValue(EventDataPolicy.builder()
                    .policyId(policy.getPolicyId()).name(request.getName())
                    .scopePattern(request.getScopePattern()).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.status(201).body(policy);
    }
    @PatchMapping("/{policy_id}") @Operation(operationId = "updatePolicy")
    public ResponseEntity<Policy> update(@PathVariable("policy_id") String policyId, @Valid @RequestBody PolicyUpdateRequest request, HttpServletRequest httpRequest) {
        String scopePattern = repository.getScopePattern(policyId);
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scopePattern);
        // v0.1.25.14 dual-auth: admin caller passes null tenantId to the
        // repository (the Lua script skips ownership check); tenant caller
        // passes their authenticated tenant id (Lua enforces ownership).
        // No body change needed because policy_id pins the owning tenant —
        // the persisted record's tenant_id is trusted as the audit subject.
        String authTenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        boolean isAdminAuth = authTenantId == null;
        Policy policy = repository.update(authTenantId, policyId, request);
        // For audit / event the subject tenant is the policy's stored owner,
        // not the (possibly null) caller — keeps history attributable to the
        // tenant whose policy was modified.
        String subjectTenantId = isAdminAuth ? policy.getTenantId() : authTenantId;
        auditRepository.log(buildAuditEntry(httpRequest)
            .tenantId(subjectTenantId)
            .keyId((String) httpRequest.getAttribute("authenticated_key_id"))
            .resourceType("policy").resourceId(policyId)
            .operation("updatePolicy")
            .status(200)
            .metadata(buildUpdatePolicyMetaWithActor(scopePattern, request, isAdminAuth))
            .build());
        try {
            ActorType actorType = isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY;
            eventService.emit(EventType.POLICY_UPDATED, subjectTenantId, scopePattern, "cycles-admin",
                Actor.builder().type(actorType)
                    .keyId((String) httpRequest.getAttribute("authenticated_key_id")).build(),
                objectMapper.convertValue(EventDataPolicy.builder()
                    .policyId(policyId).scopePattern(scopePattern).build(), Map.class),
                null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
        } catch (Exception e) {
            LOG.warn("Failed to emit event: {}", e.getMessage());
        }
        return ResponseEntity.ok(policy);
    }
    @GetMapping @Operation(operationId = "listPolicies")
    public ResponseEntity<PolicyListResponse> list(
            @RequestParam(required = false) String tenant_id,
            @RequestParam(required = false) String scope_pattern,
            @RequestParam(required = false) PolicyStatus status,
            // v0.1.25.8: accepted and ignored. v0.1.26+ servers with action quotas extension
            // will wire this up. Typed as String (not Boolean) so any value — including
            // malformed ones from newer clients — is accepted without a 400, per spec
            // requirement "MUST ignore without error".
            @SuppressWarnings("unused") @RequestParam(required = false) String has_action_quotas,
            @SuppressWarnings("unused") @RequestParam(required = false) String references_action_kind,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        ScopeFilterUtil.enforceScopeFilter(httpRequest, scope_pattern);
        String tenantId = (String) httpRequest.getAttribute("authenticated_tenant_id");
        if (tenantId == null) {
            // Admin key auth — tenant_id query param is required for scoping
            if (tenant_id == null || tenant_id.isBlank()) {
                throw new io.runcycles.admin.data.exception.GovernanceException(
                    io.runcycles.admin.model.shared.ErrorCode.INVALID_REQUEST,
                    "tenant_id query parameter is required when using admin key authentication",
                    400);
            }
            tenantId = tenant_id;
        }
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        var policies = repository.list(tenantId, scope_pattern, status, cursor, effectiveLimit);
        PolicyListResponse response = PolicyListResponse.builder()
            .policies(policies)
            .hasMore(policies.size() >= effectiveLimit)
            .nextCursor(policies.size() >= effectiveLimit ? policies.get(policies.size() - 1).getPolicyId() : null)
            .build();
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildUpdatePolicyMeta(String scopePattern, PolicyUpdateRequest request) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        if (scopePattern != null) meta.put("scope_pattern", scopePattern);
        if (request.getName() != null) meta.put("name", request.getName());
        if (request.getPriority() != null) meta.put("priority", request.getPriority());
        if (request.getStatus() != null) meta.put("new_status", request.getStatus().name());
        if (request.getCaps() != null) meta.put("caps_updated", true);
        if (request.getCommitOveragePolicy() != null) meta.put("commit_overage_policy", request.getCommitOveragePolicy().name());
        return meta.isEmpty() ? null : meta;
    }

    // v0.1.25.14: same fields as buildUpdatePolicyMeta but always emits the
    // actor_type discriminator so audit reviewers can filter
    // admin-on-behalf-of vs tenant self-service updates without joining to
    // the keys table.
    private Map<String, Object> buildUpdatePolicyMetaWithActor(String scopePattern, PolicyUpdateRequest request, boolean isAdminAuth) {
        Map<String, Object> meta = buildUpdatePolicyMeta(scopePattern, request);
        if (meta == null) meta = new java.util.LinkedHashMap<>();
        meta.put("actor_type", (isAdminAuth ? ActorType.ADMIN_ON_BEHALF_OF : ActorType.API_KEY).getValue());
        return meta;
    }

    private AuditLogEntry.AuditLogEntryBuilder buildAuditEntry(HttpServletRequest request) {
        return AuditLogEntry.builder()
            .requestId(request.getAttribute("requestId") != null ? request.getAttribute("requestId").toString() : null)
            .sourceIp(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"));
    }
}
