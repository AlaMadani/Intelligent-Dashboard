package com.noveocare.dataprocessor.ai.sequence;

public enum SequenceModelKind {
    TRANSFORMER,
    TCN,
    RULES_ONLY;

    public static SequenceModelKind from(String value, SequenceModelKind fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "transformer" -> TRANSFORMER;
            case "tcn" -> TCN;
            case "rules_only", "rules-only" -> RULES_ONLY;
            default -> fallback;
        };
    }
}
