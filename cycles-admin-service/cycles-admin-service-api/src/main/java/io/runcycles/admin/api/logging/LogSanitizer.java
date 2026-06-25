package io.runcycles.admin.api.logging;

/**
 * Thin API-layer alias for {@link io.runcycles.admin.data.logging.LogSanitizer}
 * so existing controller/service call sites keep using {@code LogSanitizer.safe}
 * while the single implementation lives in the data module (shared with the
 * repository layer).
 */
public final class LogSanitizer {
    private LogSanitizer() {}

    public static String safe(Object value) {
        return io.runcycles.admin.data.logging.LogSanitizer.safe(value);
    }
}
