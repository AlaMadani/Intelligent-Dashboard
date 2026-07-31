package com.neo.dashboard.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * LLM provider implementation for NVIDIA NIM (NVIDIA Inference Microservice).
 * Supports two HTTP backends (reactive {@link WebClient} and JDK {@link HttpClient})
 * and is configurable through numerous {@code app.llm.nvidia.*} properties.
 * <p>
 * Registered as Spring bean named {@code "nvidia-nim"}.
 */
@Component("nvidia-nim")
@Slf4j
public class NvidiaNimLlmProvider implements LlmProvider {

    /** Reactive WebClient used when {@link #httpClientType} is not {@code "jdk"}. */
    private final WebClient webClient;

    /** Jackson object mapper for serialising request bodies and parsing responses. */
    private final ObjectMapper objectMapper;

    /** Base URL of the NVIDIA NIM API — defaults to the hosted integrate endpoint. */
    @Value("${app.llm.nvidia.base-url:https://integrate.api.nvidia.com/v1}")
    private String baseUrl;

    /** API key for authenticating with NVIDIA NIM. */
    @Value("${app.llm.nvidia.api-key:}")
    private String apiKey;

    /** Model identifier to use (e.g. {@code nvidia/nemotron-3-super-120b-a12b}). */
    @Value("${app.llm.nvidia.model:nvidia/nemotron-3-super-120b-a12b}")
    private String model;

    /** Sampling temperature — higher values produce more random outputs. */
    @Value("${app.llm.nvidia.temperature:1.0}")
    private double temperature;

    /** Nucleus sampling top-p parameter. */
    @Value("${app.llm.nvidia.top-p:0.95}")
    private double topP;

    /** Maximum number of tokens to generate in the response. */
    @Value("${app.llm.nvidia.max-tokens:128}")
    private int maxTokens;

    /** Whether to ask the model to show its reasoning/thought process. */
    @Value("${app.llm.nvidia.enable-thinking:false}")
    private boolean enableThinking;

    /** Token budget allocated for the model's internal reasoning. */
    @Value("${app.llm.nvidia.reasoning-budget:0}")
    private int reasoningBudget;

    /** When {@code true}, the reasoning budget field is sent even when its value is zero. */
    @Value("${app.llm.nvidia.send-reasoning-budget-when-zero:false}")
    private boolean sendReasoningBudgetWhenZero;

    /** Request timeout in milliseconds. */
    @Value("${app.llm.nvidia.timeout-ms:120000}")
    private long timeoutMs;

    /** When {@code true}, extra body fields (reasoning_budget, chat_template_kwargs) are included. */
    @Value("${app.llm.nvidia.include-extra-body:false}")
    private boolean includeExtraBody;

    /** When {@code true}, only a trivial "Say hello" prompt is sent — useful for connectivity tests. */
    @Value("${app.llm.nvidia.minimal-request-mode:true}")
    private boolean minimalRequestMode;

    /** When {@code true}, the full JSON request body is logged at INFO level. */
    @Value("${app.llm.nvidia.log-request-json:true}")
    private boolean logRequestJson;

    /** Maximum characters of the request body to include in log output (excess is truncated). */
    @Value("${app.llm.nvidia.log-request-max-chars:4000}")
    private int logRequestMaxChars;

    /** Selects the HTTP client implementation: {@code "webclient"} (default) or {@code "jdk"}. */
    @Value("${app.llm.nvidia.http-client:webclient}")
    private String httpClientType;

