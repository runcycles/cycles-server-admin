package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;
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
            .filter(s -> s != null)
            .map(CidrRange::parse)
            .filter(r -> r != null)
            .collect(Collectors.toList());
    }

    static class CidrRange {
        private final byte[] network;
        private final int prefixLength;
        private final boolean isIpv4;

        CidrRange(byte[] network, int prefixLength, boolean isIpv4) {
            this.network = network;
            this.prefixLength = prefixLength;
            this.isIpv4 = isIpv4;
        }

        static CidrRange parse(String cidr) {
            try {
                String[] parts = cidr.split("/");
                InetAddress addr = InetAddress.getByName(parts[0]);
                int maxPrefix = addr.getAddress().length * 8;
                int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : maxPrefix;
                if (prefix < 0 || prefix > maxPrefix) {
                    LOG.warn("Invalid prefix length in CIDR '{}', skipping", cidr);
                    return null;
                }
                return new CidrRange(addr.getAddress(), prefix, addr instanceof Inet4Address);
            } catch (Exception e) {
                LOG.warn("Invalid CIDR range '{}', skipping", cidr);
                return null;
            }
        }

        boolean contains(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            // Handle IPv4-mapped IPv6 addresses (::ffff:x.x.x.x) against IPv4 CIDR ranges
            if (isIpv4 && address instanceof Inet6Address && addrBytes.length == 16) {
                if (isIpv4Mapped(addrBytes)) {
                    addrBytes = new byte[] { addrBytes[12], addrBytes[13], addrBytes[14], addrBytes[15] };
                } else {
                    return false;
                }
            }
            if (addrBytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addrBytes[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isIpv4Mapped(byte[] ipv6Bytes) {
            // ::ffff:x.x.x.x — bytes 0-9 are 0, bytes 10-11 are 0xFF
            for (int i = 0; i < 10; i++) {
                if (ipv6Bytes[i] != 0) return false;
            }
            return ipv6Bytes[10] == (byte) 0xFF && ipv6Bytes[11] == (byte) 0xFF;
        }
    }

    private static final Pattern GLOB_META = Pattern.compile("[+?()\\[\\]{}|^$\\\\]");

    boolean matchesGlob(String url, String pattern) {
        // Escape all regex metacharacters except * and ., then convert glob wildcards
        String escaped = GLOB_META.matcher(pattern).replaceAll("\\\\$0");
        String regex = escaped.replace(".", "\\.").replace("*", ".*");
        try {
            return url.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }
}
