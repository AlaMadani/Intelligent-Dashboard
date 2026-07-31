package com.neo.dashboard.service.llm;

/**
 * Contract that every LLM provider (Gemini, NVIDIA NIM, etc.) must implement.
 * Defines the two operations the LLM service layer relies on:
 * <ul>
 *   <li>sending a prompt and receiving a structured response</li>
 *   <li>identifying which provider handled the request</li>
 * </ul>
 */
public interface LlmProvider {

    /**
     * Sends the given request to the LLM and returns a structured response
     * that includes the generated text, token usage, latency, and success status.
     *
     * @param request the prompt payload together with metadata (eventId, style, language, etc.)
     * @return a response object that always includes at least success/failure and latency
     */
    LlmProviderResponse generate(LlmProviderRequest request);

    /**
     * Returns a unique, human-readable name for this provider (e.g. {@code "gemini"},
     * {@code "nvidia-nim"}) used in logging, metrics, and error responses.
     */
    String providerName();
}
