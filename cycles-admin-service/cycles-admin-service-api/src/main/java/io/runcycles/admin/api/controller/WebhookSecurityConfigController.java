package io.runcycles.admin.api.controller;

import static io.runcycles.admin.api.logging.LogSanitizer.safe;

import io.runcycles.admin.api.filter.RequestIdFilter;
import io.runcycles.admin.api.filter.TraceContextFilter;
import io.runcycles.admin.data.repository.AuditRepository;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/v1/admin/config") @Tag(name = "Webhooks")
public class WebhookSecurityConfigController {
    @Autowired private WebhookSecurityConfigRepository repository;
    @Autowired private AuditRepository auditRepository;

    @GetMapping("/webhook-security") @Operation(operationId = "getWebhookSecurityConfig")
    public ResponseEntity<WebhookSecurityConfig> get() {
        return ResponseEntity.ok(repository.get());
    }

    @PutMapping("/webhook-security") @Operation(operationId = "updateWebhookSecurityConfig")
    public ResponseEntity<WebhookSecurityConfig> update(@Valid @RequestBody WebhookSecurityConfig config, HttpServletRequest httpRequest) {
        repository.save(config);
        auditRepository.log(AuditLogEntry.builder()
            .requestId(attr(httpRequest, RequestIdFilter.REQUEST_ID_ATTRIBUTE))
            .traceId(attr(httpRequest, TraceContextFilter.TRACE_ID_ATTRIBUTE))
            .sourceIp(httpRequest.getRemoteAddr())
            .userAgent(httpRequest.getHeader("User-Agent"))
            .resourceType("config").resourceId("webhook-security")
            .operation("updateWebhookSecurityConfig")
            .status(200)
            .metadata(buildConfigMeta(config))
            .build());
        return ResponseEntity.ok(repository.get());
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return safe(v);
    }

    private java.util.Map<String, Object> buildConfigMeta(WebhookSecurityConfig config) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("allow_http", config.getAllowHttp() != null ? config.getAllowHttp() : false);
        if (config.getBlockedCidrRanges() != null) meta.put("blocked_cidr_count", config.getBlockedCidrRanges().size());
        if (config.getAllowedUrlPatterns() != null) meta.put("allowed_url_pattern_count", config.getAllowedUrlPatterns().size());
        return meta;
    }
}