    public NvidiaNimLlmProvider(ObjectMapper objectMapper) {
        /* Build a vanilla WebClient; the full URL is constructed per-request. */
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector())
                .build();
        this.objectMapper = objectMapper;
    }

    /** Logs provider-selection details at startup so operators can verify the configuration. */
    @PostConstruct
    void logProviderSelected() {
        String fullUrl = baseUrl + "/chat/completions";
        boolean keyPresent = apiKey != null && !apiKey.isBlank();
        int keyLength = keyPresent ? apiKey.length() : 0;
        boolean keyPrefixValid = keyPresent && apiKey.startsWith("nvapi-");
        log.info("LLM_PROVIDER_SELECTED provider=nvidia-nim model={} baseUrl={} fullUrl={} " +
                        "minimalMode={} includeExtraBody={} maxTokens={} logRequestJson={} " +
                        "httpClient={} enableThinking={} reasoningBudget={} sendReasoningBudgetWhenZero={} " +
                        "apiKeyPresent={} apiKeyLength={} apiKeyPrefixValid={}",
                model, baseUrl, fullUrl, minimalRequestMode, includeExtraBody, maxTokens,
                logRequestJson, httpClientType, enableThinking, reasoningBudget, sendReasoningBudgetWhenZero,
                keyPresent, keyLength, keyPrefixValid);
    }

    @Override
    public LlmProviderResponse generate(LlmProviderRequest request) {
        /* Short-circuit when no API key has been provided. */
        if (!isConfigured()) {
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("NOT_CONFIGURED")
                    .errorMessage("NVIDIA NIM API key not configured. Set app.llm.nvidia.api-key or NVIDIA_API_KEY.")
                    .build();
        }

        /* Record start time for latency calculation. */
        Instant start = Instant.now();
        String fullUrl = baseUrl + "/chat/completions";
        String llmRequestId = request.getLlmRequestId();
        String eventId = request.getEventId();
        logDiagnostics(fullUrl, eventId, llmRequestId);

        try {
            /* Build the request map, log it, then serialise to JSON for the HTTP call. */
            Map<String, Object> body = buildRequestBody(request);
            logRequestBody(body, request, llmRequestId);
            String jsonBody = objectMapper.writeValueAsString(body);

            /* Dispatch using the configured HTTP client. */
            String rawBody = "jdk".equals(httpClientType)
                    ? sendWithJdkHttpClient(jsonBody, fullUrl, eventId, start)
                    : sendWithWebClient(body, fullUrl, eventId, start);

            /* Parse the raw response string into a structured LlmProviderResponse. */
            return parseRawResponse(rawBody, eventId, fullUrl, start, llmRequestId);

        } catch (WebClientResponseException e) {
            /* The API returned a non-2xx HTTP status — extract and log the response body. */
            long ms = Duration.between(start, Instant.now()).toMillis();
            int statusCode = e.getStatusCode().value();
            String responseBody = extractNvidiaResponseBody(e);
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass={} exceptionMessage=\"{}\" statusCode={} responseBody=\"{}\" fullUrl={}",
                    model, llmRequestId, eventId,
                    e.getClass().getName(), sanitize(e.getMessage()), statusCode,
                    truncate(responseBody, 2000), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("HTTP_" + statusCode)
                    .errorMessage("NVIDIA NIM returned HTTP " + statusCode + ": " + truncate(responseBody, 500))
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();

        } catch (WebClientRequestException e) {
            /* Connection-level failure (DNS, refused, etc.) before any HTTP response. */
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass={} exceptionMessage=\"{}\" statusCode=n/a responseBody=n/a fullUrl={}",
                    model, llmRequestId, eventId,
                    e.getClass().getName(), sanitize(e.getMessage()), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("TRANSPORT_ERROR")
                    .errorMessage("NVIDIA NIM transport error: " + sanitize(e.getMessage()))
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();

        } catch (SSLException e) {
            /* TLS handshake failure (certificate issues, protocol mismatch, etc.). */
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass={} exceptionMessage=\"{}\" statusCode=n/a responseBody=n/a fullUrl={}",
                    model, llmRequestId, eventId,
                    e.getClass().getName(), sanitize(e.getMessage()), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("TLS_ERROR")
                    .errorMessage("NVIDIA NIM TLS error: " + sanitize(e.getMessage()))
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();

        } catch (java.io.IOException e) {
            /* Generic I/O error from the JDK HttpClient path. */
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass={} exceptionMessage=\"{}\" statusCode=n/a responseBody=n/a fullUrl={}",
                    model, llmRequestId, eventId,
                    e.getClass().getName(), sanitize(e.getMessage()), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("IO_ERROR")
                    .errorMessage("NVIDIA NIM I/O error: " + sanitize(e.getMessage()))
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();

        } catch (InterruptedException e) {
            /* The calling thread was interrupted while blocked on the JDK HttpClient call. */
            Thread.currentThread().interrupt();
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass={} exceptionMessage=\"{}\" statusCode=n/a responseBody=n/a fullUrl={}",
                    model, llmRequestId, eventId,
                    e.getClass().getName(), sanitize(e.getMessage()), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("INTERRUPTED")
                    .errorMessage("NVIDIA NIM request was interrupted")
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();

        } catch (Exception e) {
            /* Catch-all for any unexpected exception type. */
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass={} exceptionMessage=\"{}\" statusCode=n/a responseBody=n/a fullUrl={}",
                    model, llmRequestId, eventId,
                    e.getClass().getName(), sanitize(e.getMessage()), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("EXCEPTION")
                    .errorMessage(e.getClass().getSimpleName() + ": " + sanitize(e.getMessage()))
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();
        }
    }

    /**
     * Parses the raw JSON response body from NVIDIA NIM into an {@link LlmProviderResponse}.
     * Handles empty or invalid JSON payloads and extracts token usage + finish reason.
     */
    LlmProviderResponse parseRawResponse(String rawBody, String eventId, String fullUrl, Instant start, String llmRequestId) {
        /* Reject empty responses immediately. */
        if (rawBody == null || rawBody.isBlank()) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass=INVALID_PARSE exceptionMessage=\"NVIDIA response body is empty\" " +
                            "statusCode=INVALID_RESPONSE rawBody=\"{}\" fullUrl={}",
                    model, llmRequestId, eventId,
                    truncate(rawBody, 2000), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("INVALID_RESPONSE")
                    .errorMessage("NVIDIA NIM returned empty response body")
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();
        }

        /* Attempt to deserialise the response body as JSON. */
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass=INVALID_PARSE exceptionMessage=\"Failed to parse NVIDIA response as JSON\" " +
                            "statusCode=INVALID_RESPONSE rawBody=\"{}\" fullUrl={}",
                    model, llmRequestId, eventId,
                    truncate(rawBody, 2000), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("INVALID_RESPONSE")
                    .errorMessage("NVIDIA NIM returned invalid JSON response")
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();
        }

        /* Extract the generated text from the structured JSON. */
        String text = extractText(root);
        if (text == null || text.isBlank()) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                            "exceptionClass=EMPTY_CONTENT exceptionMessage=\"NVIDIA response missing message content\" " +
                            "statusCode=EMPTY_CONTENT rawBody=\"{}\" fullUrl={}",
                    model, llmRequestId, eventId,
                    truncate(rawBody, 2000), fullUrl);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("EMPTY_CONTENT")
                    .errorMessage("NVIDIA NIM returned response with no content")
                    .llmRequestId(llmRequestId)
                    .latencyMs(ms)
                    .build();
        }

        /* Read usage and finish-reason fields if present. */
        long ms = Duration.between(start, Instant.now()).toMillis();
        Integer promptTokens = root.path("usage").path("prompt_tokens").isMissingNode()
                ? null : root.path("usage").path("prompt_tokens").asInt();
        Integer completionTokens = root.path("usage").path("completion_tokens").isMissingNode()
                ? null : root.path("usage").path("completion_tokens").asInt();
        Integer totalTokens = root.path("usage").path("total_tokens").isMissingNode()
                ? null : root.path("usage").path("total_tokens").asInt();
        String finishReason = root.path("choices").path(0).path("finish_reason").asText(null);

        log.info("LLM_PROVIDER_RESPONSE provider=nvidia-nim model={} llmRequestId={} eventId={} latencyMs={} promptTokens={} completionTokens={} totalTokens={} status=OK finishReason={}",
                model, llmRequestId, eventId, ms, promptTokens, completionTokens, totalTokens, finishReason);

        return LlmProviderResponse.builder()
                .provider(providerName())
                .model(model)
                .text(text)
                .success(true)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .finishReason(finishReason)
                .llmRequestId(llmRequestId)
                .latencyMs(ms)
                .build();
    }

    /**
     * Sends the request via Spring's reactive {@link WebClient}.
     * On a non-2xx status the response body is captured and re-thrown as a
     * {@link WebClientResponseException} so the caller can log it.
     */
    private String sendWithWebClient(Map<String, Object> body, String fullUrl, String eventId, Instant start) {
        return webClient.post()
                .uri(fullUrl)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        /* Error responses: read the body and wrap it in an exception for consistent handling. */
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(rawBody -> Mono.error(
                                        WebClientResponseException.create(
                                                response.statusCode().value(),
                                                response.statusCode().toString(),
                                                HttpHeaders.EMPTY,
                                                rawBody.getBytes(StandardCharsets.UTF_8),
                                                StandardCharsets.UTF_8,
                                                null)));
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("");
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .block(Duration.ofMillis(timeoutMs + 5000));
    }

    /**
     * Sends the request using the JDK built-in {@link HttpClient}.
     * This is an alternative path for environments where the reactive stack is unavailable.
     * HTTP error status codes are wrapped in {@link WebClientResponseException} for consistency.
     */
    private String sendWithJdkHttpClient(String jsonBody, String fullUrl, String eventId, Instant start)
            throws java.io.IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = httpResponse.statusCode();
        String responseBody = httpResponse.body();

        /* Convert non-2xx to the same exception type used by the WebClient path. */
        if (statusCode >= 400) {
            throw WebClientResponseException.create(
                    statusCode,
                    "HTTP " + statusCode,
                    org.springframework.http.HttpHeaders.EMPTY,
                    responseBody.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8,
                    null);
        }

        return responseBody;
    }

    /** Logs diagnostic information about the outgoing request (URL, auth header presence, key validity). */
    private void logDiagnostics(String fullUrl, String eventId, String llmRequestId) {
        boolean keyPresent = apiKey != null && !apiKey.isBlank();
        int keyLength = keyPresent ? apiKey.length() : 0;
        boolean keyPrefixValid = keyPresent && apiKey.startsWith("nvapi-");
        log.info("LLM_PROVIDER_DIAGNOSTICS provider=nvidia-nim model={} llmRequestId={} eventId={} " +
                        "fullUrl={} method=POST contentType=application/json accept=application/json " +
                        "authorizationPresent={} authorizationScheme=Bearer " +
                        "apiKeyLength={} apiKeyPrefixValid={} httpClient={}",
                model, llmRequestId, eventId, fullUrl, keyPresent, keyLength, keyPrefixValid, httpClientType);
    }

    @Override
    public String providerName() {
        return "nvidia-nim";
    }

    /** Returns {@code true} when the API key has been provided and is non-blank. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Returns the model identifier currently configured for this provider. */
    public String getModel() {
        return model;
    }

    /**
     * Assembles the JSON-serialisable request body map for the NVIDIA NIM
     * chat/completions API. In minimal mode only a trivial "Say hello" message
     * is sent; otherwise the system and user prompts from the request are used.
     */
    Map<String, Object> buildRequestBody(LlmProviderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        /* Build the messages array — either minimal or full prompt. */
        List<Map<String, Object>> messages = new ArrayList<>();
        if (minimalRequestMode) {
            /* Connectivity-test mode: ignores the actual prompts. */
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", "Say hello in one sentence.");
            messages.add(userMsg);
        } else {
            Map<String, Object> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.getSystemPrompt());
            messages.add(systemMsg);

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", request.getUserPrompt());
            messages.add(userMsg);
        }
        body.put("messages", messages);

        /* Sampling and generation parameters. */
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        /* Extended parameters (reasoning budget, thinking) — only when both includeExtraBody and full mode are active. */
        if (!minimalRequestMode && includeExtraBody) {
            if (reasoningBudget > 0 || sendReasoningBudgetWhenZero) {
                body.put("reasoning_budget", reasoningBudget);
            }
            Map<String, Object> chatTemplateKwargs = new LinkedHashMap<>();
            chatTemplateKwargs.put("enable_thinking", enableThinking);
            body.put("chat_template_kwargs", chatTemplateKwargs);
        }

        return body;
    }

    /**
     * Logs a structured summary of the outgoing request body including roles,
     * content lengths, and top-level keys. Optionally logs the full JSON when
     * {@link #logRequestJson} is enabled (subject to truncation).
     */
    @SuppressWarnings("unchecked")
    private void logRequestBody(Map<String, Object> body, LlmProviderRequest request, String llmRequestId) {
        /* Inspect the messages array to extract roles and content lengths. */
        List<String> roles = new ArrayList<>();
        List<Integer> contentLengths = new ArrayList<>();
        Object messagesRaw = body.get("messages");
        if (messagesRaw instanceof List<?> msgs) {
            for (Object m : msgs) {
                if (m instanceof Map) {
                    Map<Object, Object> msg = (Map<Object, Object>) m;
                    String role = String.valueOf(msg.getOrDefault("role", "?"));
                    roles.add(role);
                    String content = String.valueOf(msg.getOrDefault("content", ""));
                    contentLengths.add(content.length());
                }
            }
        }

        List<String> topLevelKeys = new ArrayList<>(body.keySet());
        boolean hasTopLevelModel = body.containsKey("model") && body.get("model") != null;

        /* Serialise the body to JSON for size logging. */
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            bodyJson = "(serialization error)";
        }

        log.info("LLM_PROVIDER_REQUEST_BODY provider=nvidia-nim model={} llmRequestId={} hasTopLevelModel={} topLevelKeys={} " +
                        "roles={} contentLengths={} temperature={} topP={} maxTokens={} stream=false " +
                        "hasExtraBody={} hasReasoningBudget={} hasChatTemplateKwargs={} " +
                        "hasResponseFormat={} hasTools={} hasToolChoice={} " +
                        "minimalMode={} eventId={} totalJsonBytes={}",
                model, llmRequestId, hasTopLevelModel, topLevelKeys,
                roles, contentLengths,
                body.getOrDefault("temperature", "?"),
                body.getOrDefault("top_p", "?"),
                body.getOrDefault("max_tokens", "?"),
                body.containsKey("extra_body"),
                body.containsKey("reasoning_budget"),
                body.containsKey("chat_template_kwargs"),
                body.containsKey("response_format"),
                body.containsKey("tools"),
                body.containsKey("tool_choice"),
                minimalRequestMode,
                request.getEventId(),
                bodyJson.length());

        if (logRequestJson) {
            String truncated = truncate(bodyJson, logRequestMaxChars);
            log.info("LLM_PROVIDER_REQUEST_JSON provider=nvidia-nim llmRequestId={} eventId={} body={}",
                    llmRequestId, request.getEventId(), truncated);
        }
    }

    /**
     * Navigates the NVIDIA NIM JSON response tree to extract the generated
     * text from the first choice's message content.
     */
    private String extractText(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.trim();
    }

    /** Extracts the HTTP response body from a {@link WebClientResponseException} for error logging. */
    private String extractNvidiaResponseBody(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            try {
                String body = wcre.getResponseBodyAsString();
                return body != null && !body.isBlank() ? body : "(no body)";
            } catch (Exception ex) {
                return "(unreadable body)";
            }
        }
        return "(no response body)";
    }

    /** Truncates a string to {@code max} characters, appending an ellipsis if truncated. */
    private String truncate(String s, int max) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Removes newlines from a string so it can safely be included in a single-line log message. */
    private String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
