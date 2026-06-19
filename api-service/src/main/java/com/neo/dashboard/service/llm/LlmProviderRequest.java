package com.neo.dashboard.service.llm;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class LlmProviderRequest {
    private String llmRequestId;
    private String systemPrompt;
    private String userPrompt;
    private String eventId;
    private String evidenceHash;
    private String style;
    private String language;
    private boolean includeRecommendedActions;
    private Map<String, Object> metadata;
}
