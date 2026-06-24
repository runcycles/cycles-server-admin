package io.runcycles.admin.data.logging;

/**
 * Flattens user-controlled values before they reach a log line so embedded
 * {@code CR}/{@code LF} characters cannot forge additional log entries (log
 * injection). Lives in the data module so both the API layer and the
 * repository/data layer share one implementation — the admin plane logs
 * request-derived strings (tenant id, scope, resource keys, exception
 * messages) from both.
 *
 * <p>Returns {@code null} unchanged so it composes with SLF4J's {@code {}}
 * placeholders. Non-strings are rendered via {@link String#valueOf(Object)}.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /**
     * @return the value with {@code \r} and {@code \n} replaced by spaces, or
     *         {@code null} if {@code value} is null.
     */
    public static String safe(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().replace('\r', ' ').replace('\n', ' ');
    }
}
