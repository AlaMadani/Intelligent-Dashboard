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


@Component("nvidia-nim")
@Slf4j
public class NvidiaNimLlmProvider implements LlmProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${app.llm.nvidia.base-url:https://integrate.api.nvidia.com/v1}")
    private String baseUrl;

    @Value("${app.llm.nvidia.api-key:}")
    private String apiKey;

    @Value("${app.llm.nvidia.model:nvidia/nemotron-3-super-120b-a12b}")
    private String model;

    @Value("${app.llm.nvidia.temperature:1.0}")
    private double temperature;

    @Value("${app.llm.nvidia.top-p:0.95}")
    private double topP;

    @Value("${app.llm.nvidia.max-tokens:128}")
    private int maxTokens;

    @Value("${app.llm.nvidia.enable-thinking:false}")
    private boolean enableThinking;

    @Value("${app.llm.nvidia.reasoning-budget:0}")
    private int reasoningBudget;

    @Value("${app.llm.nvidia.send-reasoning-budget-when-zero:false}")
    private boolean sendReasoningBudgetWhenZero;

    @Value("${app.llm.nvidia.timeout-ms:120000}")
    private long timeoutMs;

    @Value("${app.llm.nvidia.include-extra-body:false}")
    private boolean includeExtraBody;

    @Value("${app.llm.nvidia.minimal-request-mode:true}")
    private boolean minimalRequestMode;

    @Value("${app.llm.nvidia.log-request-json:true}")
    private boolean logRequestJson;

    @Value("${app.llm.nvidia.log-request-max-chars:4000}")
    private int logRequestMaxChars;

    @Value("${app.llm.nvidia.http-client:webclient}")
    private String httpClientType;

    public NvidiaNimLlmProvider(ObjectMapper objectMapper) {
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector())
                .build();
        this.objectMapper = objectMapper;
    }

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
        if (!isConfigured()) {
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("NOT_CONFIGURED")
                    .errorMessage("NVIDIA NIM API key not configured. Set app.llm.nvidia.api-key or NVIDIA_API_KEY.")
                    .build();
        }

        Instant start = Instant.now();
        String fullUrl = baseUrl + "/chat/completions";
        String llmRequestId = request.getLlmRequestId();
        String eventId = request.getEventId();
        logDiagnostics(fullUrl, eventId, llmRequestId);

        try {
            Map<String, Object> body = buildRequestBody(request);
            logRequestBody(body, request, llmRequestId);
            String jsonBody = objectMapper.writeValueAsString(body);

            String rawBody = "jdk".equals(httpClientType)
                    ? sendWithJdkHttpClient(jsonBody, fullUrl, eventId, start)
                    : sendWithWebClient(body, fullUrl, eventId, start);

            return parseRawResponse(rawBody, eventId, fullUrl, start, llmRequestId);

        } catch (WebClientResponseException e) {
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

    LlmProviderResponse parseRawResponse(String rawBody, String eventId, String fullUrl, Instant start, String llmRequestId) {
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

    private String sendWithWebClient(Map<String, Object> body, String fullUrl, String eventId, Instant start) {
        return webClient.post()
                .uri(fullUrl)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
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

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getModel() {
        return model;
    }

    Map<String, Object> buildRequestBody(LlmProviderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        List<Map<String, Object>> messages = new ArrayList<>();
        if (minimalRequestMode) {
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

        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

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

    @SuppressWarnings("unchecked")
    private void logRequestBody(Map<String, Object> body, LlmProviderRequest request, String llmRequestId) {
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

    private String truncate(String s, int max) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
