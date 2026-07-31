package com.neo.dashboard.service.llm;

import lombok.Builder;
import lombok.Data;

/**
 * Immutable data class representing the result of an LLM provider invocation.
 * Every response, whether successful or not, includes the provider name, model,
 * and wall-clock latency so callers can log and monitor consistently.
 */
@Data
@Builder
public class LlmProviderResponse {
    /** Name of the provider that handled the request (e.g. "gemini", "nvidia-nim"). */
    private String provider;
    /** Model identifier used for generation (e.g. "gemini-2.5-flash"). */
    private String model;
    /** The generated text content — present only when {@code success == true}. */
    private String text;
    /** Whether the LLM call completed and produced usable output. */
    private boolean success;

    /** Number of tokens in the prompt (may be {@code null} if the API omits it). */
    private Integer promptTokens;
    /** Number of tokens in the generated completion (may be {@code null}). */
    private Integer completionTokens;
    /** Total tokens consumed by the request (may be {@code null}). */
    private Integer totalTokens;

    /** Machine-readable error code for failed calls (e.g. "NOT_CONFIGURED", "HTTP_429"). */
    private String errorCode;
    /** Human-readable error description for failed calls. */
    private String errorMessage;

    /** Reason the generation stopped (e.g. "stop", "length") — from the API finish_reason field. */
    private String finishReason;
    /** Tracks the originating request, copied from {@link LlmProviderRequest#llmRequestId}. */
    private String llmRequestId;

    /** Wall-clock duration of the provider call in milliseconds. */
    private long latencyMs;
}
