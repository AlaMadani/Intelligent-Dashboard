package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.TextNormalization;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SequenceValueNormalizer {

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
