package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.TextNormalization;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalises raw categorical values for vocabulary lookup by generating
 * candidate forms (trimmed, upper/lower, repaired mojibake) and field-specific
 * mappings (e.g. OK/KO for status).
 */
@Component
public class SequenceValueNormalizer {

    /* ========== Public API ========== */

    /* Returns the best matching vocabulary key, or the repaired raw value if not found. */
    public String normalizeForVocabulary(String field, String rawValue, Map<String, Integer> vocabulary) {
        if (rawValue == null || rawValue.isBlank() || vocabulary == null || vocabulary.isEmpty()) {
            return null;
        }
        for (String candidate : candidates(field, rawValue)) {
            if (vocabulary.containsKey(candidate)) {
                return candidate;
            }
        }
        return TextNormalization.normalizeLabel(rawValue);
    }

    /* ========== Private helpers ========== */

    /* Generates a set of candidate strings for vocabulary matching. */
    private Set<String> candidates(String field, String rawValue) {
        String repaired = TextNormalization.normalizeLabel(rawValue);
        String trimmed = repaired == null ? "" : repaired.trim();
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rawValue);
        candidates.add(trimmed);
        candidates.add(trimmed.toLowerCase(Locale.ROOT));
        candidates.add(trimmed.toUpperCase(Locale.ROOT));
        if ("status".equals(field)) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.equals("SUCCESS") || upper.equals("SUCCEEDED") || upper.equals("200")) {
                candidates.add("OK");
                candidates.add("ok");
            }
            if (upper.equals("FAILURE") || upper.equals("FAILED") || upper.equals("ERROR")) {
                candidates.add("KO");
                candidates.add("ko");
            }
        }
        return candidates;
    }
}
