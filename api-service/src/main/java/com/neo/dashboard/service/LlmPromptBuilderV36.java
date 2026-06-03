package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LlmPromptBuilderV36 {

    private final ObjectMapper objectMapper;

    @Value("${app.v36.explanations.max-evidence-size-kb:128}")
    private int maxEvidenceSizeKb;

    public String buildPrompt(JsonNode evidence, String style, String language, boolean includeRecommendedActions) {
        return """
                You are a security analyst assistant for an insurance API command center.
                Use only the provided V3.6.1 evidence payload. Do not invent facts, users, scores, models, or rules.
                If evidence is insufficient, say which evidence is missing.
                The result is an analyst aid, not a final determination.
                Language: %s
                Style: %s
                Include recommended actions: %s

                Return concise markdown with these sections:
                ## Summary
                ## Evidence
                ## Possible interpretation
                ## Recommended actions
                ## Limits

                V3.6.1 evidence payload:
                %s
                """.formatted(
                safe(language, "en"),
                safe(style, "security_analyst"),
                includeRecommendedActions,
                trimEvidence(evidence)
        );
    }

    private String trimEvidence(JsonNode evidence) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);
            int maxChars = Math.max(maxEvidenceSizeKb, 1) * 1024;
            return json.length() <= maxChars ? json : json.substring(0, maxChars) + "\n...<evidence truncated by api-service limit>";
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
