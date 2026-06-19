package com.neo.dashboard.service.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmProviderResponse {
    private String provider;
    private String model;
    private String text;
    private boolean success;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    private String errorCode;
    private String errorMessage;

    private String finishReason;
    private String llmRequestId;

    private long latencyMs;
}
