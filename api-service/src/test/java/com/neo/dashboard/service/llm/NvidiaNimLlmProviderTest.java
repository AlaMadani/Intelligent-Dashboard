package com.neo.dashboard.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NvidiaNimLlmProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private NvidiaNimLlmProvider provider;

    @BeforeEach
    void setUp() {
        provider = new NvidiaNimLlmProvider(objectMapper);
        ReflectionTestUtils.setField(provider, "apiKey", "test-key");
        ReflectionTestUtils.setField(provider, "model", "nvidia/nemotron-3-super-120b-a12b");
        ReflectionTestUtils.setField(provider, "temperature", 1.0);
        ReflectionTestUtils.setField(provider, "topP", 0.95);
        ReflectionTestUtils.setField(provider, "maxTokens", 128);
    }

    /**
     * Provider Name Is Nvidia Nim
     */
    @Test
    void providerNameIsNvidiaNim() {
        assertThat(provider.providerName()).isEqualTo("nvidia-nim");
    }

    /**
     * Is Configured Returns False When Key Missing
     */
    @Test
    void isConfiguredReturnsFalseWhenKeyMissing() {
        ReflectionTestUtils.setField(provider, "apiKey", "");
        assertThat(provider.isConfigured()).isFalse();
    }

    /**
     * Is Configured Returns True When Key Present
     */
    @Test
    void isConfiguredReturnsTrueWhenKeyPresent() {
        ReflectionTestUtils.setField(provider, "apiKey", "test-key");
        assertThat(provider.isConfigured()).isTrue();
    }

    /**
     * Generate Returns Not Configured Error When Key Missing
     */
    @Test
    void generateReturnsNotConfiguredErrorWhenKeyMissing() {
        ReflectionTestUtils.setField(provider, "apiKey", "");
        ReflectionTestUtils.setField(provider, "model", "test-model");

        LlmProviderRequest request = LlmProviderRequest.builder().eventId("evt-1").build();
        LlmProviderResponse response = provider.generate(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NOT_CONFIGURED");
        assertThat(response.getProvider()).isEqualTo("nvidia-nim");
        assertThat(response.getModel()).isEqualTo("test-model");
    }

    /**
     * Minimal Request Body Matches Power Shell Shape
     */
    @Test
    void minimalRequestBodyMatchesPowerShellShape() {
        ReflectionTestUtils.setField(provider, "minimalRequestMode", true);
        ReflectionTestUtils.setField(provider, "includeExtraBody", false);
        ReflectionTestUtils.setField(provider, "enableThinking", false);
        ReflectionTestUtils.setField(provider, "reasoningBudget", 128);
        ReflectionTestUtils.setField(provider, "maxTokens", 128);

        LlmProviderRequest request = LlmProviderRequest.builder().eventId("evt-1").build();
        Map<String, Object> body = provider.buildRequestBody(request);

        assertThat(body.get("model")).isEqualTo("nvidia/nemotron-3-super-120b-a12b");
        assertThat(body.get("messages")).isNotNull();

        List<?> messages = (List<?>) body.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("role")).isEqualTo("user");
        assertThat(((Map<?, ?>) messages.get(0)).get("content")).isEqualTo("Say hello in one sentence.");

        assertThat(body.get("temperature")).isEqualTo(1.0);
        assertThat(body.get("top_p")).isEqualTo(0.95);
        assertThat(body.get("max_tokens")).isEqualTo(128);
        assertThat(body.get("stream")).isEqualTo(false);
    }

    /**
     * Minimal Request Body Exact Key Set
     */
    @Test
    void minimalRequestBodyExactKeySet() {
        ReflectionTestUtils.setField(provider, "minimalRequestMode", true);
        ReflectionTestUtils.setField(provider, "includeExtraBody", false);

        LlmProviderRequest request = LlmProviderRequest.builder().eventId("evt-1").build();
        Map<String, Object> body = provider.buildRequestBody(request);

        assertThat(body.keySet()).containsExactly("model", "messages", "temperature", "top_p", "max_tokens", "stream");
    }

    /**
     * Minimal Request Body Excludes All Unsupported Fields
     */
    @Test
    void minimalRequestBodyExcludesAllUnsupportedFields() {
        ReflectionTestUtils.setField(provider, "minimalRequestMode", true);
        ReflectionTestUtils.setField(provider, "includeExtraBody", true);
        ReflectionTestUtils.setField(provider, "enableThinking", true);
        ReflectionTestUtils.setField(provider, "reasoningBudget", 4096);

        LlmProviderRequest request = LlmProviderRequest.builder().eventId("evt-1").build();
        Map<String, Object> body = provider.buildRequestBody(request);

        assertThat(body.containsKey("model")).isTrue();
        assertThat(body.containsKey("extra_body")).isFalse();
        assertThat(body.containsKey("reasoning_budget")).isFalse();
        assertThat(body.containsKey("chat_template_kwargs")).isFalse();
        assertThat(body.containsKey("response_format")).isFalse();
        assertThat(body.containsKey("tools")).isFalse();
        assertThat(body.containsKey("tool_choice")).isFalse();
        assertThat(body.containsKey("stream_options")).isFalse();
        assertThat(body.containsKey("modelName")).isFalse();
        assertThat(body.containsKey("request")).isFalse();
        assertThat(body.containsKey("payload")).isFalse();
        assertThat(body.containsKey("body")).isFalse();
        assertThat(body.containsKey("input")).isFalse();
    }

    /**
     * Minimal Request Body Serializes To Expected Json
     */
    @Test
    void minimalRequestBodySerializesToExpectedJson() throws Exception {
        ReflectionTestUtils.setField(provider, "minimalRequestMode", true);
        ReflectionTestUtils.setField(provider, "includeExtraBody", false);
        ReflectionTestUtils.setField(provider, "maxTokens", 128);

        LlmProviderRequest request = LlmProviderRequest.builder().eventId("evt-1").build();
        Map<String, Object> body = provider.buildRequestBody(request);

        String json = objectMapper.writeValueAsString(body);
        JsonNode parsed = objectMapper.readTree(json);

        assertThat(parsed.get("model").asText()).isEqualTo("nvidia/nemotron-3-super-120b-a12b");
        assertThat(parsed.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(parsed.get("messages").get(0).get("content").asText()).isEqualTo("Say hello in one sentence.");
        assertThat(parsed.get("temperature").asDouble()).isEqualTo(1.0);
        assertThat(parsed.get("top_p").asDouble()).isEqualTo(0.95);
        assertThat(parsed.get("max_tokens").asInt()).isEqualTo(128);
        assertThat(parsed.get("stream").asBoolean()).isFalse();

        assertThat(parsed.has("extra_body")).isFalse();
        assertThat(parsed.has("reasoning_budget")).isFalse();
        assertThat(parsed.has("chat_template_kwargs")).isFalse();
    }

    /**
     * Normal Mode With Extra Body False Excludes Unsupported Fields
     */
    @Test
    void normalModeWithExtraBodyFalseExcludesUnsupportedFields() {
        ReflectionTestUtils.setField(provider, "minimalRequestMode", false);
        ReflectionTestUtils.setField(provider, "includeExtraBody", false);

        LlmProviderRequest request = LlmProviderRequest.builder()
                .systemPrompt("system prompt")
                .userPrompt("user prompt")
                .eventId("evt-1")
                .build();
        Map<String, Object> body = provider.buildRequestBody(request);

        assertThat(body.containsKey("model")).isTrue();
        assertThat(body.containsKey("extra_body")).isFalse();
        assertThat(body.containsKey("reasoning_budget")).isFalse();
        assertThat(body.containsKey("chat_template_kwargs")).isFalse();
        assertThat(body.containsKey("response_format")).isFalse();
        assertThat(body.containsKey("tools")).isFalse();
        assertThat(body.containsKey("tool_choice")).isFalse();
    }

    /**
     * Normal Mode With Extra Body True Contains Top Level Fields
     */
    @Test
    void normalModeWithExtraBodyTrueContainsTopLevelFields() {
        ReflectionTestUtils.setField(provider, "minimalRequestMode", false);
        ReflectionTestUtils.setField(provider, "includeExtraBody", true);
        ReflectionTestUtils.setField(provider, "enableThinking", true);
        ReflectionTestUtils.setField(provider, "reasoningBudget", 4096);

        LlmProviderRequest request = LlmProviderRequest.builder()
                .systemPrompt("system")
                .userPrompt("user")
                .eventId("evt-1")
                .build();
        Map<String, Object> body = provider.buildRequestBody(request);

        assertThat(body.containsKey("reasoning_budget")).isTrue();
        assertThat(body.get("reasoning_budget")).isEqualTo(4096);
        assertThat(body.containsKey("chat_template_kwargs")).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> kwargs = (Map<String, Object>) body.get("chat_template_kwargs");
        assertThat(kwargs.get("enable_thinking")).isEqualTo(true);

        assertThat(body.containsKey("extra_body")).isFalse();
    }

    /**
     * Normal Mode Has System And User Messages
     */
    @Test
    void normalModeHasSystemAndUserMessages() {
        ReflectionTestUtils.setField(provider, "minimalRequestMode", false);
        ReflectionTestUtils.setField(provider, "includeExtraBody", false);

        LlmProviderRequest request = LlmProviderRequest.builder()
                .systemPrompt("You are a security analyst.")
                .userPrompt("Evidence payload here")
                .eventId("evt-1")
                .build();
        Map<String, Object> body = provider.buildRequestBody(request);

        List<?> messages = (List<?>) body.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(((Map<?, ?>) messages.get(0)).get("role")).isEqualTo("system");
        assertThat(((Map<?, ?>) messages.get(0)).get("content")).isEqualTo("You are a security analyst.");
        assertThat(((Map<?, ?>) messages.get(1)).get("role")).isEqualTo("user");
        assertThat(((Map<?, ?>) messages.get(1)).get("content")).isEqualTo("Evidence payload here");
    }

    /**
     * Response Body Extracted From Web Client Response Exception
     */
    @Test
    void responseBodyExtractedFromWebClientResponseException() throws Exception {
        WebClientResponseException ex = WebClientResponseException.create(
                400, "Bad Request", HttpHeaders.EMPTY,
                "{\"error\":\"invalid request\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8, null);

        String body = invokeExtractNvidiaResponseBody(ex);
        assertThat(body).contains("invalid request");
    }

    /**
     * Response Body Extraction Handles Non Http Exception
     */
    @Test
    void responseBodyExtractionHandlesNonHttpException() throws Exception {
        String body = invokeExtractNvidiaResponseBody(new RuntimeException("network error"));
        assertThat(body).isEqualTo("(no response body)");
    }

    /**
     * Extract Null Response Body Returns No Body
     */
    @Test
    void extractNullResponseBodyReturnsNoBody() throws Exception {
        String body = invokeExtractNvidiaResponseBody(null);
        assertThat(body).isEqualTo("(no response body)");
    }

    /**
     * Truncate Returns Full String When Under Limit
     */
    @Test
    void truncateReturnsFullStringWhenUnderLimit() throws Exception {
        String result = invokeTruncate("short text", 100);
        assertThat(result).isEqualTo("short text");
    }

    /**
     * Truncate Returns Truncated String When Over Limit
     */
    @Test
    void truncateReturnsTruncatedStringWhenOverLimit() throws Exception {
        String result = invokeTruncate("a".repeat(3000), 2000);
        assertThat(result).hasSize(2003);
        assertThat(result).endsWith("...");
    }

    /**
     * Truncate Returns Empty For Null
     */
    @Test
    void truncateReturnsEmptyForNull() throws Exception {
        String result = invokeTruncate(null, 2000);
        assertThat(result).isEmpty();
    }

    /**
     * Response Text Extraction From Choices
     */
    @Test
    void responseTextExtractionFromChoices() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Generated explanation text"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 50,
                    "completion_tokens": 100,
                    "total_tokens": 150
                  }
                }
                """);

        Optional<String> text = extractText(provider, response);
        assertThat(text).isPresent();
        assertThat(text.orElseThrow()).isEqualTo("Generated explanation text");
    }

    /**
     * Response Text With Reasoning Content Returns Content Only
     */
    @Test
    void responseTextWithReasoningContentReturnsContentOnly() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Hello!",
                        "reasoning": "...reasoning text...",
                        "reasoning_content": "...reasoning content..."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 22,
                    "completion_tokens": 20,
                    "total_tokens": 42
                  }
                }
                """);

        Optional<String> text = extractText(provider, response);
        assertThat(text).isPresent();
        assertThat(text.orElseThrow()).isEqualTo("Hello!");
    }

    /**
     * Response Text Extraction From Empty Choices
     */
    @Test
    void responseTextExtractionFromEmptyChoices() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "choices": []
                }
                """);

        Optional<String> text = extractText(provider, response);
        assertThat(text).isEmpty();
    }

    /**
     * Response Text Extraction From Missing Choices
     */
    @Test
    void responseTextExtractionFromMissingChoices() throws Exception {
        JsonNode response = objectMapper.readTree("{}");

        Optional<String> text = extractText(provider, response);
        assertThat(text).isEmpty();
    }

    /**
     * Response Text Extraction From Missing Content
     */
    @Test
    void responseTextExtractionFromMissingContent() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "choices": [
                    {
                      "message": {}
                    }
                  ]
                }
                """);

        Optional<String> text = extractText(provider, response);
        assertThat(text).isEmpty();
    }

    private Optional<String> extractText(NvidiaNimLlmProvider p, JsonNode response) {
        try {
            var method = NvidiaNimLlmProvider.class.getDeclaredMethod("extractText", JsonNode.class);
            method.setAccessible(true);
            return Optional.ofNullable((String) method.invoke(p, response));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String invokeExtractNvidiaResponseBody(Throwable t) throws Exception {
        var method = NvidiaNimLlmProvider.class.getDeclaredMethod("extractNvidiaResponseBody", Throwable.class);
        method.setAccessible(true);
        return (String) method.invoke(provider, t);
    }

    private String invokeTruncate(String s, int max) throws Exception {
        var method = NvidiaNimLlmProvider.class.getDeclaredMethod("truncate", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(provider, s, max);
    }

    /**
     * Successful Raw Response Parse
     */
    @Test
    void successfulRawResponseParse() {
        String rawBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Hello!",
                        "reasoning": "internal reasoning",
                        "reasoning_content": "internal reasoning"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 22,
                    "completion_tokens": 20,
                    "total_tokens": 42
                  }
                }
                """;
        LlmProviderResponse response = provider.parseRawResponse(
                rawBody, "evt-1", "https://example.com/chat/completions", Instant.now(), "test-request-id");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getText()).isEqualTo("Hello!");
        assertThat(response.getPromptTokens()).isEqualTo(22);
        assertThat(response.getCompletionTokens()).isEqualTo(20);
        assertThat(response.getTotalTokens()).isEqualTo(42);
        assertThat(response.getProvider()).isEqualTo("nvidia-nim");
    }

    /**
     * Invalid Response Json Returns Invalid Response Error
     */
    @Test
    void invalidResponseJsonReturnsInvalidResponseError() {
        LlmProviderResponse response = provider.parseRawResponse(
                "not JSON", "evt-1", "https://example.com/chat/completions", Instant.now(), "test-request-id");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INVALID_RESPONSE");
    }

    /**
     * Empty Raw Body Returns Invalid Response Error
     */
    @Test
    void emptyRawBodyReturnsInvalidResponseError() {
        LlmProviderResponse response = provider.parseRawResponse(
                "", "evt-1", "https://example.com/chat/completions", Instant.now(), "test-request-id");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("INVALID_RESPONSE");
    }

    /**
     * Missing Content Returns Empty Content Error
     */
    @Test
    void missingContentReturnsEmptyContentError() {
        String rawBody = """
                {
                  "choices": [
                    {
                      "message": {}
                    }
                  ]
                }
                """;
        LlmProviderResponse response = provider.parseRawResponse(
                rawBody, "evt-1", "https://example.com/chat/completions", Instant.now(), "test-request-id");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("EMPTY_CONTENT");
    }

    /**
     * Empty Choices Array Returns Empty Content Error
     */
    @Test
    void emptyChoicesArrayReturnsEmptyContentError() {
        String rawBody = """
                {
                  "choices": []
                }
                """;
        LlmProviderResponse response = provider.parseRawResponse(
                rawBody, "evt-1", "https://example.com/chat/completions", Instant.now(), "test-request-id");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("EMPTY_CONTENT");
    }

    /**
     * Missing Usage Returns Null Tokens
     */
    @Test
    void missingUsageReturnsNullTokens() {
        String rawBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Hello!"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;
        LlmProviderResponse response = provider.parseRawResponse(
                rawBody, "evt-1", "https://example.com/chat/completions", Instant.now(), "test-request-id");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getText()).isEqualTo("Hello!");
        assertThat(response.getPromptTokens()).isNull();
        assertThat(response.getCompletionTokens()).isNull();
        assertThat(response.getTotalTokens()).isNull();
    }

}
