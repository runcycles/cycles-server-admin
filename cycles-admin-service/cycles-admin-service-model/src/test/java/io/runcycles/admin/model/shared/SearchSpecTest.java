package io.runcycles.admin.model.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watch-item #3 from review-admin-0-1-25-spec-indexed-dewdrop.md:
 *
 * Locks the validator order to {@code trim → empty-check → length-check}
 * so trailing whitespace cannot bypass the 128-char cap. A regression that
 * reorders these steps (length-check before trim) would let a caller send
 * {@code "x".repeat(128) + "   "} and silently accept it — then the
 * repository layer would see a value whose on-wire length exceeded the
 * spec-mandated cap.
 */
class SearchSpecTest {

    @Test
    void resolve_null_returnsNull() {
        assertNull(SearchSpec.resolve(null));
    }

    @Test
    void resolve_empty_isTreatedAsAbsent() {
        assertNull(SearchSpec.resolve(""));
    }

    @Test
    void resolve_whitespaceOnly_isTreatedAsAbsent() {
        assertNull(SearchSpec.resolve("   "));
        assertNull(SearchSpec.resolve("\t\n "));
    }

    @Test
    void resolve_trimsLeadingAndTrailing() {
        assertEquals("hello", SearchSpec.resolve("  hello  "));
    }

    @Test
    void resolve_exactlyMaxLength_isAccepted() {
        String atCap = "x".repeat(SearchSpec.MAX_LENGTH);
        assertEquals(atCap, SearchSpec.resolve(atCap));
    }

    @Test
    void resolve_overMaxLength_throws400() {
        String over = "x".repeat(SearchSpec.MAX_LENGTH + 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> SearchSpec.resolve(over));
        assertTrue(ex.getMessage().contains("exceeds maxLength"),
            "message should mention 'exceeds maxLength': " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(SearchSpec.MAX_LENGTH)),
            "message should mention the cap: " + ex.getMessage());
    }

    @Test
    void resolve_lengthCheckRunsAfterTrim_trailingWhitespaceCannotBypassCap() {
        // The key invariant: the length gate measures the TRIMMED value,
        // so padding with whitespace cannot inflate an over-cap string
        // into a spurious accept. If the validator re-ordered steps (cap
        // before trim), a caller could pass a value whose raw length is
        // 129+ but trimmed-length is 128 and the repository would see
        // "caller gave us over-cap content dressed in whitespace."
        String underCapPadded = "x".repeat(SearchSpec.MAX_LENGTH) + "     ";
        String resolved = SearchSpec.resolve(underCapPadded);
        assertEquals(SearchSpec.MAX_LENGTH, resolved.length());

        // Conversely, padding an over-cap string still rejects because
        // trim() does NOT chop interior content.
        String overCapPadded = "  " + "y".repeat(SearchSpec.MAX_LENGTH + 1) + "  ";
        assertThrows(IllegalArgumentException.class, () -> SearchSpec.resolve(overCapPadded));
    }

    @Test
    void matches_caseInsensitiveSubstring() {
        assertTrue(SearchSpec.matches("Hello World", "world"));
        assertTrue(SearchSpec.matches("Hello World", "HELLO"));
        assertFalse(SearchSpec.matches("Hello World", "xyz"));
    }

    @Test
    void matches_nullInputs_returnFalseInsteadOfNPE() {
        assertFalse(SearchSpec.matches(null, "x"));
        assertFalse(SearchSpec.matches("hello", null));
        assertFalse(SearchSpec.matches(null, null));
    }

    @Test
    void matches_emptyNeedle_matchesEverything() {
        // String.contains("") is true for any non-null haystack. This is
        // fine because controllers pre-normalise via resolve(), which
        // rejects empty needles before they reach matches().
        assertTrue(SearchSpec.matches("anything", ""));
    }
}
