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
import java.util.stream.Collectors;

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
        // Check resolved IPs against configured blocked CIDR ranges
        List<CidrRange> blockedRanges = parseCidrRanges(config.getBlockedCidrRanges());
        if (!blockedRanges.isEmpty()) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress addr : addresses) {
                    for (CidrRange range : blockedRanges) {
                        if (range.contains(addr)) {
                            throw GovernanceException.webhookUrlInvalid(url,
                                "Resolves to blocked IP: " + addr.getHostAddress());
                        }
                    }
                }
            } catch (UnknownHostException e) {
                throw GovernanceException.webhookUrlInvalid(url, "Cannot resolve hostname: " + host);
            }
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

    private List<CidrRange> parseCidrRanges(List<String> cidrStrings) {
        if (cidrStrings == null || cidrStrings.isEmpty()) {
            return List.of();
        }
        return cidrStrings.stream()
            .map(CidrRange::parse)
            .filter(r -> r != null)
            .collect(Collectors.toList());
    }

    static class CidrRange {
        private final byte[] network;
        private final int prefixLength;

        CidrRange(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static CidrRange parse(String cidr) {
            try {
                String[] parts = cidr.split("/");
                InetAddress addr = InetAddress.getByName(parts[0]);
                int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : (addr.getAddress().length * 8);
                return new CidrRange(addr.getAddress(), prefix);
            } catch (Exception e) {
                LOG.warn("Invalid CIDR range '{}', skipping", cidr);
                return null;
            }
        }

        boolean contains(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            if (addrBytes.length != network.length) {
                return false; // IPv4 vs IPv6 mismatch
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes && i < addrBytes.length; i++) {
                if (addrBytes[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0 && fullBytes < addrBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addrBytes[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
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
