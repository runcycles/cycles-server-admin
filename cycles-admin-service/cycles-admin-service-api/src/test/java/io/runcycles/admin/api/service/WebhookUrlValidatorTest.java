package io.runcycles.admin.api.service;

import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.WebhookSecurityConfigRepository;
import io.runcycles.admin.model.webhook.WebhookSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookUrlValidatorTest {

    @Mock private WebhookSecurityConfigRepository configRepository;
    @InjectMocks private WebhookUrlValidator urlValidator;

    /**
     * Config with blocked CIDRs — used for tests that check private IP blocking.
     * Only use with IP-literal URLs to avoid DNS resolution in test environments.
     */
    private WebhookSecurityConfig configWithBlockedCidrs() {
        return WebhookSecurityConfig.builder()
            .allowHttp(false)
            .blockedCidrRanges(List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8"))
            .build();
    }

    /**
     * Config without blocked CIDRs — avoids DNS resolution, suitable for scheme/pattern tests.
     */
    private WebhookSecurityConfig configNoCidrBlock(boolean allowHttp) {
        return WebhookSecurityConfig.builder()
            .allowHttp(allowHttp)
            .blockedCidrRanges(List.of())
            .build();
    }

    @Test
    void validate_nullUrl_throws() {
        assertThatThrownBy(() -> urlValidator.validate(null))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("URL is required");
    }

    @Test
    void validate_blankUrl_throws() {
        assertThatThrownBy(() -> urlValidator.validate("  "))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("URL is required");
    }

    @Test
    void validate_malformedUrl_throws() {
        // Use lenient since the stub may or may not be reached depending on parse failure path
        lenient().when(configRepository.get()).thenReturn(configNoCidrBlock(false));

        assertThatThrownBy(() -> urlValidator.validate("not a url at all ://"))
            .isInstanceOf(GovernanceException.class);
    }

    @Test
    void validate_httpUrlWithAllowHttpFalse_throws() {
        when(configRepository.get()).thenReturn(configNoCidrBlock(false));

        assertThatThrownBy(() -> urlValidator.validate("http://example.com/webhook"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("HTTPS required");
    }

    @Test
    void validate_httpUrlWithAllowHttpTrue_passes() {
        when(configRepository.get()).thenReturn(configNoCidrBlock(true));

        // Should not throw — no CIDR blocking, no allowed patterns, HTTP permitted
        urlValidator.validate("http://example.com/webhook");
    }

    @Test
    void validate_httpsUrl_passes() {
        when(configRepository.get()).thenReturn(configNoCidrBlock(false));

        // Should not throw — no CIDR blocking, no allowed patterns
        urlValidator.validate("https://example.com/webhook");
    }

    @Test
    void validate_privateIp_localhost_throws() {
        when(configRepository.get()).thenReturn(configWithBlockedCidrs());

        assertThatThrownBy(() -> urlValidator.validate("https://127.0.0.1/webhook"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("private/reserved IP");
    }

    @Test
    void validate_urlNotMatchingAllowedPatterns_throws() {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder()
            .allowHttp(false)
            .blockedCidrRanges(List.of())
            .allowedUrlPatterns(List.of("https://allowed.com/*"))
            .build();
        when(configRepository.get()).thenReturn(config);

        assertThatThrownBy(() -> urlValidator.validate("https://other.com/webhook"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("does not match any allowed pattern");
    }

    @Test
    void validate_urlMatchingAllowedPattern_passes() {
        WebhookSecurityConfig config = WebhookSecurityConfig.builder()
            .allowHttp(false)
            .blockedCidrRanges(List.of())
            .allowedUrlPatterns(List.of("https://allowed.com/*"))
            .build();
        when(configRepository.get()).thenReturn(config);

        // Should not throw
        urlValidator.validate("https://allowed.com/webhook");
    }

    @Test
    void validate_noAllowedPatterns_doesNotFilter() {
        when(configRepository.get()).thenReturn(configNoCidrBlock(false));

        // Should not throw — no patterns means allow all (use example.com — IANA reserved, always resolves)
        urlValidator.validate("https://example.com/webhook");
    }

    @Test
    void matchesGlob_wildcardMatchesAnyPath() {
        assertThat(urlValidator.matchesGlob("https://example.com/webhook/path", "https://example.com/*")).isTrue();
    }

    @Test
    void matchesGlob_exactMatch() {
        assertThat(urlValidator.matchesGlob("https://example.com/webhook", "https://example.com/webhook")).isTrue();
    }

    @Test
    void matchesGlob_noMatch() {
        assertThat(urlValidator.matchesGlob("https://other.com/webhook", "https://example.com/*")).isFalse();
    }

    @Test
    void matchesGlob_dotEscapedProperly() {
        // "example.com" in pattern should not match "exampleXcom"
        assertThat(urlValidator.matchesGlob("https://exampleXcom/webhook", "https://example.com/*")).isFalse();
    }

    @Test
    void validate_privateIp_blockedEvenWithoutCidrConfig() {
        // SSRF fix: private IPs must be blocked even when no CIDR ranges are configured
        when(configRepository.get()).thenReturn(configNoCidrBlock(true));

        assertThatThrownBy(() -> urlValidator.validate("http://127.0.0.1/webhook"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("private/reserved IP");
    }

    @Test
    void validate_ftpScheme_throws() {
        when(configRepository.get()).thenReturn(configNoCidrBlock(true));

        assertThatThrownBy(() -> urlValidator.validate("ftp://example.com/file"))
            .isInstanceOf(GovernanceException.class)
            .hasMessageContaining("Only HTTP(S) URLs are allowed");
    }
}
