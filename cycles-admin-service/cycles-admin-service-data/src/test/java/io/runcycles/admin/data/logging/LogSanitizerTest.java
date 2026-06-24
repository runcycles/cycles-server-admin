package io.runcycles.admin.data.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogSanitizer")
class LogSanitizerTest {

    @Test
    void nullStaysNull() {
        assertThat(LogSanitizer.safe(null)).isNull();
    }

    @Test
    void cleanValueUnchanged() {
        assertThat(LogSanitizer.safe("tenant:acme/app:web")).isEqualTo("tenant:acme/app:web");
    }

    @Test
    @DisplayName("CR, LF, and CRLF are all flattened to spaces (blocks log forging)")
    void newlinesFlattened() {
        assertThat(LogSanitizer.safe("acme\nERROR fake line")).isEqualTo("acme ERROR fake line");
        assertThat(LogSanitizer.safe("a\rb")).isEqualTo("a b");
        assertThat(LogSanitizer.safe("a\r\nb")).isEqualTo("a  b");
    }

    @Test
    void nonStringRenderedViaToString() {
        assertThat(LogSanitizer.safe(42)).isEqualTo("42");
    }
}
