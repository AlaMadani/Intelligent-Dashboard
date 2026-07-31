package com.noveocare.dataprocessor.ai;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Repairs common mojibake artifacts found in notebook-generated resources and
 * exposes a stable comparison key for rule checks.
 */
public final class TextNormalization {

    /* ---- Known corruption markers ---- */
    private static final List<String> SUSPICIOUS_TOKENS = List.of(
            "\u00C3", // Ã
            "\u00C2", // Â
            "\uFFFD"  // replacement character
    );

    private TextNormalization() {
    }

    /* ========== Public API ========== */

    /* Repairs mojibake via ISO-8859-1 -> UTF-8 round-trip (max 3 attempts). */
    public static String normalizeLabel(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (int attempt = 0; attempt < 3 && looksCorrupted(normalized); attempt++) {
            String repaired = new String(
                    normalized.getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8)
                    .trim();
            if (repaired.equals(normalized)) {
                break;
            }
            normalized = repaired;
        }
        return normalized;
    }

    /* Produces an ASCII-folded, lower-case key for stable comparisons. */
    public static String comparisonKey(String value) {
        String normalized = normalizeLabel(value);
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        String asciiFolded = Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('\u2019', '\'');
        return asciiFolded.toLowerCase(Locale.ROOT).trim();
    }

    /* Checks equality using the comparison key of both strings. */
    public static boolean equalsNormalized(String left, String right) {
        return comparisonKey(left).equals(comparisonKey(right));
    }

    /* Detects corruption markers that indicate mojibake. */
    private static boolean looksCorrupted(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String token : SUSPICIOUS_TOKENS) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
