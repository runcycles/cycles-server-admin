package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

@Service
public class WebhookUrlValidator {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookUrlValidator.class);
    private final WebhookSecurityConfigRepository configRepository;

    public WebhookUrlValidator(WebhookSecurityConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw GovernanceException.webhookUrlInvalid(url, "URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw GovernanceException.webhookUrlInvalid(url, "Malformed URL");
        }
        WebhookSecurityConfig config = configRepository.get();
        // Check HTTPS requirement
        if (!Boolean.TRUE.equals(config.getAllowHttp()) && !"https".equals(uri.getScheme())) {
            throw GovernanceException.webhookUrlInvalid(url, "HTTPS required");
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw GovernanceException.webhookUrlInvalid(url, "Only HTTP(S) URLs are allowed");
        }
        // Check host resolution against blocked CIDRs
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw GovernanceException.webhookUrlInvalid(url, "No host in URL");
        }
        // Always check for private/reserved IPs to prevent SSRF
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isPrivateOrReserved(addr)) {
                    throw GovernanceException.webhookUrlInvalid(url,
                        "Resolves to private/reserved IP: " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw GovernanceException.webhookUrlInvalid(url, "Cannot resolve hostname: " + host);
        }
        // Check allowed URL patterns
        List<String> patterns = config.getAllowedUrlPatterns();
        if (patterns != null && !patterns.isEmpty()) {
            boolean matched = patterns.stream().anyMatch(p -> matchesGlob(url, p));
            if (!matched) {
                throw GovernanceException.webhookUrlInvalid(url, "URL does not match any allowed pattern");
            }
        }
    }

    private boolean isPrivateOrReserved(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isSiteLocalAddress() ||
               addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
    }

    boolean matchesGlob(String url, String pattern) {
        // Simple glob: * matches any sequence of characters
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        try {
            return url.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }
}
