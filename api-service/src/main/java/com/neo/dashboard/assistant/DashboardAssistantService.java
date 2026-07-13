package com.neo.dashboard.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toList;

@Service
@Slf4j
public class DashboardAssistantService {

    private static final Pattern EVENT_ID_PATTERN = Pattern.compile(
            "\\b(?:anom|evt)-\\d{12}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSURED_ID_PATTERN = Pattern.compile(
            "\\binsured-[a-z0-9]+(?:-[a-z0-9]+)*\\b", Pattern.CASE_INSENSITIVE);

    private WebClient webClient;
    private final ObjectMapper objectMapper;
    private final DashboardAssistantCapabilityRegistry registry;

    @Value("${app.llm.nvidia.base-url:https://integrate.api.nvidia.com/v1}")
    private String baseUrl;

    @Value("${app.llm.nvidia.api-key:}")
    private String apiKey;

    @Value("${app.assistant.dashboard.enabled:true}")
    private boolean enabled;

    @Value("${app.assistant.dashboard.deterministic-actions-enabled:false}")
    private boolean deterministicActionsEnabled;

    @Value("${app.assistant.dashboard.model:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning}")
    private String model;

    @Value("${app.assistant.dashboard.temperature:0.0}")
    private double temperature;

    @Value("${app.assistant.dashboard.top-p:0.7}")
    private double topP;

    @Value("${app.assistant.dashboard.max-tokens:20480}")
    private int maxTokens;

    @Value("${app.assistant.dashboard.timeout-ms:30000}")
    private long timeoutMs;

    @Value("${app.assistant.dashboard.fallback-to-explanation-model:false}")
    private boolean fallbackToExplanationModel;

    @Value("${app.assistant.dashboard.use-guided-json:false}")
    private boolean useGuidedJson;

    @Value("${app.assistant.dashboard.guided-json-supported-models:nvidia/llama-3.3-nemotron-super-49b-v1}")
    private String guidedJsonSupportedModelsCsv;

    @Value("${app.assistant.dashboard.max-candidates:10}")
    private int maxCandidates;

    @Value("${app.assistant.dashboard.planner-mode:model-only}")
    private String plannerMode;

    @Value("${app.assistant.dashboard.candidate-models:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning,nvidia/nemotron-3-nano-30b-a3b,meta/llama-3.1-8b-instruct,nvidia/llama-3.1-nemotron-nano-8b-v1,meta/llama-4-maverick-17b-128e-instruct,deepseek-ai/deepseek-v4-flash,mistralai/mistral-nemotron}")
    private String candidateModelsCsv;

    @Value("${app.assistant.dashboard.fallback-on-parse-failure:true}")
    private boolean fallbackOnParseFailure;

    @Value("${app.assistant.dashboard.fallback-on-timeout:true}")
    private boolean fallbackOnTimeout;

    @Value("${app.assistant.dashboard.fallback-on-network-error:true}")
    private boolean fallbackOnNetworkError;

    @Value("${app.assistant.dashboard.fallback-on-provider-5xx:true}")
    private boolean fallbackOnProvider5xx;

    @Value("${app.assistant.dashboard.reasoning-budget:16384}")
    private int reasoningBudget;

    @Value("${app.assistant.dashboard.enable-thinking:false}")
    private boolean enableThinking;

    private List<String> getCandidateModels() {
        if (candidateModelsCsv == null || candidateModelsCsv.isBlank()) {
            return List.of("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning");
        }
        return List.of(candidateModelsCsv.split("\\s*,\\s*"));
    }

    public List<String> getCandidateModelsPublic() {
        return getCandidateModels();
    }
    private List<AssistantVisibleElement> currentVisibleElements = List.of();
    // Holds the last user message for post-processing overrides
    private String lastUserMessage = "";

    public DashboardAssistantService(ObjectMapper objectMapper, DashboardAssistantCapabilityRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.webClient = WebClient.builder().build();
    }

    @PostConstruct
    public void initHttpClient() {
        // Recreate the HttpClient with the actual dashboard timeout value
        long effectiveTimeout = Math.max(timeoutMs + 2000, 10000);
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(effectiveTimeout))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(effectiveTimeout, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(effectiveTimeout, TimeUnit.MILLISECONDS)));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @PostConstruct
    public void logModelConfig() {
        log.info("DASHBOARD_ASSISTANT_MODEL_SELECTED model={} baseUrl={} fullUrl={} maxTokens={} timeoutMs={} temperature={} topP={} useGuidedJson={} guidedJsonMode={} effectiveGuidedJson={} maxCandidates={} candidateModels={} fallbackToExplanationModel={} plannerMode={} fallbackOnTimeout={} fallbackOnNetworkError={} fallbackOnProvider5xx={} fallbackOnParseFailure={}",
                model, baseUrl, baseUrl + "/chat/completions", maxTokens, timeoutMs, temperature, topP,
                useGuidedJson, guidedJsonMode(), isGuidedJsonEnabled(), maxCandidates, candidateModelsCsv, fallbackToExplanationModel, plannerMode,
                fallbackOnTimeout, fallbackOnNetworkError, fallbackOnProvider5xx, fallbackOnParseFailure);
        if (isGuidedJsonEnabled()) {
            log.info("DASHBOARD_ASSISTANT_GUIDED_JSON_SCHEMA_VERSION=v2");
        } else {
            log.info("DASHBOARD_ASSISTANT_GUIDED_JSON_DISABLED model={} supportedModels={}", model, guidedJsonSupportedModelsCsv);
        }
        if (!isGuidedJsonEnabled()) {
            log.info("DASHBOARD_ASSISTANT_PAYLOAD_MODE=plain-openai no nvext no guided_json");
        }
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public DashboardAssistantResponse handleMessage(DashboardAssistantRequest request) {
        Instant start = Instant.now();
        String requestId = generateRequestId();
        this.currentVisibleElements = request.getVisibleElements() != null
                ? request.getVisibleElements()
                : List.of();

        if (!enabled) {
            DashboardAssistantResponse response = DashboardAssistantResponse.builder()
                    .message("Dashboard assistant is currently disabled.")
                    .commands(List.of())
                    .requiresConfirmation(false)
                    .warnings(List.of("dashboard_assistant_disabled"))
                    .build();
            logAssistantResponse(request, response, start);
            return response;
        }

        if (!deterministicActionsEnabled) {
            return handleMessageNonDeterministic(request, start);
        }

        DashboardAssistantResponse deterministic = tryDeterministicFallback(request);
        if (deterministic != null) {
            if (request.isDebug()) {
                addDebugMetadata(deterministic, null, request);
            }
            log.info("ASSISTANT_DETERMINISTIC_MATCH message=\"{}\" route={}",
                    sanitize(request.getMessage()), request.getCurrentRoute());
            logAssistantResponse(request, deterministic, start);
            return deterministic;
        }

        String systemPrompt = buildSystemPrompt(request);
        String userPrompt = request.getMessage();
        int promptChars = systemPrompt.length() + (userPrompt != null ? userPrompt.length() : 0);
        int visibleCount = request.getVisibleElements() != null ? request.getVisibleElements().size() : 0;
        int candidateCount = visibleCount;

        List<String> modelsToTry = getCandidateModels();
        if (request.getModel() != null && !request.getModel().isBlank()) {
            List<String> modified = new ArrayList<>();
            modified.add(request.getModel());
            for (String m : modelsToTry) {
                if (!m.equals(request.getModel())) {
                    modified.add(m);
                }
            }
            modelsToTry = modified;
        }

        return callModelWithFallback(request, start, systemPrompt, userPrompt, promptChars, candidateCount, modelsToTry);
    }

    private List<String> getGuidedJsonSupportedModels() {
        if (guidedJsonSupportedModelsCsv == null || guidedJsonSupportedModelsCsv.isBlank()) {
            return List.of();
        }
        return List.of(guidedJsonSupportedModelsCsv.split("\\s*,\\s*"));
    }

    private boolean isGuidedJsonEnabled() {
        return useGuidedJson && getGuidedJsonSupportedModels().contains(model);
    }

    private String guidedJsonMode() {
        if (!isGuidedJsonEnabled()) return "none";
        return "top-level-nvext";
    }

    private int countPromptChars(String systemPrompt, String userPrompt) {
        return (systemPrompt != null ? systemPrompt.length() : 0) + (userPrompt != null ? userPrompt.length() : 0);
    }

    private DashboardAssistantResponse handleMessageNonDeterministic(DashboardAssistantRequest request, Instant start) {
        boolean isModelOnly = "model-only".equals(plannerMode);
        String msg = normalize(request.getMessage());
        String rawMsg = request.getMessage();
        lastUserMessage = rawMsg;

        // In non-model-only modes, allow local shortcuts
        if (!isModelOnly && msg != null) {
            if (msg.matches(".*\\b(hello|hi there|hey)\\b.*")) {
                DashboardAssistantResponse response = noActionResponse("Hello! I am your dashboard assistant. I can help you navigate pages, highlight navigation items or page sections, search for alerts and users, and more.");
                if (request.isDebug()) {
                    addDebugMetadata(response, null, request);
                }
                logAssistantResponse(request, response, start);
                return response;
            }
            if (containsAny(rawMsg, "what can you do", "capabilities", "help", "available commands", "list commands", "how can you help")) {
                DashboardAssistantResponse response = noActionResponse("I can help you with:\n- Navigating to any dashboard page\n- Finding and highlighting UI elements\n- Explaining alerts with AI\n- Toggling dark/light theme\n- Changing your password\n- Filtering and refreshing data views\n- Searching for alerts and users\n\nTry asking: \"take me to churn\", \"highlight explain AI for anom-000000001179\", \"switch to dark mode\", or \"refresh the forecast\".");
                if (request.isDebug()) {
                    addDebugMetadata(response, null, request);
                }
                logAssistantResponse(request, response, start);
                return response;
            }
            if (containsAny(rawMsg, "what can you highlight", "which items", "list elements", "list highlightable", "show me what you can highlight", "list what", "highlightable items")) {
                DashboardAssistantResponse response = listHighlightableItemsResponse(request);
                if (request.isDebug()) {
                    addDebugMetadata(response, null, request);
                }
                logAssistantResponse(request, response, start);
                return response;
            }
        }

        String systemPrompt = buildSystemPrompt(request);
        String userPrompt = request.getMessage();
        int promptChars = systemPrompt.length() + (userPrompt != null ? userPrompt.length() : 0);
        int visibleCount = request.getVisibleElements() != null ? request.getVisibleElements().size() : 0;
        int candidateCount = visibleCount;

        List<String> modelsToTry = getCandidateModels();
        if (request.getModel() != null && !request.getModel().isBlank()) {
            List<String> modified = new ArrayList<>();
            modified.add(request.getModel());
            for (String m : modelsToTry) {
                if (!m.equals(request.getModel())) {
                    modified.add(m);
                }
            }
            modelsToTry = modified;
        }

        return callModelWithFallback(request, start, systemPrompt, userPrompt, promptChars, candidateCount, modelsToTry);
    }

    /**
     * Attempts each model from the candidate chain in order. Falls through on timeout,
     * network error, provider 5xx (and optionally parse failure). Returns the first
     * successful validated response, or an error if all models fail.
     */
    private DashboardAssistantResponse callModelWithFallback(
            DashboardAssistantRequest request,
            Instant start,
            String systemPrompt,
            String userPrompt,
            int promptChars,
            int candidateCount) {
        return callModelWithFallback(request, start, systemPrompt, userPrompt, promptChars, candidateCount, getCandidateModels());
    }

    private DashboardAssistantResponse callModelWithFallback(
            DashboardAssistantRequest request,
            Instant start,
            String systemPrompt,
            String userPrompt,
            int promptChars,
            int candidateCount,
            List<String> modelsToTry) {
        String requestId = generateRequestId();

        log.info("ASSISTANT_MODEL_REQUEST requestId={} message=\"{}\" baseUrl={} guidedJson={} guidedJsonMode={} maxTokens={} timeoutMs={} candidateCount={} visibleElementCount={} promptChars={} modelsToTry={}",
                requestId, sanitize(request.getMessage()), baseUrl, isGuidedJsonEnabled(), guidedJsonMode(),
                maxTokens, timeoutMs, candidateCount,
                request.getVisibleElements() != null ? request.getVisibleElements().size() : 0,
                promptChars, modelsToTry);

        // Log visible element IDs from frontend
        if (request.getVisibleElements() != null && !request.getVisibleElements().isEmpty()) {
            List<String> ids = request.getVisibleElements().stream()
                    .map(AssistantVisibleElement::getId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            log.info("ASSISTANT_REQUEST_VISIBLE_ELEMENTS requestId={} count={} ids={}",
                    requestId, ids.size(), ids);
        }

        List<Map<String, Object>> attemptedModels = new ArrayList<>();
        String fallbackReason = null;
        long totalLatencyMs = 0;
        DashboardAssistantResponse finalResponse = null;
        AssistantModelResponse finalModelResponse = null;
        String actualModelUsed = null;

        for (int attempt = 0; attempt < modelsToTry.size(); attempt++) {
            String currentModel = modelsToTry.get(attempt);
            Instant attemptStart = Instant.now();
            Map<String, Object> attemptRecord = new LinkedHashMap<>();
            attemptRecord.put("model", currentModel);

            log.info("ASSISTANT_MODEL_ATTEMPT requestId={} model={} attempt={}/{} timeoutMs={}",
                    requestId, currentModel, attempt + 1, modelsToTry.size(), timeoutMs);

            try {
                Map<String, Object> requestBody = buildRequestBody(systemPrompt, userPrompt, currentModel);
                String jsonBody = objectMapper.writeValueAsString(requestBody);
                String rawResponse = sendRequest(jsonBody);
                AssistantModelResponse parsed = parseModelResponse(rawResponse);
                DashboardAssistantResponse validated = validateAndBuildResponse(parsed);

                long attemptLatency = Duration.between(attemptStart, Instant.now()).toMillis();
                totalLatencyMs += attemptLatency;
                attemptRecord.put("status", "success");
                attemptRecord.put("latencyMs", attemptLatency);

                log.info("ASSISTANT_MODEL_RESPONSE requestId={} model={} latencyMs={} httpStatus=200 parseOk=true validatedCommandCount={}",
                        requestId, currentModel, attemptLatency,
                        validated.getCommands() != null ? validated.getCommands().size() : 0);

                // Log raw model message responseType for debugging
                if (parsed != null) {
                    log.info("ASSISTANT_MODEL_PARSED requestId={} model={} responseType={} message=\"{}\" commandTypes={}",
                            requestId, currentModel, parsed.getResponseType(),
                            truncate(parsed.getMessage(), 120),
                            parsed.getCommands() != null
                                    ? parsed.getCommands().stream().map(c -> c.getType() + ":" + (c.getElementId() != null ? c.getElementId() : c.getRouteName() != null ? c.getRouteName() : "?")).toList()
                                    : "[]");
                }

                actualModelUsed = currentModel;
                finalResponse = validated;
                finalModelResponse = parsed;
                attemptedModels.add(attemptRecord);
                break;
            } catch (Exception e) {
                long attemptLatency = Duration.between(attemptStart, Instant.now()).toMillis();
                totalLatencyMs += attemptLatency;
                String errMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                if (errMsg.contains("timeout") || errMsg.contains("timed out")) {
                    attemptRecord.put("status", "timeout");
                    attemptRecord.put("latencyMs", attemptLatency);
                    attemptRecord.put("warning", "assistant_model_timeout");
                    log.warn("ASSISTANT_MODEL_TIMEOUT requestId={} model={} attempt={}/{} timeoutMs={} latencyMs={}",
                            requestId, currentModel, attempt + 1, modelsToTry.size(), timeoutMs, attemptLatency);
                } else if (errMsg.contains("network is unreachable") || errMsg.contains("connectexception")
                        || errMsg.contains("connection refused") || errMsg.contains("failed to resolve")
                        || errMsg.contains("unknownhost")) {
                    attemptRecord.put("status", "network_error");
                    attemptRecord.put("latencyMs", attemptLatency);
                    attemptRecord.put("warning", "assistant_model_network_error");
                    log.warn("ASSISTANT_MODEL_NETWORK_ERROR requestId={} model={} attempt={}/{} error={}",
                            requestId, currentModel, attempt + 1, modelsToTry.size(), e.getMessage());
                } else if (errMsg.contains("500") || errMsg.contains("502") || errMsg.contains("503")
                        || errMsg.contains("504") || errMsg.contains("5xx") || errMsg.contains("server error")) {
                    attemptRecord.put("status", "provider_5xx");
                    attemptRecord.put("latencyMs", attemptLatency);
                    attemptRecord.put("warning", "assistant_model_unavailable");
                    log.warn("ASSISTANT_MODEL_PROVIDER_ERROR requestId={} model={} attempt={}/{} error={}",
                            requestId, currentModel, attempt + 1, modelsToTry.size(), e.getMessage());
                } else if (errMsg.contains("empty response") || errMsg.contains("no json found")
                        || errMsg.contains("failed to parse") || errMsg.contains("empty content")) {
                    attemptRecord.put("status", "parse_failure");
                    attemptRecord.put("latencyMs", attemptLatency);
                    attemptRecord.put("warning", "assistant_response_parse_failed");
                    log.warn("ASSISTANT_MODEL_PARSE_FAILURE requestId={} model={} attempt={}/{} error={}",
                            requestId, currentModel, attempt + 1, modelsToTry.size(), e.getMessage());
                } else {
                    attemptRecord.put("status", "error");
                    attemptRecord.put("latencyMs", attemptLatency);
                    attemptRecord.put("warning", "assistant_model_unavailable");
                    log.warn("ASSISTANT_MODEL_ERROR requestId={} model={} attempt={}/{} error={}",
                            requestId, currentModel, attempt + 1, modelsToTry.size(), e.getMessage());
                }
                attemptedModels.add(attemptRecord);

                String status = (String) attemptRecord.get("status");
                boolean shouldFallback = false;
                if ("timeout".equals(status) && fallbackOnTimeout) shouldFallback = true;
                else if ("network_error".equals(status) && fallbackOnNetworkError) shouldFallback = true;
                else if ("provider_5xx".equals(status) && fallbackOnProvider5xx) shouldFallback = true;
                else if ("parse_failure".equals(status) && fallbackOnParseFailure) shouldFallback = true;

                if (shouldFallback && attempt + 1 < modelsToTry.size()) {
                    fallbackReason = status;
                    log.info("ASSISTANT_MODEL_FALLBACK requestId={} from={} to={} reason={}",
                            requestId, currentModel, modelsToTry.get(attempt + 1), status);
                    continue;
                }

                ClassifiedError err = classifyModelError(e);
                DashboardAssistantResponse errorResponse = DashboardAssistantResponse.builder()
                        .message(err.userMessage)
                        .commands(List.of())
                        .requiresConfirmation(false)
                        .warnings(List.of(err.warning))
                        .build();
                if (request.isDebug()) {
                    addDebugMetadata(errorResponse, null, request, totalLatencyMs, promptChars, 0, candidateCount,
                            err.decisionSource, currentModel, attemptedModels, fallbackReason);
                }
                logAssistantResponse(request, errorResponse, start);
                return errorResponse;
            }
        }

        if (finalResponse == null) {
            DashboardAssistantResponse errorResponse = DashboardAssistantResponse.builder()
                    .message("I couldn't reach the assistant planning model right now. Please try again.")
                    .commands(List.of())
                    .requiresConfirmation(false)
                    .warnings(List.of("assistant_model_unavailable"))
                    .build();
            if (request.isDebug()) {
                addDebugMetadata(errorResponse, null, request, totalLatencyMs, promptChars, 0, candidateCount,
                        "model-error", modelsToTry.get(modelsToTry.size() - 1), attemptedModels, fallbackReason);
            }
            logAssistantResponse(request, errorResponse, start);
            return errorResponse;
        }

        if (request.isDebug()) {
            addDebugMetadata(finalResponse, finalModelResponse, request, totalLatencyMs, promptChars, 0, candidateCount,
                    null, actualModelUsed, attemptedModels, fallbackReason);
        }

        boolean isModelOnly = "model-only".equals(plannerMode);
        if (!isModelOnly && finalResponse.getCommands().isEmpty() && finalResponse.getWarnings().isEmpty()) {
            DashboardAssistantResponse fb = tryDeterministicFallback(request);
            if (fb != null) {
                logAssistantResponse(request, fb, start);
                return fb;
            }
        }

        logAssistantResponse(request, finalResponse, start);
        return finalResponse;
    }

    private String buildSystemPrompt(DashboardAssistantRequest request) {
        String visibleElementsJson = buildVisibleElementsContext(request.getVisibleElements());
        String tasksJson = buildTasksContext();
        String routesJson = buildManifestRoutesContext();
        return """
You are a dashboard assistant.

You may either:
1. answer a normal question using current route/context/manifest,
2. return dashboard commands,
3. ask for clarification,
4. say unsupported.

Do not force every user message into an action.

If the user asks a normal conversational question, return responseType=ANSWER and commands=[].

If the user asks where they are or what view/page this is, answer using currentRoute and route description.

If the user asks a simple math question, answer it briefly and mention that you mainly help with the dashboard.

If the user asks for navigation/highlight/filter/theme, return ACTION with validated commands.

Never return action-like text without commands.

CRITICAL RULE — differentiate user intent: HIGHLIGHT_ELEMENT vs CLICK_ELEMENT:
When the user says "highlight X" or "show me X" where X is an element on screen, you MUST return a HIGHLIGHT_ELEMENT command with the matching elementId from visibleElements. Do NOT answer with information about X instead of highlighting it. "Highlight the average risk score" means visually highlight the card element — it does NOT mean tell the user the score value. Even if you know the value from the element's textContent, you must still return HIGHLIGHT_ELEMENT, not ANSWER. The user wants the element highlighted in the UI, not told its value. If you cannot find a matching element, use responseType=CLARIFICATION.

However, when the user's intent is to PERFORM an action — such as "refresh", "toggle", "open", "close", "switch", "trigger" — and the visible element supports CLICK_ELEMENT, you MUST use CLICK_ELEMENT, not HIGHLIGHT_ELEMENT. Use your intelligence to distinguish intent: "show me the refresh button" → HIGHLIGHT_ELEMENT, but "refresh the data", "click refresh", "refresh the page" → CLICK_ELEMENT. "show me the sidebar toggle" → HIGHLIGHT_ELEMENT, but "open the sidebar", "close the sidebar", "toggle the sidebar" → CLICK_ELEMENT.

Return only valid JSON. No markdown. No code fences. No explanation outside the JSON schema.
Never output thinking or reasoning tokens like <think> or </think>. Output nothing before { and nothing after }. If you use reasoning internally, suppress it from the final output.

For requests about "show", "highlight", or "table" data, prefer commands that reference visible elements over SET_FILTER commands. Visible elements represent what is actually on screen.

If the requested information exists among the visibleElements on the current route, use HIGHLIGHT_ELEMENT to point at it visually. Do not navigate away or include a NAVIGATE command for elements that already exist on the current page. If the element supports CLICK_ELEMENT and the user's intent is to trigger an action on it (refresh, toggle, open, close), use CLICK_ELEMENT instead — highlighting does not trigger actions.

Only use NAVIGATE when the user explicitly asks to go to a different page or section. If the user says "here", "this view", "this page", "current page", or "the info exists here", that means the element is on the current page — use HIGHLIGHT_ELEMENT, not NAVIGATE.

Use the visibleElements list to find the correct elementId. For example, the Kibana button has id "btn-kibana", Grafana has "btn-grafana", the theme toggle has "theme-toggle". When the user says "open kibana" or "take me to kibana", use CLICK_ELEMENT with elementId "btn-kibana" — do NOT use NAVIGATE.

CRITICAL: Do NOT use NAVIGATE for external tools like Kibana, Grafana, or account settings pages. These are NOT routes — they are CLICK_ELEMENT actions that open external links. When the user asks to go to kibana, grafana, or account settings, use CLICK_ELEMENT with elementId matching the button visible in the visibleElements list (e.g. "btn-kibana", "btn-grafana", "btn-user-avatar"). NAVIGATE is only for internal dashboard pages listed in Available manifest routes.

Current route: %s
Current context: %s
Visible elements:
%s
Available manifest routes:
%s
Available manifest tasks:
%s

Supported command types: NAVIGATE, HIGHLIGHT_ELEMENT, CLICK_ELEMENT, SET_FILTER, REFRESH_VIEW, OPEN_PANEL, EXPLAIN_WITH_AI, TOGGLE_THEME, CHANGE_PASSWORD, SEARCH_ALERT, SEARCH_USER, SEARCH_SESSION, NO_ACTION

For CLICK_ELEMENT, use only for safe controls like refresh buttons, external link buttons (grafana, kibana), avatar menu, sidebar toggle, and theme toggle. Do not use for sign-out or destructive actions. Include elementId.

CRITICAL: For "change password" or "account settings" requests, use the CHANGE_PASSWORD command type (no elementId needed). Do NOT use CLICK_ELEMENT on btn-user-avatar — the avatar button only opens a dropdown menu; it does not navigate to the account page. CHANGE_PASSWORD handles the full navigation and highlighting automatically.

Respond in strict JSON format:
{
  "responseType": "ANSWER|ACTION|MIXED|CLARIFICATION|UNSUPPORTED",
  "message": "Your natural language response",
  "commands": [
    {
      "type": "COMMAND_TYPE",
      "routeName": "route-name",
      "params": { "key": "value" },
      "elementId": "element-id",
      "panelId": "panel-id",
      "query": "search-query",
      "target": "filter-or-refresh-target",
      "value": "filter-value",
      "message": "Description of the action"
    }
  ],
  "requiresConfirmation": false
}

Your "message" must never say "refreshing", "enabling", "switching", "navigating", "opening", "filtering", "showing", or "highlighting" unless you include a matching command.
For SEARCH_ALERT, include "query" field.
For SEARCH_USER, include "query" field with insuredId.
For SEARCH_SESSION, include "query" field with sessionId.
For NAVIGATE, do not include requiresConfirmation=true for normal navigation.
For SET_FILTER, include "target" (like churn-risk-filter or alerts-risk-filter) and "value".
For TOGGLE_THEME, include "value" ("light" or "dark") based on the user's request. "light it up", "switch to light", "enable light" → value "light". "dark mode", "enable dark", "darken" → value "dark". Do not toggle blindly — set the specific theme the user asked for.
""".formatted(
                request.getCurrentRoute() != null ? request.getCurrentRoute() : "unknown",
                request.getCurrentContext() != null ? request.getCurrentContext().toString() : "none",
                visibleElementsJson,
                routesJson,
                tasksJson
        );
    }

    private String buildVisibleElementsContext(List<AssistantVisibleElement> visibleElements) {
        if (visibleElements == null || visibleElements.isEmpty()) {
            return "  (no visible elements reported)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  [");
        for (int i = 0; i < visibleElements.size(); i++) {
            AssistantVisibleElement el = visibleElements.get(i);
            sb.append("\n    { \"id\": \"").append(escapeJson(el.getId()));
            sb.append("\", \"type\": \"").append(escapeJson(el.getType()));
            sb.append("\", \"label\": \"").append(escapeJson(el.getLabel()));
            sb.append("\", \"description\": \"").append(escapeJson(el.getDescription()));
            sb.append("\", \"routeId\": \"").append(escapeJson(el.getRouteId()));
            sb.append("\", \"actions\": ").append(el.getActions() != null ? el.getActions().toString() : "[]");
            sb.append(", \"visible\": ").append(el.isVisible());
            if (el.getTextContent() != null && !el.getTextContent().isBlank()) {
                sb.append(", \"textContent\": \"").append(escapeJson(el.getTextContent())).append("\"");
            }
            sb.append(" }");
        }
        sb.append("\n  ]");
        return sb.toString();
    }

    private List<AssistantVisibleElement> rankVisibleElements(List<AssistantVisibleElement> elements) {
        if (elements == null || elements.isEmpty()) return List.of();
        List<AssistantVisibleElement> working = new ArrayList<>(elements);
        working.sort((a, b) -> {
            int scoreA = rankScore(a);
            int scoreB = rankScore(b);
            return Integer.compare(scoreB, scoreA);
        });
        return working;
    }

    private int rankScore(AssistantVisibleElement el) {
        int score = 0;
        if (el.isVisible()) score += 20;
        if (el.getTextContent() != null && !el.getTextContent().isBlank()) score += 5;
        if (el.getActions() != null) {
            if (el.getActions().contains("CLICK_ELEMENT")) score += 3;
            if (el.getActions().contains("REFRESH_VIEW")) score += 3;
            if (el.getActions().contains("SET_FILTER")) score += 3;
        }
        if ("button".equals(el.getType())) score += 2;
        if ("card".equals(el.getType())) score += 2;
        if ("table".equals(el.getType())) score += 2;
        if ("nav-item".equals(el.getType())) score += 1;
        if (el.getId() != null && (el.getId().contains("kpi") || el.getId().contains("table") || el.getId().contains("refresh"))) score += 1;
        return score;
    }

    private String buildTasksContext() {
        Map<String, DashboardAssistantManifest.ManifestTask> taskMap = registry.getTaskMap();
        if (taskMap == null || taskMap.isEmpty()) {
            return "  (no tasks available)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  [");
        for (DashboardAssistantManifest.ManifestTask task : taskMap.values()) {
            sb.append("\n    { \"id\": \"").append(escapeJson(task.getId()));
            sb.append("\", \"label\": \"").append(escapeJson(task.getLabel()));
            sb.append("\", \"description\": \"").append(escapeJson(task.getDescription()));
            sb.append("\", \"actions\": ").append(task.getActionsSupported() != null ? task.getActionsSupported().toString() : "[]");
            if (task.getRequiresContext() != null && !task.getRequiresContext().isEmpty()) {
                sb.append(", \"requiresContext\": ").append(task.getRequiresContext().toString());
            }
            sb.append(" }");
        }
        sb.append("\n  ]");
        return sb.toString();
    }

    private String buildManifestElementsForRoute(String routeId) {
        if (routeId == null || routeId.isBlank()) return "  (no route specified)";
        List<DashboardAssistantManifest.ManifestElement> elements = registry.getManifest().getElements();
        if (elements == null || elements.isEmpty()) return "  (no manifest elements)";
        StringBuilder sb = new StringBuilder();
        sb.append("  [");
        for (DashboardAssistantManifest.ManifestElement el : elements) {
            if (routeId.equals(el.getRouteId())) {
                sb.append("\n    { \"id\": \"").append(escapeJson(el.getId()));
                sb.append("\", \"type\": \"").append(escapeJson(el.getType()));
                sb.append("\", \"label\": \"").append(escapeJson(el.getLabel()));
                sb.append("\", \"description\": \"").append(escapeJson(el.getDescription()));
                sb.append("\", \"visibility\": \"").append(escapeJson(el.getVisibility()));
                sb.append("\", \"actions\": ").append(el.getActionsSupported() != null ? el.getActionsSupported().toString() : "[]");
                sb.append(" }");
            }
        }
        sb.append("\n  ]");
        return sb.toString();
    }

    private String buildManifestRoutesContext() {
        List<DashboardAssistantManifest.ManifestRoute> routes = registry.getManifest().getRoutes();
        if (routes == null || routes.isEmpty()) {
            return "  (no routes available)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  [");
        for (DashboardAssistantManifest.ManifestRoute route : routes) {
            sb.append("\n    { \"id\": \"").append(escapeJson(route.getId()));
            sb.append("\", \"routeName\": \"").append(escapeJson(route.getRouteName()));
            sb.append("\", \"label\": \"").append(escapeJson(route.getLabel()));
            sb.append("\", \"description\": \"").append(escapeJson(route.getDescription()));
            sb.append("\", \"requiresParams\": ").append(route.getRequiresParams() != null ? route.getRequiresParams().toString() : "[]");
            sb.append(" }");
        }
        sb.append("\n  ]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        return buildRequestBody(systemPrompt, userPrompt, model);
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt, String modelName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        // NVIDIA reasoning model: explicitly disable thinking unless configured
        if (modelName.contains("nemotron-3-nano-omni")) {
            Map<String, Object> chatTemplateKwargs = new LinkedHashMap<>();
            chatTemplateKwargs.put("enable_thinking", enableThinking);
            body.put("chat_template_kwargs", chatTemplateKwargs);
            body.put("reasoning_budget", reasoningBudget);
        }

        // Nemotron 3 Nano (non-reasoning) also needs thinking disabled
        if ("nvidia/nemotron-3-nano-30b-a3b".equals(modelName)) {
            Map<String, Object> chatTemplateKwargs = new LinkedHashMap<>();
            chatTemplateKwargs.put("enable_thinking", false);
            body.put("chat_template_kwargs", chatTemplateKwargs);
            body.put("reasoning_budget", 0);
        }

        // Meta Llama models: explicitly disable reasoning
        if (modelName.contains("llama")) {
            Map<String, Object> chatTemplateKwargs = new LinkedHashMap<>();
            chatTemplateKwargs.put("enable_thinking", false);
            body.put("chat_template_kwargs", chatTemplateKwargs);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);
        messages.add(systemMessage);

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        messages.add(userMessage);

        body.put("messages", messages);

        if (isGuidedJsonEnabled()) {
            Map<String, Object> nvext = new LinkedHashMap<>();
            Map<String, Object> guidedJson = new LinkedHashMap<>();
            guidedJson.put("type", "object");
            guidedJson.put("properties", buildGuidedJsonProperties());
            guidedJson.put("required", List.of("responseType", "message", "commands", "requiresConfirmation"));
            nvext.put("guided_json", guidedJson);
            body.put("nvext", nvext);
        }

        return body;
    }

    private Map<String, Object> buildGuidedJsonProperties() {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("responseType", Map.of("type", "string", "enum", List.of(
                "ANSWER", "ACTION", "MIXED", "CLARIFICATION", "UNSUPPORTED")));

        props.put("message", Map.of("type", "string"));

        Map<String, Object> commandItem = new LinkedHashMap<>();
        commandItem.put("type", "object");
        commandItem.put("properties", Map.of(
                "type", Map.of("type", "string", "enum", List.of(
                        "NAVIGATE", "HIGHLIGHT_ELEMENT", "SEARCH_ALERT", "SEARCH_USER",
                        "SEARCH_SESSION", "OPEN_PANEL", "SET_FILTER", "REFRESH_VIEW",
                        "TOGGLE_THEME", "NO_ACTION")),
                "routeName", Map.of("type", List.of("string", "null")),
                "elementId", Map.of("type", List.of("string", "null")),
                "target", Map.of("type", List.of("string", "null")),
                "value", Map.of("type", List.of("string", "null")),
                "message", Map.of("type", List.of("string", "null")),
                "params", Map.of("type", List.of("object", "null")),
                "query", Map.of("type", List.of("object", "null"))
        ));
        commandItem.put("required", List.of("type"));

        Map<String, Object> commandsProp = new LinkedHashMap<>();
        commandsProp.put("type", "array");
        commandsProp.put("items", commandItem);
        props.put("commands", commandsProp);

        props.put("requiresConfirmation", Map.of("type", "boolean"));

        return props;
    }

    private String sendRequest(String jsonBody) {
        String fullUrl = baseUrl + "/chat/completions";
        try {
            return webClient.post()
                    .uri(fullUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Assistant request failed: " + e.getMessage(), e);
        }
    }

    private String sendRequestRaw(String jsonBody) {
        return sendRequest(jsonBody);
    }

    private AssistantModelResponse parseModelResponse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new RuntimeException("Empty response from assistant");
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String content = root.at("/choices/0/message/content").asText(null);
            if (content == null || content.isBlank()) {
                throw new RuntimeException("Empty content in assistant response");
            }
            String json = extractJson(content);
            if (json == null) {
                log.warn("ASSISTANT_MODEL_RAW_OUTPUT content=\"{}\"", content);
                throw new RuntimeException("No JSON found in assistant response: " + truncate(content, 200));
            }
            try {
                return objectMapper.readValue(json, AssistantModelResponse.class);
            } catch (Exception e) {
                log.warn("ASSISTANT_MODEL_PARSE_FAILED_JSON json=\"{}\" error=\"{}\"", truncate(json, 500), e.getMessage());
                throw new RuntimeException("Failed to parse assistant response", e);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Failed to parse assistant response", e);
        }
    }

    private String extractJson(String text) {
        if (text == null) return null;
        // Strip markdown code fences and thinking tokens
        String cleaned = text.replaceAll("```(?:json)?\\s*", "").trim();
        cleaned = cleaned.replaceAll("(?s)<think>.*?</think>", "").trim();
        cleaned = cleaned.replaceAll("</?think>", "").trim();
        int start = cleaned.indexOf('{');
        if (start < 0) {
            log.warn("EXTRACT_JSON_NO_BRACE cleaned=\"{}\" originalLen={}", truncate(cleaned, 300), text.length());
            return null;
        }
        int depth = 0;
        for (int i = start; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0) {
                return cleaned.substring(start, i + 1);
            }
        }
        return null;
    }

    private DashboardAssistantResponse validateAndBuildResponse(AssistantModelResponse modelResponse) {
        List<DashboardAssistantCommand> validCommands = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String rawUserMsg = lastUserMessage; // captured from the current request

        // Build a set of elementIds that the model was allowed to choose from (visible elements + manifest)
        java.util.Set<String> allowedElementIds = new java.util.HashSet<>();
        for (AssistantVisibleElement ve : currentVisibleElements) {
            if (ve.getId() != null) allowedElementIds.add(ve.getId());
        }

        // Preprocess model commands: normalize route names, filter targets, and refresh targets
        if (modelResponse.getCommands() != null) {
            for (AssistantModelResponse.AssistantModelCommand mc : modelResponse.getCommands()) {
                if ("NAVIGATE".equals(mc.getType()) && mc.getRouteName() != null) {
                    String normalized = registry.normalizeRouteName(mc.getRouteName());
                    if (normalized != null) {
                        mc.setRouteName(normalized);
                    }
                }
                if ("SET_FILTER".equals(mc.getType()) && mc.getTarget() != null) {
                    String normalized = registry.normalizeFilterTarget(mc.getTarget());
                    if (normalized != null) {
                        mc.setTarget(normalized);
                    }
                }
                if ("REFRESH_VIEW".equals(mc.getType()) && mc.getTarget() != null) {
                    String normalized = registry.normalizeRefreshTarget(mc.getTarget());
                    if (normalized != null) {
                        mc.setTarget(normalized);
                    }
                }
            }
        }

        if (modelResponse.getCommands() != null) {
            for (AssistantModelResponse.AssistantModelCommand mc : modelResponse.getCommands()) {
                if (mc.getType() == null) {
                    warnings.add("assistant_command_rejected");
                    continue;
                }
                if (!registry.isAllowedCommandType(mc.getType())) {
                    warnings.add("assistant_invalid_command_type");
                    continue;
                }
                if ("NO_ACTION".equals(mc.getType())) {
                    validCommands.add(DashboardAssistantCommand.builder()
                            .type("NO_ACTION")
                            .message(mc.getMessage() != null ? mc.getMessage() : "I can help with navigation, searching, and explaining dashboard areas.")
                            .build());
                    continue;
                }
                if ("NAVIGATE".equals(mc.getType())) {
                    if (mc.getRouteName() == null || !registry.isAllowedRoute(mc.getRouteName())) {
                        warnings.add("assistant_unknown_route");
                        continue;
                    }
                    DashboardAssistantManifest.ManifestRoute route = registry.getRoute(mc.getRouteName());
                    if (route != null && route.getRequiresParams() != null && !route.getRequiresParams().isEmpty()) {
                        Map<String, String> params = mc.getParams() != null ? mc.getParams() : Map.of();
                        List<String> missing = new ArrayList<>();
                        for (String requiredParam : route.getRequiresParams()) {
                            if (!params.containsKey(requiredParam) || params.get(requiredParam) == null || params.get(requiredParam).isBlank()) {
                                missing.add(requiredParam);
                            }
                        }
                        if (!missing.isEmpty()) {
                            warnings.add("assistant_missing_route_params");
                            validCommands.add(DashboardAssistantCommand.builder()
                                    .type("NO_ACTION")
                                    .message("Please provide the " + String.join(", ", missing) + " to navigate to " + route.getLabel() + ".")
                                    .build());
                            continue;
                        }
                    }
                }
                if ("HIGHLIGHT_ELEMENT".equals(mc.getType())) {
                    if (mc.getElementId() == null) {
                        warnings.add("assistant_unknown_element");
                        continue;
                    }
                    // Allow elementIds that are in the visible elements list OR in the global manifest
                    if (!allowedElementIds.contains(mc.getElementId()) && !registry.isAllowedElement(mc.getElementId())) {
                        // Fuzzy fallback: try to find a visible element matching the user's intent
                        String fuzzyMatch = fuzzyMatchElementAgainstVisible(mc.getElementId(), mc.getMessage(), allowedElementIds);
                        if (fuzzyMatch != null) {
                            mc.setElementId(fuzzyMatch);
                        } else {
                            warnings.add("assistant_unknown_element");
                            continue;
                        }
                    }
                }
                if ("SET_FILTER".equals(mc.getType())) {
                    if (mc.getTarget() == null || !registry.isAllowedFilter(mc.getTarget())) {
                        warnings.add("assistant_invalid_filter");
                        continue;
                    }
                    DashboardAssistantManifest.ManifestFilter filter = registry.getFilter(mc.getTarget());
                    if (filter != null && filter.getAllowedValues() != null && !filter.getAllowedValues().isEmpty()) {
                        if (mc.getValue() == null || !filter.getAllowedValues().contains(mc.getValue())) {
                            warnings.add("assistant_invalid_filter");
                            continue;
                        }
                    }
                }
                if ("REFRESH_VIEW".equals(mc.getType())) {
                    if (mc.getTarget() == null || !registry.isAllowedRefreshTarget(mc.getTarget())) {
                        // Guard: if visible elements contain a refresh button, replace with highlight
                        String refreshButtonId = findRefreshButtonInVisible();
                        if (refreshButtonId != null) {
                            mc.setType("HIGHLIGHT_ELEMENT");
                            mc.setElementId(refreshButtonId);
                            mc.setMessage("Use this button to refresh the current view.");
                        } else {
                            warnings.add("assistant_unsupported_refresh");
                            continue;
                        }
                    }
                }
                if ("OPEN_PANEL".equals(mc.getType())) {
                    if (mc.getPanelId() == null || !registry.isAllowedPanel(mc.getPanelId())) {
                        warnings.add("assistant_unknown_panel");
                        continue;
                    }
                }
                if ("EXPLAIN_WITH_AI".equals(mc.getType())) {
                    String eventId = extractEventId(mc.getMessage());
                    if (eventId == null && currentVisibleElements != null) {
                        boolean onInvestigation = currentVisibleElements.stream()
                                .anyMatch(ve -> "btn-explain-ai".equals(ve.getId()));
                        if (!onInvestigation) {
                            warnings.add("assistant_missing_event_id_for_explain_ai");
                            continue;
                        }
                    }
                }
                if ("TOGGLE_THEME".equals(mc.getType())) {
                    // Always allowed
                }
                if ("CHANGE_PASSWORD".equals(mc.getType())) {
                    // Always allowed
                }
                if ("LIST_CAPABILITIES".equals(mc.getType()) || "LIST_HIGHLIGHTABLE".equals(mc.getType())) {
                    // Will be converted to ANSWER in post-processing
                }
                if ("CLICK_ELEMENT".equals(mc.getType())) {
                    if (mc.getElementId() == null || !registry.isSafeClickableElement(mc.getElementId())) {
                        warnings.add("assistant_unsafe_click");
                        continue;
                    }
                }
                DashboardAssistantCommand.DashboardAssistantCommandBuilder builder = DashboardAssistantCommand.builder()
                        .type(mc.getType())
                        .message(mc.getMessage());
                if (mc.getRouteName() != null) builder.routeName(mc.getRouteName());
                if (mc.getParams() != null) builder.params(mc.getParams());
                if (mc.getElementId() != null) builder.elementId(mc.getElementId());
                if (mc.getPanelId() != null) builder.panelId(mc.getPanelId());
                if (mc.getQuery() != null) builder.query(mc.getQuery());
                if (mc.getTarget() != null) builder.target(mc.getTarget());
                if (mc.getValue() != null) builder.value(mc.getValue());
                validCommands.add(builder.build());
            }
        }

        // ── Convert CHANGE_PASSWORD → NAVIGATE + HIGHLIGHT_ELEMENT ──
        // The model may emit CHANGE_PASSWORD, but the frontend only understands
        // NAVIGATE / HIGHLIGHT_ELEMENT / CLICK_ELEMENT etc. Expand it here.
        {
            boolean hasChangePassword = validCommands.stream().anyMatch(c -> "CHANGE_PASSWORD".equals(c.getType()));
            if (hasChangePassword) {
                validCommands.removeIf(c -> "CHANGE_PASSWORD".equals(c.getType()));
                validCommands.add(DashboardAssistantCommand.builder()
                        .type("NAVIGATE")
                        .routeName("account")
                        .message("Opening Account Settings page.")
                        .build());
                validCommands.add(DashboardAssistantCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("account-password-section")
                        .message("Here is the password change form.")
                        .build());
            }
        }

        // ── Post-validation guard: current-page HIGHLIGHT must beat NAVIGATE ──
        // If model returned NAVIGATE + HIGHLIGHT_ELEMENT for an element that
        // already exists on the current page, strip the NAVIGATE.
        {
            List<DashboardAssistantCommand> navCommands = validCommands.stream()
                    .filter(c -> "NAVIGATE".equals(c.getType()))
                    .collect(java.util.stream.Collectors.toList());
            List<DashboardAssistantCommand> highlightCommands = validCommands.stream()
                    .filter(c -> "HIGHLIGHT_ELEMENT".equals(c.getType()))
                    .collect(java.util.stream.Collectors.toList());
            if (!navCommands.isEmpty() && !highlightCommands.isEmpty()) {
                boolean hasVisibleTarget = highlightCommands.stream()
                        .anyMatch(c -> c.getElementId() != null && allowedElementIds.contains(c.getElementId()));
                if (hasVisibleTarget) {
                    validCommands.removeAll(navCommands);
                    String warn = "assistant_current_page_highlight";
                    if (!warnings.contains(warn)) warnings.add(warn);
                }
            }
        }

        // If all original commands were rejected, rewrite message as UNSUPPORTED
        boolean allCommandsRejected = modelResponse.getCommands() != null && !modelResponse.getCommands().isEmpty() && validCommands.isEmpty();
        if (allCommandsRejected) {
            boolean hasRefreshWarning = warnings.contains("assistant_unsupported_refresh");
            if (!hasRefreshWarning) {
                warnings.add("assistant_no_valid_command");
            }
            return DashboardAssistantResponse.builder()
                    .responseType("UNSUPPORTED")
                    .message("I couldn't safely match that request to an available dashboard action.")
                    .commands(List.of())
                    .requiresConfirmation(false)
                    .warnings(warnings)
                    .build();
        }

        // Determine responseType
        String responseType = modelResponse.getResponseType();
        if (responseType == null) {
            responseType = validCommands.isEmpty() ? "ANSWER" : "ACTION";
        }

        // ── List-all detection: override model response with backend-generated list ──
        if (isListAllElementsRequest(rawUserMsg)) {
            return listHighlightableItemsResponse(currentVisibleElements);
        }

        // Convert LIST_HIGHLIGHTABLE / LIST_CAPABILITIES to ANSWER responses
        boolean hasListHighlightable = validCommands.stream().anyMatch(c -> "LIST_HIGHLIGHTABLE".equals(c.getType()));
        boolean hasListCapabilities = validCommands.stream().anyMatch(c -> "LIST_CAPABILITIES".equals(c.getType()));
        if (hasListHighlightable || hasListCapabilities) {
            responseType = "ANSWER";
            String listMessage;
            if (hasListHighlightable) {
                listMessage = buildListHighlightableMessage();
            } else {
                listMessage = "I can help you with:\n- Navigating to any dashboard page\n- Finding and highlighting UI elements\n- Explaining alerts with AI\n- Toggling dark/light theme\n- Changing your password\n- Filtering and refreshing data views\n- Searching for alerts and users\n\nTry asking: \"take me to churn\", \"highlight explain AI for anom-000000001179\", \"switch to dark mode\", or \"refresh the forecast\".";
            }
            return DashboardAssistantResponse.builder()
                    .responseType("ANSWER")
                    .message(listMessage)
                    .commands(List.of())
                    .requiresConfirmation(false)
                    .warnings(warnings)
                    .build();
        }

        String message = modelResponse.getMessage() != null
                ? modelResponse.getMessage()
                : "I can help with navigation, searching, and explaining dashboard areas.";

        boolean hasActionableCommand = validCommands.stream().anyMatch(c ->
                !"NO_ACTION".equals(c.getType()) && !"LIST_CAPABILITIES".equals(c.getType()) && !"LIST_HIGHLIGHTABLE".equals(c.getType()));

        if ("ANSWER".equals(responseType)) {
            return DashboardAssistantResponse.builder()
                    .responseType(responseType)
                    .message(message)
                    .commands(validCommands)
                    .requiresConfirmation(false)
                    .warnings(warnings)
                    .build();
        }

        boolean requiresConfirmation = modelResponse.isRequiresConfirmation();
        if (hasActionableCommand && validCommands.stream().anyMatch(c -> "NAVIGATE".equals(c.getType()))) {
            requiresConfirmation = false;
        }

        log.info("ASSISTANT_COMMANDS_VALIDATED responseType={} validCount={} totalModelCommands={} warnings={} commands={}",
                responseType,
                validCommands.size(),
                modelResponse.getCommands() != null ? modelResponse.getCommands().size() : 0,
                warnings,
                validCommands.stream().map(c -> c.getType() + ":" + (c.getElementId() != null ? c.getElementId() : c.getRouteName() != null ? c.getRouteName() : c.getValue() != null ? c.getValue() : "?")).toList());

        return DashboardAssistantResponse.builder()
                .responseType(responseType)
                .message(message)
                .commands(validCommands)
                .requiresConfirmation(requiresConfirmation)
                .warnings(warnings)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DETERMINISTIC FALLBACK — runs before model call and on model failure
    // ─────────────────────────────────────────────────────────────────────────

    // ── Metrics/nav penalty words ────────────────────────────────────────────

    private static final List<String> METRIC_CARD_WORDS = List.of(
            "score", "average", "kpi", "card", "metric", "today", "total",
            "users", "processed", "risk", "rate", "count", "coverage",
            "anomaly", "anomalies", "events", "event"
    );

    private static final List<String> HIGHLIGHT_INTENT_WORDS = List.of(
            "show me", "where can i find", "where can i see", "where should i look",
            "highlight", "point me to", "locate", "where is", "where are",
            "show me where", "here", "find"
    );

    // ── Stop words (tokens to skip when scoring) ─────────────────────────────

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "the", "and", "for", "its", "all", "can", "not", "but", "are",
            "was", "were", "has", "had", "have", "been", "will", "would",
            "could", "should", "may", "might", "shall", "this", "that",
            "with", "from", "they", "them", "their", "what", "when", "where",
            "which", "who", "whom", "how", "show", "here", "there", "card", "me"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // INTENT CLASSIFIER + ACTION PLANNER
    // ─────────────────────────────────────────────────────────────────────────

    private enum AssistantIntent {
        GREETING,
        NAVIGATE,
        HIGHLIGHT_CURRENT_UI,
        HIGHLIGHT_NAVIGATION,
        EXPLAIN_ALERT_AI,
        SEARCH,
        FILTER,
        REFRESH,
        TOGGLE_THEME,
        CHANGE_PASSWORD,
        LIST_CAPABILITIES,
        LIST_HIGHLIGHTABLE,
        UNKNOWN
    }

    private AssistantIntent classifyIntent(String msg, String rawMsg) {
        if (!deterministicActionsEnabled) {
            if (containsAny(rawMsg, "hello", "hi there", "hey", "good morning", "good afternoon",
                    "good evening"))
                return AssistantIntent.GREETING;

            if (containsAny(rawMsg, "what can you do", "capabilities", "features", "available commands", "list commands",
                    "what are your capabilities", "what can i ask", "how can you help"))
                return AssistantIntent.LIST_CAPABILITIES;

            if (containsAny(rawMsg, "highlightable", "list elements", "what can you highlight", "which elements",
                    "show me what you can highlight", "list all items", "what can i interact with"))
                return AssistantIntent.LIST_HIGHLIGHTABLE;

            return AssistantIntent.UNKNOWN;
        }

        // TASK intents (check before GREETING since they may contain keywords like "what can you do")
        if (containsAny(rawMsg, "dark mode", "light mode", "toggle theme", "switch theme", "theme dark", "theme light",
                "enable dark mode", "enable light mode", "turn on dark mode", "turn on light mode"))
            return AssistantIntent.TOGGLE_THEME;

        if (containsAny(rawMsg, "change password", "change my password", "update password", "change the password",
                "modify password", "reset password", "password change",
                "changer mot de passe", "changer mon mot de passe",
                "modifier mot de passe", "modifier mon mot de passe",
                "réinitialiser mot de passe", "réinitialiser mon mot de passe"))
            return AssistantIntent.CHANGE_PASSWORD;

        if (containsAny(rawMsg, "what can you do", "capabilities", "features", "available commands", "list commands",
                "what are your capabilities", "what can i ask", "how can you help"))
            return AssistantIntent.LIST_CAPABILITIES;

        if (containsAny(rawMsg, "highlightable", "list elements", "what can you highlight", "which elements",
                "show me what you can highlight", "list all items", "what can i interact with"))
            return AssistantIntent.LIST_HIGHLIGHTABLE;

        // GREETING
        if (containsAny(rawMsg, "hello", "hi there", "hey", "good morning", "good afternoon",
                "good evening", "are you here", "what can you do", "how can you help"))
            return AssistantIntent.GREETING;

        // EXPLAIN_ALERT_AI
        boolean hasExplain = containsAny(rawMsg, "explain", "why did") && containsAny(rawMsg, "ai", "artificial intelligence");
        boolean hasExplainPhrase = containsAny(rawMsg, "explain with ai", "explain ai", "ai explain", "explain this alert", "explain why");
        if (hasExplain || hasExplainPhrase) return AssistantIntent.EXPLAIN_ALERT_AI;

        // SEARCH
        if (containsAny(rawMsg, "search", "find user", "find alert", "lookup")) return AssistantIntent.SEARCH;

        // FILTER (only when not seeking location)
        if (containsAny(rawMsg, "filter", "show only", "show just") && !isHighlightIntent(msg))
            return AssistantIntent.FILTER;

        // REFRESH (only when not seeking location)
        if (containsAny(rawMsg, "refresh", "reload", "update") && !isHighlightIntent(msg))
            return AssistantIntent.REFRESH;

        // HIGHLIGHT_NAVIGATION: user is asking WHERE to click for a page
        boolean asksWhereToClick = containsAny(rawMsg, "where to click", "where should i click",
                "how to go to", "click to go to", "how to navigate");
        boolean asksWhereIs = containsAny(rawMsg, "where is the", "where is that", "where can i find",
                "where's the") && containsAny(rawMsg, "nav", "link", "tab", "button");
        if (asksWhereToClick || asksWhereIs) return AssistantIntent.HIGHLIGHT_NAVIGATION;

        // NAVIGATE: strong navigation intent
        boolean hasNavIntent = containsAny(rawMsg, "take me", "go to", "navigate",
                "jump to", "switch to", "bring me", "open");
        if (hasNavIntent && !isHighlightIntent(msg)) return AssistantIntent.NAVIGATE;

        // HIGHLIGHT_CURRENT_UI: user asks to find/show something on the current page
        if (isHighlightIntent(msg)) return AssistantIntent.HIGHLIGHT_CURRENT_UI;

        // NAVIGATE even with seeking keywords (e.g. "take me to where i can see anomalies")
        if (hasNavIntent) return AssistantIntent.NAVIGATE;

        return AssistantIntent.UNKNOWN;
    }

    private DashboardAssistantResponse resolveByIntent(DashboardAssistantRequest request) {
        String rawMsg = request.getMessage();
        String msg = normalize(rawMsg);
        if (msg == null || msg.isBlank()) return null;

        String route = request.getCurrentRoute() != null ? request.getCurrentRoute() : "";
        Map<String, String> ctx = request.getCurrentContext() != null ? request.getCurrentContext() : Map.of();

        AssistantIntent intent = classifyIntent(msg, rawMsg);

        if (!deterministicActionsEnabled) {
            switch (intent) {
                case GREETING:
                    return matchGreeting(msg);
                case LIST_CAPABILITIES:
                    return noActionResponse("I can help you with:\n- Navigating to any dashboard page\n- Finding and highlighting UI elements\n- Explaining alerts with AI\n- Toggling dark/light theme\n- Changing your password\n- Filtering and refreshing data views\n- Searching for alerts and users\n\nTry asking: \"take me to churn\", \"highlight explain AI for anom-000000001179\", \"switch to dark mode\", or \"refresh the forecast\".");
                case LIST_HIGHLIGHTABLE:
                    return listHighlightableItemsResponse(request);
                default:
                    return null;
            }
        }

        switch (intent) {
            case GREETING:
                return matchGreeting(msg);

            case EXPLAIN_ALERT_AI:
                return resolveExplainAlertAi(rawMsg, route, ctx, request);

            case HIGHLIGHT_CURRENT_UI:
                return resolveHighlightCurrentUi(request);

            case HIGHLIGHT_NAVIGATION:
                return matchNavHighlight(msg, route);

            case NAVIGATE:
                return matchNavigate(msg, route, ctx, rawMsg);

            case SEARCH:
                return matchSearch(msg, route, ctx, rawMsg);

            case FILTER:
                return matchFilter(msg, route);

            case REFRESH:
                return matchRefresh(msg, route);

            case TOGGLE_THEME:
                return noActionResponse("I'll switch the dashboard theme. You can find the theme toggle in the top toolbar.");

            case CHANGE_PASSWORD:
                return multiCommandResponse("I'll take you to the Account Settings page where you can change your password.",
                        List.of(
                                DashboardAssistantCommand.builder()
                                        .type("NAVIGATE")
                                        .routeName("account")
                                        .message("Opening Account Settings page.")
                                        .build(),
                                DashboardAssistantCommand.builder()
                                        .type("HIGHLIGHT_ELEMENT")
                                        .elementId("account-password-section")
                                        .message("Here is the password change form.")
                                        .build()
                        ));

            case LIST_CAPABILITIES:
                return noActionResponse("I can help you with:\n- Navigating to any dashboard page\n- Finding and highlighting UI elements\n- Explaining alerts with AI\n- Toggling dark/light theme\n- Changing your password\n- Filtering and refreshing data views\n- Searching for alerts and users\n\nTry asking: \"take me to churn\", \"highlight explain AI for anom-000000001179\", \"switch to dark mode\", or \"refresh the forecast\".");

            case LIST_HIGHLIGHTABLE:
                return listHighlightableItemsResponse(request);

            case UNKNOWN:
                return null;
        }
        return null;
    }

    private DashboardAssistantResponse resolveExplainAlertAi(String rawMsg, String route,
                                                              Map<String, String> ctx,
                                                              DashboardAssistantRequest request) {
        String eventId = extractEventId(rawMsg);
        if (eventId == null) eventId = ctx.get("eventId");

        // If already on alert-investigation with eventId, highlight the button
        if ("alert-investigation".equals(route) && eventId != null && !eventId.isBlank()) {
            return highlightResponse("btn-explain-ai",
                    "I'll highlight the Explain with AI button.",
                    "Click this button to get an AI-generated explanation of this alert.");
        }

        // If eventId is available, navigate to investigation and highlight
        if (eventId != null && !eventId.isBlank()) {
            return multiCommandResponse("I'll take you to the alert investigation and highlight the Explain with AI button.",
                    List.of(
                            DashboardAssistantCommand.builder()
                                    .type("NAVIGATE")
                                    .routeName("alert-investigation")
                                    .params(Map.of("eventId", eventId))
                                    .message("Opening alert investigation page.")
                                    .build(),
                            DashboardAssistantCommand.builder()
                                    .type("HIGHLIGHT_ELEMENT")
                                    .elementId("btn-explain-ai")
                                    .message("Click this button to get an AI-generated explanation of this alert.")
                                    .build()
                    ));
        }

        // If on alerts page (no eventId), warn that Explain AI needs an investigation page
        if ("alerts".equals(route) || route.startsWith("alert")) {
            return noActionResponse("Explain with AI is available on an alert investigation page, not on the alerts list. " +
                    "Please provide an alert event ID, or select an alert first.",
                    List.of("assistant_missing_event_id_for_explain_ai"));
        }

        // No eventId available at all
        return noActionResponse("Please provide an alert event ID so I can open the alert investigation page and highlight the Explain with AI button.",
                List.of("assistant_missing_event_id_for_explain_ai"));
    }

    private DashboardAssistantResponse resolveHighlightCurrentUi(DashboardAssistantRequest request) {
        String msg = normalize(request.getMessage());
        String rawMsg = request.getMessage();
        String route = request.getCurrentRoute() != null ? request.getCurrentRoute() : "";
        List<AssistantVisibleElement> visible = request.getVisibleElements();

        // ── Step 1: Try visible element scoring (current route, non-nav) ──
        DashboardAssistantResponse visibleResult = matchVisibleElementsFirst(request, false);
        if (visibleResult != null) return visibleResult;

        // ── Step 2: If user specifically asked for critical alert table/list, use action catalog ──
        if (containsAny(rawMsg, "critical alert table", "critical alert list")) {
            return resolveRecentCriticalAlerts(request);
        }

        // ── Step 3: Fallback to manifest synonym matching (non-nav) ──
        if (route != null && !route.isBlank()) {
            String elementId = registry.findElementBySynonymOnRoute(msg, route);
            if (elementId != null) {
                DashboardAssistantManifest.ManifestElement el = registry.getElement(elementId);
                if (el != null && !"navigation-item".equals(el.getType())) {
                    return highlightResponse(elementId,
                            "I'll highlight the " + el.getLabel() + ".",
                            "Here is the " + el.getLabel() + ".");
                }
            }
        }

        // ── Step 4: When user asked for "recent critical alerts" (without table/list), try action catalog ──
        if (containsAny(rawMsg, "recent critical alert")) {
            return resolveRecentCriticalAlerts(request);
        }

        // ── Step 5: No match found → let legacy fallbacks handle it ──
        return null;
    }

    private DashboardAssistantResponse resolveRecentCriticalAlerts(DashboardAssistantRequest request) {
        String route = request.getCurrentRoute() != null ? request.getCurrentRoute() : "";
        String rawMsg = request.getMessage();

        // If user specifically asked for a table, and no table element exists, navigate to alerts + filter
        boolean wantsTable = containsAny(rawMsg, "table", "list", "grid");

        // Check visible elements for a dedicated recent-critical-alerts card/table
        if (request.getVisibleElements() != null) {
            for (AssistantVisibleElement el : request.getVisibleElements()) {
                if (el.getRouteId() != null && el.getRouteId().equals(route)
                        && el.getId() != null
                        && (el.getId().contains("critical-alert") || el.getId().contains("critical-alert"))) {
                    return highlightResponse(el.getId(),
                            "I'll highlight the recent critical alerts.",
                            "Here are the recent critical alerts.");
                }
            }
        }

        // No dedicated element found: navigate to alerts and filter
        if (wantsTable) {
            return multiCommandResponse("I don't see a recent critical alerts table on this page. " +
                            "I'll open the Alerts page and filter for critical alerts.",
                    List.of(
                            DashboardAssistantCommand.builder()
                                    .type("NAVIGATE")
                                    .routeName("alerts")
                                    .message("Opening Alerts page.")
                                    .build(),
                            DashboardAssistantCommand.builder()
                                    .type("SET_FILTER")
                                    .target("alerts-risk-filter")
                                    .value("CRITICAL")
                                    .message("Filtering to critical alerts.")
                                    .build()
                    ));
        }

        return multiCommandResponse("I'll open the Alerts page and show you critical alerts.",
                List.of(
                        DashboardAssistantCommand.builder()
                                .type("NAVIGATE")
                                .routeName("alerts")
                                .message("Opening Alerts page.")
                                .build(),
                        DashboardAssistantCommand.builder()
                                .type("SET_FILTER")
                                .target("alerts-risk-filter")
                                .value("CRITICAL")
                                .message("Filtering to critical alerts.")
                                .build()
                ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DETERMINISTIC FALLBACK — runs before model call and on model failure
    // ─────────────────────────────────────────────────────────────────────────

    private DashboardAssistantResponse tryDeterministicFallback(DashboardAssistantRequest request) {
        if (!deterministicActionsEnabled) {
            DashboardAssistantResponse intentResult = resolveByIntent(request);
            if (intentResult != null) return intentResult;
            return null;
        }

        String msg = normalize(request.getMessage());
        if (msg == null || msg.isBlank()) return null;

        String route = request.getCurrentRoute() != null ? request.getCurrentRoute() : "";
        Map<String, String> ctx = request.getCurrentContext() != null ? request.getCurrentContext() : Map.of();
        String rawMsg = request.getMessage();

        // Step 1: Intent-based resolution — gates all matching by intent type
        DashboardAssistantResponse intentResult = resolveByIntent(request);
        if (intentResult != null) return intentResult;

        // Step 2: Legacy fallbacks for intents that weren't caught
        if (matchPredictionHighlight(msg, route, ctx) != null)
            return matchPredictionHighlight(msg, route, ctx);

        if (matchDiagnostics(msg, route) != null)
            return matchDiagnostics(msg, route);

        if (matchRuntimeHealth(msg, route) != null)
            return matchRuntimeHealth(msg, route);

        if (matchUser360(msg, route, ctx, rawMsg) != null)
            return matchUser360(msg, route, ctx, rawMsg);

        if (matchAccountHighlight(msg) != null)
            return matchAccountHighlight(msg);

        // Step 3: Page element highlight by synonyms (for intents that bypassed resolveHighlightCurrentUi)
        if (matchHighlightOnCurrentPage(msg, route) != null)
            return matchHighlightOnCurrentPage(msg, route);

        // Step 4: Legacy nav highlight
        if (matchNavHighlight(msg, route) != null)
            return matchNavHighlight(msg, route);

        // Step 5: Legacy nav/search/filter/refresh matchers
        if (matchNavigate(msg, route, ctx, rawMsg) != null)
            return matchNavigate(msg, route, ctx, rawMsg);

        if (matchSearch(msg, route, ctx, rawMsg) != null)
            return matchSearch(msg, route, ctx, rawMsg);

        if (matchFilter(msg, route) != null)
            return matchFilter(msg, route);

        if (matchRefresh(msg, route) != null)
            return matchRefresh(msg, route);

        return null;
    }

    // ── Visible elements semantic matcher ─────────────────────────────────────

    private DashboardAssistantResponse matchVisibleElementsFirst(DashboardAssistantRequest request) {
        return matchVisibleElementsFirst(request, true);
    }

    private DashboardAssistantResponse matchVisibleElementsFirst(DashboardAssistantRequest request, boolean allowNavItems) {
        String msg = normalize(request.getMessage());
        List<AssistantVisibleElement> visible = request.getVisibleElements();
        if (msg == null || visible == null || visible.isEmpty()) return null;

        if (!isHighlightIntent(msg)) return null;

        String route = request.getCurrentRoute() != null ? request.getCurrentRoute() : "";
        String rawMsg = request.getMessage();

        boolean hasMetricWords = containsAny(msg, METRIC_CARD_WORDS.toArray(new String[0]));
        boolean hasExplicitNav = containsAny(rawMsg, "go to", "navigate", "open page",
                "where to click to go to", "take me to", "bring me to");

        List<ScoredElement> scored = new ArrayList<>();
        for (AssistantVisibleElement el : visible) {
            // Filter by nav-item allowance
            if (!allowNavItems && "navigation-item".equals(el.getType())) continue;

            int s = scoreVisibleElement(el, msg, rawMsg, route, hasMetricWords, hasExplicitNav);
            scored.add(new ScoredElement(el, s));
        }

        if (scored.isEmpty()) return null;

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        ScoredElement best = scored.get(0);
        int threshold = 60;
        if (best.score < threshold) return null;

        AssistantVisibleElement bestEl = best.element;
        DashboardAssistantManifest.ManifestElement manifestEl = registry.getElement(bestEl.getId());
        String label = bestEl.getLabel() != null ? bestEl.getLabel()
                : (manifestEl != null ? manifestEl.getLabel() : bestEl.getId());

        return highlightResponse(bestEl.getId(),
                "I'll highlight the " + label + ".",
                "Here is the " + label + ".");
    }

    private int scoreVisibleElement(AssistantVisibleElement el, String msg, String rawMsg,
                                    String route, boolean hasMetricWords, boolean hasExplicitNav) {
        int score = 0;

        String searchable = buildSearchableText(el);

        // ── Token overlap ──
        String[] msgTokens = msg.split(" ");
        java.util.Set<String> msgSignificant = new java.util.HashSet<>();
        for (String t : msgTokens) {
            String clean = t.trim();
            if (clean.length() > 2 && !STOP_WORDS.contains(clean)) {
                msgSignificant.add(clean);
            }
        }
        if (msgSignificant.isEmpty()) return 0;

        int overlap = 0;
        for (String t : msgSignificant) {
            if (searchable.contains(t)) overlap++;
        }
        double overlapRatio = (double) overlap / msgSignificant.size();
        score += (int) (overlapRatio * 100);

        // ── Phrase bonus: bigrams from msg in description ──
        if (el.getDescription() != null) {
            String descNorm = normalize(el.getDescription());
            for (int i = 0; i < msgTokens.length - 1; i++) {
                String bigram = msgTokens[i] + " " + msgTokens[i + 1];
                if (bigram.length() > 3 && descNorm.contains(bigram)) {
                    score += 40;
                    break;
                }
            }
        }

        // ── Phrase bonus: bigrams from msg in id/label ──
        String idLabel = (el.getId() + " " + (el.getLabel() != null ? el.getLabel() : "")).toLowerCase();
        for (int i = 0; i < msgTokens.length - 1; i++) {
            String bigram = msgTokens[i] + " " + msgTokens[i + 1];
            if (bigram.length() > 3 && idLabel.contains(bigram)) {
                score += 60;
                break;
            }
        }

        // ── Current route bonus ──
        if (el.getRouteId() != null && el.getRouteId().equals(route)) {
            score += 30;
        }

        // ── Supports highlight bonus ──
        if (el.getActions() != null && el.getActions().contains("HIGHLIGHT_ELEMENT")) {
            score += 20;
        }

        // ── Nav penalty for metric/card requests ──
        if ("navigation-item".equals(el.getType()) && hasMetricWords && !hasExplicitNav) {
            score -= 100;
        }

        // ── Low-quality match penalty ──
        if (overlapRatio < 0.3) {
            score = 0;
        }

        return Math.max(score, 0);
    }

    private String buildSearchableText(AssistantVisibleElement el) {
        StringBuilder sb = new StringBuilder();
        sb.append(el.getId() != null ? el.getId().toLowerCase() : "");
        sb.append(" ").append(el.getType() != null ? el.getType().toLowerCase() : "");
        sb.append(" ").append(el.getLabel() != null ? normalize(el.getLabel()) : "");
        sb.append(" ").append(el.getDescription() != null ? normalize(el.getDescription()) : "");
        sb.append(" ").append(el.getTextContent() != null ? normalize(el.getTextContent()) : "");
        sb.append(" ").append(el.getRouteId() != null ? el.getRouteId().toLowerCase() : "");
        return sb.toString();
    }

    private boolean isHighlightIntent(String msg) {
        return containsAny(msg, HIGHLIGHT_INTENT_WORDS.toArray(new String[0]));
    }

    private static class ScoredElement {
        final AssistantVisibleElement element;
        final int score;
        ScoredElement(AssistantVisibleElement element, int score) {
            this.element = element;
            this.score = score;
        }
    }

    // ── Greetings ────────────────────────────────────────────────────────────

    private DashboardAssistantResponse matchGreeting(String msg) {
        if (msg.matches(".*\\b(hello|hi there|hey|are you here|good morning|good afternoon|good evening|what can you do|how can you help)\\b.*")) {
            return noActionResponse("Hello! I am your dashboard assistant. I can help you navigate pages, highlight navigation items or page sections, search for alerts and users, apply supported filters, and refresh data views. Try asking: \"take me to churn\", \"where to click for alerts\", \"highlight explain with AI for event anom-000000001179\", \"filter churn to medium risk\", or \"refresh the forecast\".");
        }
        return null;
    }

    // ── Explain AI with eventId extraction from message ──────────────────────

    private DashboardAssistantResponse matchExplainAiWithEventId(String rawMsg, String route, Map<String, String> ctx) {
        String norm = normalize(rawMsg);
        boolean hasExplainKeyword = containsAny(norm, "explain", "why did");
        boolean hasAiKeyword = containsAny(norm, "ai", "artificial intelligence");
        if (!hasExplainKeyword || !hasAiKeyword) {
            if (!containsAny(norm, "explain with ai", "explain ai", "ai explain", "explain this alert", "explain why")) {
                return null;
            }
        }

        String eventId = extractEventId(rawMsg);
        if (eventId == null) {
            eventId = ctx.get("eventId");
        }

        if ("alert-investigation".equals(route)) {
            return highlightResponse("btn-explain-ai",
                    "I'll highlight the Explain with AI button.",
                    "Click this button to get an AI-generated explanation of this alert.");
        }

        if (eventId != null && !eventId.isBlank()) {
            return multiCommandResponse("I'll take you to the alert investigation and highlight the Explain with AI button.",
                    List.of(
                            DashboardAssistantCommand.builder()
                                    .type("NAVIGATE")
                                    .routeName("alert-investigation")
                                    .params(Map.of("eventId", eventId))
                                    .message("Opening alert investigation page.")
                                    .build(),
                            DashboardAssistantCommand.builder()
                                    .type("HIGHLIGHT_ELEMENT")
                                    .elementId("btn-explain-ai")
                                    .message("Click this button to get an AI-generated explanation of this alert.")
                                    .build()
                    ));
        }

        return noActionResponse("Please provide an alert event ID so I can show you the Explain with AI button. For example: \"show me explain AI for anom-000000001179\".");
    }

    private String extractEventId(String text) {
        if (text == null) return null;
        Matcher m = EVENT_ID_PATTERN.matcher(text);
        return m.find() ? m.group().toLowerCase() : null;
    }

    private String extractInsuredId(String text) {
        if (text == null) return null;
        Matcher m = INSURED_ID_PATTERN.matcher(text);
        return m.find() ? m.group().toLowerCase() : null;
    }

    // ── Prediction highlight ─────────────────────────────────────────────────

    private DashboardAssistantResponse matchPredictionHighlight(String msg, String route, Map<String, String> ctx) {
        if (!containsAny(msg, "next event prediction", "next events prediction", "prediction card", "deviation evidence")) {
            return null;
        }
        if ("alert-investigation".equals(route)) {
            return highlightResponse("card-next-event-prediction",
                    "I'll highlight the Next Event Prediction card.",
                    "This card shows next-event predictions and deviation evidence when available.");
        }
        String eventId = ctx.get("eventId");
        if (eventId != null && !eventId.isBlank()) {
            return multiCommandResponse("I'll take you to the alert investigation and highlight the prediction card.",
                    List.of(
                            DashboardAssistantCommand.builder()
                                    .type("NAVIGATE")
                                    .routeName("alert-investigation")
                                    .params(Map.of("eventId", eventId))
                                    .message("Opening alert investigation page.")
                                    .build(),
                            DashboardAssistantCommand.builder()
                                    .type("HIGHLIGHT_ELEMENT")
                                    .elementId("card-next-event-prediction")
                                    .message("This card shows next-event predictions and deviation evidence when available.")
                                    .build()
                    ));
        }
        return noActionResponse("Please provide an alert event ID so I can show you the prediction card.");
    }

    // ── Nav highlight: "where to click to go to X" → HIGHLIGHT nav-{id} ──────

    private DashboardAssistantResponse matchNavHighlight(String msg, String currentRoute) {
        if (!isSeekingLocation(msg)) return null;

        // If message is purely navigation (take me to / go to / open) without seeking keywords,
        // don't highlight — let matchNavigate handle it.
        boolean explicitlySeeks = containsAny(msg, "show me", "show me where", "where to click",
                "where should i", "click to go", "point me", "where is", "where can i");
        if (!explicitlySeeks && !containsAny(msg, "highlight")) {
            if (isNavigationIntent(msg)) return null;
        }

        // Don't highlight nav items when user is asking about explain-with-AI
        if (containsAny(msg, "explain")) return null;

        // Messages with "take me to ... and highlight ..." need multi-command from matchNavigate
        if (isNavigationIntent(msg) && containsAny(msg, "highlight") && !explicitlySeeks) {
            return null;
        }

        for (NavHighlightEntry entry : NAV_HIGHLIGHT_ENTRIES) {
            if (!containsAny(msg, entry.triggerWords)) continue;
            if (entry.routeName != null && entry.routeName.equals(currentRoute)) continue;
            return highlightResponse(entry.elementId,
                    "I'll highlight the " + entry.displayName + " navigation item.",
                    "Click here to open the " + entry.displayName + " page.");
        }
        return null;
    }

    private static boolean isSeekingLocation(String msg) {
        return containsAny(msg, "show me", "click", "find", "locate", "how do i", "how to", "highlight",
                "point me", "where can i", "where can i see", "where can i find", "where is", "where to",
                "where should i", "where do i", "where i");
    }

    private static boolean isNavigationIntent(String msg) {
        return containsAny(msg, "go to", "take me", "open", "navigate", "jump to", "switch to", "bring me", "view that", "the view");
    }

    // ── Diagnostics card ─────────────────────────────────────────────────────

    private DashboardAssistantResponse matchDiagnostics(String msg, String route) {
        if (!containsAny(msg, "diagnostics", "diagnostic card", "diagnostics card")) return null;

        if ("runtime-health".equals(route) || "runtime".equals(route)) {
            return highlightResponse("card-runtime-summary",
                    "I'll highlight the Runtime Health summary card, which shows overall system diagnostics.",
                    "This card shows the overall runtime health status, version, and key feature flags.");
        }

        return navigateThenHighlightResponse("runtime-health", Map.of(),
                "I'll take you to the Runtime Health page and highlight the diagnostics area.",
                "card-runtime-summary",
                "This card shows the overall runtime health status, version, and key feature flags.");
    }

    // ── Runtime health ───────────────────────────────────────────────────────

    private DashboardAssistantResponse matchRuntimeHealth(String msg, String route) {
        boolean wantsKafka = containsAny(msg, "kafka health", "kafka");
        boolean wantsModels = containsAny(msg, "model health", "models health");
        if (!wantsKafka && !wantsModels) return null;

        boolean isNavigationIntent = containsAny(msg, "take me", "go to", "navigate", "jump to", "open", "view that");
        boolean isHighlightIntent = isSeekingLocation(msg);

        if ("runtime-health".equals(route) || "runtime".equals(route)) {
            List<DashboardAssistantCommand> commands = new ArrayList<>();
            if (wantsKafka) {
                commands.add(DashboardAssistantCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("card-kafka-health")
                        .message("This card shows Kafka consumer group health and diagnostics.")
                        .build());
            }
            if (wantsModels) {
                commands.add(DashboardAssistantCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("card-model-health")
                        .message("This card shows model inference health status.")
                        .build());
            }
            if (commands.size() == 1) {
                return highlightResponse(commands.get(0).getElementId(),
                        "I'll highlight the " + (wantsKafka ? "Kafka health" : "Model health") + " card.",
                        commands.get(0).getMessage());
            }
            return multiCommandResponse("I'll highlight the Kafka and Model health cards.", commands);
        }

        if (isNavigationIntent && !isHighlightIntent) {
            return navigateResponse("runtime-health", Map.of(),
                    "Opening Runtime Health page to view " +
                    (wantsKafka && wantsModels ? "Kafka and Model health." :
                     wantsKafka ? "Kafka health." : "Model health."));
        }

        String targetElement = wantsKafka ? "card-kafka-health" : "card-model-health";
        return navigateThenHighlightResponse("runtime-health", Map.of(),
                "I'll take you to the Runtime Health page.",
                targetElement,
                wantsKafka ? "This card shows Kafka consumer group health and diagnostics." : "This card shows model inference health status.");
    }

    // ── User 360 ─────────────────────────────────────────────────────────────

    private DashboardAssistantResponse matchUser360(String msg, String route, Map<String, String> ctx, String rawMsg) {
        if (!containsAny(msg, "user 360", "user360", "user profile", "user search", "special user", "user info", "user details")) return null;

        String insuredId = extractInsuredId(rawMsg);
        if (insuredId == null) {
            insuredId = ctx.get("insuredId");
        }

        if (isSeekingLocation(msg)) {
            return highlightResponse("nav-user360",
                    "I'll highlight the User 360 navigation item.",
                    "Click here to open the User 360 search page and enter an insured ID.");
        }

        if (insuredId != null && !insuredId.isBlank()) {
            return navigateResponse("user-360", Map.of("insuredId", insuredId),
                    "Opening User 360 profile for " + insuredId + ".");
        }

        // Navigation intent without insuredId: open search page and highlight input
        return multiCommandResponse("Opening User 360 search page. Please enter an insured ID.",
                List.of(
                        DashboardAssistantCommand.builder()
                                .type("NAVIGATE")
                                .routeName("user360")
                                .message("Opening User 360 search page.")
                                .build(),
                        DashboardAssistantCommand.builder()
                                .type("HIGHLIGHT_ELEMENT")
                                .elementId("user360-search-input")
                                .message("Enter an insured ID here and click Search to view a profile.")
                                .build()
                ));
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private DashboardAssistantResponse matchSearch(String msg, String route, Map<String, String> ctx, String rawMsg) {
        if (!containsAny(msg, "search", "find", "lookup")) return null;

        String insuredId = extractInsuredId(rawMsg);
        if (insuredId != null) {
            return navigateResponse("user-360", Map.of("insuredId", insuredId),
                    "Opening User 360 profile for " + insuredId + ".");
        }

        String eventId = extractEventId(rawMsg);
        if (eventId != null) {
            return navigateResponse("alert-investigation", Map.of("eventId", eventId),
                    "Opening alert investigation for " + eventId + ".");
        }

        return null;
    }

    // ── Per-page element highlight ───────────────────────────────────────────

    private DashboardAssistantResponse matchHighlightOnCurrentPage(String msg, String route) {
        if (route == null || route.isBlank()) return null;
        // Try manifest synonym matching for elements on this route
        String elementId = registry.findElementBySynonymOnRoute(msg, route);
        if (elementId != null) {
            DashboardAssistantManifest.ManifestElement el = registry.getElement(elementId);
            return highlightResponse(elementId,
                    "I'll highlight the " + (el != null ? el.getLabel() : elementId) + ".",
                    "Here is the " + (el != null ? el.getLabel() : elementId) + ".");
        }
        return null;
    }

    private DashboardAssistantResponse matchAccountHighlight(String msg) {
        if (!isSeekingLocation(msg)) return null;
        if (!containsAny(msg, "account", "account settings", "my account", "my profile", "profile settings")) return null;
        return highlightResponse("btn-user-avatar",
                "I'll highlight your avatar icon. Click it, then select \"Account Settings\" to manage your account.",
                "Click your avatar icon here to open the user menu, then choose Account Settings.");
    }

    // ── Filters ──────────────────────────────────────────────────────────────

    private DashboardAssistantResponse matchFilter(String msg, String route) {
        if (!containsAny(msg, "filter", "show only", "show just")) return null;
        if (isSeekingLocation(msg)) return null;

        String filterId = registry.findFilterBySynonym(msg);
        if (filterId == null) return null;

        DashboardAssistantManifest.ManifestFilter filter = registry.getFilter(filterId);
        if (filter == null) return null;

        String risk = null;
        if (containsAny(msg, "critical")) risk = "CRITICAL";
        else if (containsAny(msg, "high")) risk = "HIGH";
        else if (containsAny(msg, "medium")) risk = "MEDIUM";
        else if (containsAny(msg, "low")) risk = "LOW";

        if (risk == null) return null;

        return DashboardAssistantResponse.builder()
                .message("Applying " + risk + " risk filter to the " + filter.getLabel() + ".")
                .commands(List.of(DashboardAssistantCommand.builder()
                        .type("SET_FILTER")
                        .target(filterId)
                        .value(risk)
                        .message("Filtering by " + risk + " risk.")
                        .build()))
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    private DashboardAssistantResponse matchRefresh(String msg, String route) {
        if (!containsAny(msg, "refresh", "reload", "update")) return null;
        if (isSeekingLocation(msg)) return null;

        // First try synonym matching against all refresh targets
        String target = registry.findRefreshTargetBySynonym(msg);
        if (target == null) {
            // Try alias/normalization
            target = registry.normalizeRefreshTarget(msg);
        }
        if (target == null && route != null) {
            // Fallback: if current route has a refresh target, use it.
            // First normalize the route name (e.g. "SecurityOverviewPage" -> "security-overview")
            String normalizedRoute = registry.normalizeRouteName(route);
            String routeId = normalizedRoute != null ? normalizedRoute : route;
            if (registry.isAllowedRefreshTarget(routeId)) {
                target = routeId;
            } else {
                // Also check if route ID matches a refresh target's routeId
                for (DashboardAssistantManifest.ManifestRefreshTarget rt : registry.getManifest().getRefreshTargets()) {
                    if (routeId.equals(rt.getRouteId())) {
                        target = rt.getId();
                        break;
                    }
                }
            }
        }
        if (target == null) return null;

        DashboardAssistantManifest.ManifestRefreshTarget rt = registry.getRefreshTarget(target);
        String label = rt != null ? rt.getLabel() : target;

        return DashboardAssistantResponse.builder()
                .message("Refreshing " + label + " data.")
                .commands(List.of(DashboardAssistantCommand.builder()
                        .type("REFRESH_VIEW")
                        .target(target)
                        .message("Refreshing " + target + " view data.")
                        .build()))
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private DashboardAssistantResponse matchNavigate(String msg, String route, Map<String, String> ctx, String rawMsg) {
        // If the user asks "where to click to go to X", "how to go to X", "show me where to go",
        // the primary intent is location-seeking (highlight nav item), not navigation.
        // But "take me to X" / "open X" are strong navigation commands even if seeking keywords appear.
        boolean hasStrongNav = containsAny(msg, "take me", "take me to", "open", "bring me", "jump to", "switch to");
        boolean hasSeekingNav = containsAny(msg, "where to click", "where should i", "show me where", "how to go", "where is", "where can i find", "where can i see", "where can i look", "where do i", "how do i");
        if (hasSeekingNav && !hasStrongNav && !containsAny(msg, "highlight", "and highlight")) {
            return null;
        }

        String routeId = registry.findRouteBySynonym(msg);
        if (routeId == null && registry.isAllowedRouteName(msg.trim())) {
            for (DashboardAssistantManifest.ManifestRoute mr : registry.getManifest().getRoutes()) {
                if (mr.getRouteName() != null && containsAny(msg, mr.getRouteName().toLowerCase())) {
                    routeId = mr.getId();
                    break;
                }
            }
        }
        // Fuzzy match: try extracting the target word after navigation verbs and fuzzy-match against routes
        if (routeId == null) {
            java.util.regex.Matcher navMatcher = java.util.regex.Pattern.compile(
                    "(?:take me|go|navigate|open|switch|jump|bring me)\\s+(?:to\\s+)?(.{3,30})$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(rawMsg);
            if (navMatcher.find()) {
                String target = navMatcher.group(1).toLowerCase().trim();
                routeId = fuzzyMatchRoute(target);
            }
        }
        if (routeId == null) return null;

        DashboardAssistantManifest.ManifestRoute manifestRoute = registry.getRoute(routeId);
        Map<String, String> params = extractParamsForRoute(routeId, ctx, rawMsg);
        String label = manifestRoute != null ? manifestRoute.getLabel() : routeId;

        // Prefer user-360 (detail page) over user360 (search page) when insuredId is available
        if ("user360".equals(routeId)) {
            String insuredId = extractInsuredId(rawMsg);
            if (insuredId == null && ctx != null) insuredId = ctx.get("insuredId");
            if (insuredId != null && !insuredId.isBlank()) {
                routeId = "user-360";
                manifestRoute = registry.getRoute(routeId);
                label = manifestRoute != null ? manifestRoute.getLabel() : routeId;
                params = extractParamsForRoute(routeId, ctx, rawMsg);
            }
        }

        // Only include highlight when the message explicitly asks for it alongside navigation
        boolean wantsHighlight = containsAny(msg, "highlight", "and highlight", "show me where");
        boolean alreadyOnRoute = routeId.equals(route);

        // When navigating to user360 search page without insuredId, also highlight the search input
        if ("user360".equals(routeId) && !alreadyOnRoute) {
            return multiCommandResponse("Opening User 360 Search page. Please enter an insured ID.",
                    List.of(
                            DashboardAssistantCommand.builder()
                                    .type("NAVIGATE")
                                    .routeName("user360")
                                    .message("Opening User 360 search page.")
                                    .build(),
                            DashboardAssistantCommand.builder()
                                    .type("HIGHLIGHT_ELEMENT")
                                    .elementId("user360-search-input")
                                    .message("Enter an insured ID here and click Search to view a profile.")
                                    .build()
                    ));
        }

        if (wantsHighlight) {
            // Only consider elements on the resolved route to avoid cross-page matches
            String elementId = registry.findElementBySynonymOnRoute(msg, routeId);
            if (elementId != null) {
                DashboardAssistantManifest.ManifestElement el = registry.getElement(elementId);
                if (alreadyOnRoute) {
                    return highlightResponse(elementId,
                            "I'll highlight the " + (el != null ? el.getLabel() : elementId) + ".",
                            "Here is the " + (el != null ? el.getLabel() : elementId) + ".");
                }
                return multiCommandResponse("Opening " + label + " and highlighting the " + (el != null ? el.getLabel() : elementId) + ".",
                        List.of(
                                DashboardAssistantCommand.builder()
                                        .type("NAVIGATE")
                                        .routeName(routeId)
                                        .params(params)
                                        .message("Opening " + label + " page.")
                                        .build(),
                                DashboardAssistantCommand.builder()
                                        .type("HIGHLIGHT_ELEMENT")
                                        .elementId(elementId)
                                        .message("Here is the " + (el != null ? el.getLabel() : elementId) + ".")
                                        .build()
                        ));
            }
            // Element not found in manifest; let highlight-specific matchers or model handle it
            return null;
        }

        // Don't navigate if already on the target route
        if (alreadyOnRoute) return null;

        return navigateResponse(routeId, params, "Opening " + label + ".");
    }

    private String buildListHighlightableMessage() {
        List<AssistantVisibleElement> visible = currentVisibleElements;
        if (visible == null || visible.isEmpty()) {
            return "I do not currently see any assistant-registered visible elements on this page.";
        }
        StringBuilder sb = new StringBuilder("On this page I can highlight or interact with:\n");
        for (int i = 0; i < visible.size(); i++) {
            AssistantVisibleElement ve = visible.get(i);
            String label = ve.getLabel() != null ? ve.getLabel() : ve.getId();
            String type = ve.getType() != null ? ve.getType() : "element";
            sb.append("- ").append(label).append(" (").append(type).append(")");
            if (i < visible.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // ── List highlightable items response ────────────────────────────────────

    private DashboardAssistantResponse listHighlightableItemsResponse(DashboardAssistantRequest request) {
        List<AssistantVisibleElement> visible = request.getVisibleElements();

        if (visible == null || visible.isEmpty()) {
            return DashboardAssistantResponse.builder()
                    .message("I do not currently see any assistant-registered visible elements on this page.")
                    .commands(List.of())
                    .requiresConfirmation(false)
                    .warnings(List.of("assistant_no_visible_elements"))
                    .build();
        }

        StringBuilder sb = new StringBuilder("On this page I can highlight or interact with:\n");
        for (int i = 0; i < visible.size(); i++) {
            AssistantVisibleElement ve = visible.get(i);
            String label = ve.getLabel() != null ? ve.getLabel() : ve.getId();
            String type = ve.getType() != null ? ve.getType() : "element";
            sb.append("- ").append(label).append(" (").append(type).append(")");
            if (i < visible.size() - 1) {
                sb.append("\n");
            }
        }

        return DashboardAssistantResponse.builder()
                .message(sb.toString())
                .commands(List.of())
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    // ── Overloaded version that takes visible elements directly ──

    private DashboardAssistantResponse listHighlightableItemsResponse(List<AssistantVisibleElement> visible) {
        if (visible == null || visible.isEmpty()) {
            return DashboardAssistantResponse.builder()
                    .message("I do not currently see any assistant-registered visible elements on this page.")
                    .commands(List.of())
                    .requiresConfirmation(false)
                    .warnings(List.of("assistant_no_visible_elements"))
                    .build();
        }

        StringBuilder sb = new StringBuilder("On this page I can highlight or interact with:\n");
        for (int i = 0; i < visible.size(); i++) {
            AssistantVisibleElement ve = visible.get(i);
            String label = ve.getLabel() != null ? ve.getLabel() : ve.getId();
            String type = ve.getType() != null ? ve.getType() : "element";
            sb.append("- ").append(label).append(" (").append(type).append(")");
            if (i < visible.size() - 1) {
                sb.append("\n");
            }
        }

        return DashboardAssistantResponse.builder()
                .message(sb.toString())
                .commands(List.of())
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    // ── List-all detection ──

    private static boolean isListAllElementsRequest(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase().trim();
        return lower.contains("list all visible elements")
            || lower.contains("list all items")
            || lower.contains("list all element")
            || lower.contains("list me all")
            || lower.contains("list everything")
            || lower.contains("list every item")
            || lower.contains("which elements can you highlight")
            || lower.contains("what can you highlight")
            || lower.contains("which items can you interact with")
            || lower.contains("what can you highlight here")
            || lower.contains("show all visible elements")
            || lower.contains("list all elements")
            || lower.contains("show me what you can")
            || lower.contains("all items you can highlight")
            || lower.contains("what can you do here");
    }

    // ── Find refresh button in current visible elements ──

    private String findRefreshButtonInVisible() {
        if (currentVisibleElements == null) return null;
        for (AssistantVisibleElement ve : currentVisibleElements) {
            String id = ve.getId();
            if (id != null && (id.contains("refresh") || id.contains("reload"))) {
                return id;
            }
        }
        return null;
    }

    // ── Fuzzy match element against visible elements ──

    private String fuzzyMatchElementAgainstVisible(String elementId, String message,
                                                     java.util.Set<String> allowedElementIds) {
        if (allowedElementIds == null || allowedElementIds.isEmpty()) return null;

        // Build normalized search terms from the elementId and message
        String search = (elementId != null ? elementId : "") + " " + (message != null ? message : "");
        String lower = search.toLowerCase().trim();

        // Score each visible element by how well it matches the search terms
        String bestMatch = null;
        int bestScore = 0;

        for (String candidateId : allowedElementIds) {
            String candidateLower = candidateId.toLowerCase();
            int score = 0;

            // Direct substring match
            if (lower.contains(candidateLower) || candidateLower.contains(lower.replace(" ", "-"))) {
                score += 10;
            }

            // Word-level matching
            String[] searchWords = lower.split("\\s+");
            for (String word : searchWords) {
                if (word.length() > 2 && candidateLower.contains(word)) {
                    score++;
                }
            }

            // Specific heuristics for table/KPI disambiguation
            boolean wantsTable = lower.contains("table") || lower.contains("grid") || lower.contains("list");
            boolean isTable = candidateLower.contains("table") || candidateLower.contains("grid");
            if (wantsTable && isTable) score += 5;
            if (wantsTable && !isTable) score -= 3;

            boolean wantsKpi = lower.contains("kpi") || lower.contains("metric");
            boolean isKpi = candidateLower.contains("kpi") || candidateLower.contains("metric");
            if (wantsKpi && isKpi) score += 2;
            if (wantsKpi && !isKpi) score -= 1;

            // Mute all heuristic bonuses if no direct or word-level match exists
            if (score <= 0) continue;
            if (wantsKpi && !isKpi) score -= 1;

            if (score > bestScore) {
                bestScore = score;
                bestMatch = candidateId;
            }
        }

        // Only return a match if the score is meaningful
        return bestScore >= 3 ? bestMatch : null;
    }

    // ── Helper methods ───────────────────────────────────────────────────────

    private static boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        return s.toLowerCase().trim()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static DashboardAssistantResponse highlightResponse(String elementId, String message, String commandMessage) {
        return DashboardAssistantResponse.builder()
                .message(message)
                .commands(List.of(DashboardAssistantCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId(elementId)
                        .message(commandMessage)
                        .build()))
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    private static DashboardAssistantResponse navigateResponse(String routeName, Map<String, String> params, String commandMessage) {
        DashboardAssistantCommand.DashboardAssistantCommandBuilder builder = DashboardAssistantCommand.builder()
                .type("NAVIGATE")
                .routeName(routeName)
                .message(commandMessage);
        if (params != null && !params.isEmpty()) {
            builder.params(params);
        }
        return DashboardAssistantResponse.builder()
                .message(commandMessage)
                .commands(List.of(builder.build()))
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    private static DashboardAssistantResponse navigateThenHighlightResponse(String routeName, Map<String, String> params,
                                                                            String message, String elementId, String elementMessage) {
        return DashboardAssistantResponse.builder()
                .message(message)
                .commands(List.of(
                        DashboardAssistantCommand.builder()
                                .type("NAVIGATE")
                                .routeName(routeName)
                                .params(params)
                                .message("Opening " + routeName.replace("-", " ") + " page.")
                                .build(),
                        DashboardAssistantCommand.builder()
                                .type("HIGHLIGHT_ELEMENT")
                                .elementId(elementId)
                                .message(elementMessage)
                                .build()
                ))
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    private static DashboardAssistantResponse multiCommandResponse(String message, List<DashboardAssistantCommand> commands) {
        return DashboardAssistantResponse.builder()
                .message(message)
                .commands(commands)
                .requiresConfirmation(false)
                .warnings(List.of())
                .build();
    }

    private static DashboardAssistantResponse noActionResponse(String message) {
        return noActionResponse(message, List.of());
    }

    private static DashboardAssistantResponse noActionResponse(String message, List<String> warnings) {
        return DashboardAssistantResponse.builder()
                .message(message)
                .commands(List.of(DashboardAssistantCommand.builder()
                        .type("NO_ACTION")
                        .message(message)
                        .build()))
                .requiresConfirmation(false)
                .warnings(warnings != null ? warnings : List.of())
                .build();
    }

    private static Map<String, String> extractParamsForRoute(String routeName, Map<String, String> ctx, String rawMsg) {
        Map<String, String> params = new LinkedHashMap<>();
        if ("alert-investigation".equals(routeName)) {
            String eventId = null;
            if (rawMsg != null) {
                Matcher m = EVENT_ID_PATTERN.matcher(rawMsg);
                if (m.find()) eventId = m.group().toLowerCase();
            }
            if (eventId == null && ctx != null) eventId = ctx.get("eventId");
            if (eventId != null && !eventId.isBlank()) {
                params.put("eventId", eventId);
            }
        }
        if ("user-360".equals(routeName)) {
            String insuredId = null;
            if (rawMsg != null) {
                Matcher m = INSURED_ID_PATTERN.matcher(rawMsg);
                if (m.find()) insuredId = m.group().toLowerCase();
            }
            if (insuredId == null && ctx != null) insuredId = ctx.get("insuredId");
            if (insuredId != null && !insuredId.isBlank()) {
                params.put("insuredId", insuredId);
            }
        }
        return params;
    }

    // ── Nav highlight entries ────────────────────────────────────────────────

    private static class NavHighlightEntry {
        final String elementId;
        final String displayName;
        final String routeName;
        final String[] triggerWords;
        NavHighlightEntry(String elementId, String displayName, String routeName, String... triggerWords) {
            this.elementId = elementId;
            this.displayName = displayName;
            this.routeName = routeName;
            this.triggerWords = triggerWords;
        }
    }

    private static final List<NavHighlightEntry> NAV_HIGHLIGHT_ENTRIES = List.of(
            new NavHighlightEntry("nav-alerts", "Alerts", "alerts",
                    "alert", "anomalies", "anomaly"),
            new NavHighlightEntry("nav-security-overview", "Security Overview", "security-overview",
                    "overview", "security overview", "dashboard"),
            new NavHighlightEntry("nav-user360", "User 360", null,
                    "user 360", "user360", "user profile"),
            new NavHighlightEntry("nav-churn", "Churn", "churn",
                    "churn"),
            new NavHighlightEntry("nav-forecast", "Forecast", "forecast",
                    "forecast"),
            new NavHighlightEntry("nav-runtime", "Runtime Health", null,
                    "runtime", "health section", "health", "diagnostics")
    );

    // ── Debug metadata ───────────────────────────────────────────────────────

    private void addDebugMetadata(DashboardAssistantResponse response, AssistantModelResponse modelResponse, DashboardAssistantRequest request) {
        addDebugMetadata(response, modelResponse, request, 0, 0, 0, 0, null, null, null, null);
    }

    private void addDebugMetadata(DashboardAssistantResponse response, AssistantModelResponse modelResponse, DashboardAssistantRequest request, String decisionSourceOverride) {
        addDebugMetadata(response, modelResponse, request, 0, 0, 0, 0, decisionSourceOverride, null, null, null);
    }

    private void addDebugMetadata(DashboardAssistantResponse response, AssistantModelResponse modelResponse, DashboardAssistantRequest request, long latencyMs, int promptChars, int requestJsonBytes, int candidateCount) {
        addDebugMetadata(response, modelResponse, request, latencyMs, promptChars, requestJsonBytes, candidateCount, null, null, null, null);
    }

    private void addDebugMetadata(DashboardAssistantResponse response, AssistantModelResponse modelResponse, DashboardAssistantRequest request, long latencyMs, int promptChars, int requestJsonBytes, int candidateCount, String decisionSourceOverride) {
        addDebugMetadata(response, modelResponse, request, latencyMs, promptChars, requestJsonBytes, candidateCount, decisionSourceOverride, null, null, null);
    }

    private void addDebugMetadata(
            DashboardAssistantResponse response,
            AssistantModelResponse modelResponse,
            DashboardAssistantRequest request,
            long latencyMs,
            int promptChars,
            int requestJsonBytes,
            int candidateCount,
            String decisionSourceOverride,
            String actualModelUsed,
            List<Map<String, Object>> attemptedModels,
            String fallbackReason) {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("timestamp", Instant.now().toString());
        debug.put("currentRoute", request.getCurrentRoute());
        debug.put("primaryModel", model);
        debug.put("actualModelUsed", actualModelUsed != null ? actualModelUsed : model);
        debug.put("baseUrl", baseUrl);
        debug.put("guidedJson", isGuidedJsonEnabled());
        debug.put("guidedJsonMode", guidedJsonMode());
        debug.put("payloadMode", isGuidedJsonEnabled() ? "nvext-guided" : "plain-openai");
        debug.put("maxTokens", maxTokens);
        debug.put("timeoutMs", timeoutMs);
        debug.put("candidateCount", candidateCount);
        debug.put("fallbackUsed", fallbackReason != null);
        if (fallbackReason != null) {
            debug.put("fallbackReason", fallbackReason);
        }
        if (attemptedModels != null && !attemptedModels.isEmpty()) {
            debug.put("attemptedModels", attemptedModels);
        }

        if (modelResponse != null) {
            debug.put("decisionSource", "model");
            debug.put("modelCalled", true);
            debug.put("latencyMs", latencyMs > 0 ? latencyMs : 1);
            debug.put("promptChars", promptChars > 0 ? promptChars : 1);
            debug.put("requestJsonBytes", requestJsonBytes > 0 ? requestJsonBytes : 1);
            debug.put("rawModelMessage", modelResponse.getMessage());
            List<String> rawModelCommands = new ArrayList<>();
            if (modelResponse.getCommands() != null) {
                for (AssistantModelResponse.AssistantModelCommand cmd : modelResponse.getCommands()) {
                    String commandDesc = cmd.getType();
                    if (cmd.getTarget() != null) commandDesc += ":" + cmd.getTarget();
                    else if (cmd.getElementId() != null) commandDesc += ":" + cmd.getElementId();
                    else if (cmd.getRouteName() != null) commandDesc += ":" + cmd.getRouteName();
                    rawModelCommands.add(commandDesc);
                }
            }
            debug.put("rawModelCommands", rawModelCommands);

            List<String> validatedCommands = new ArrayList<>();
            if (response.getCommands() != null) {
                for (DashboardAssistantCommand cmd : response.getCommands()) {
                    String cmdDesc = cmd.getType();
                    if (cmd.getTarget() != null) cmdDesc += ":" + cmd.getTarget();
                    else if (cmd.getElementId() != null) cmdDesc += ":" + cmd.getElementId();
                    else if (cmd.getRouteName() != null) cmdDesc += ":" + cmd.getRouteName();
                    validatedCommands.add(cmdDesc);
                }
            }
            debug.put("validatedCommands", validatedCommands);

            List<String> rejectedCommands = new ArrayList<>(rawModelCommands);
            rejectedCommands.removeAll(validatedCommands);
            debug.put("rejectedCommands", rejectedCommands);
        } else {
            debug.put("decisionSource", decisionSourceOverride != null ? decisionSourceOverride : "deterministic-system");
            debug.put("modelCalled", false);
            debug.put("latencyMs", latencyMs);
            debug.put("promptChars", promptChars);
            debug.put("requestJsonBytes", requestJsonBytes);
        }

        List<AssistantVisibleElement> visible = request.getVisibleElements();
        if (visible != null) {
            debug.put("visibleElementCount", visible.size());
            List<String> allIds = visible.stream().map(AssistantVisibleElement::getId).toList();
            debug.put("visibleElementIds", allIds);
            debug.put("candidateIds", allIds);
            debug.put("omittedVisibleElementIds", List.of());
        } else {
            debug.put("visibleElementCount", 0);
            debug.put("visibleElementIds", List.of());
            debug.put("candidateIds", List.of());
            debug.put("omittedVisibleElementIds", List.of());
        }

        if (!response.getWarnings().isEmpty()) {
            debug.put("validationWarnings", response.getWarnings());
        }

        response.setDebug(debug);
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private void logAssistantResponse(DashboardAssistantRequest request, DashboardAssistantResponse response, Instant start) {
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        log.info("ASSISTANT_RESPONSE message=\"{}\" route={} commands={} warnings={} latencyMs={}",
                sanitize(request != null ? request.getMessage() : null),
                request != null ? request.getCurrentRoute() : null,
                response != null && response.getCommands() != null ? response.getCommands().size() : 0,
                response != null && response.getWarnings() != null ? response.getWarnings() : List.of(),
                latencyMs);
    }

    private String sanitize(String s) {
        return s != null ? s.replace('\n', ' ').replace('\r', ' ') : null;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ── Fuzzy route matching ────────────────────────────────────────────────

    private String fuzzyMatchRoute(String input) {
        if (input == null || input.isBlank()) return null;
        String normalized = input.toLowerCase().trim();

        // Try direct match via registry normalization first
        String canonical = registry.normalizeRouteName(input);
        if (canonical != null) return canonical;

        // Levenshtein matching over route IDs and route names
        String bestMatch = null;
        int bestDist = Integer.MAX_VALUE;
        for (DashboardAssistantManifest.ManifestRoute route : registry.getAllRoutes()) {
            int dist = levenshteinDistance(normalized, route.getId().toLowerCase());
            if (dist < bestDist) {
                bestDist = dist;
                bestMatch = route.getId();
            }
            if (route.getRouteName() != null) {
                String routeNameLower = route.getRouteName().toLowerCase();
                dist = levenshteinDistance(normalized, routeNameLower);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestMatch = route.getId();
                }
                // Also check without spaces
                dist = levenshteinDistance(normalized, routeNameLower.replace(" ", ""));
                if (dist < bestDist) {
                    bestDist = dist;
                    bestMatch = route.getId();
                }
            }
        }
        // Accept match if distance is <= threshold (2 for short strings, up to 3 for longer)
        if (bestMatch != null && bestDist <= Math.max(2, normalized.length() / 3)) {
            return bestMatch;
        }
        return null;
    }

    private static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // ── NIM Probe ────────────────────────────────────────────────────

    public Map<String, Object> nimProbe(String message, String modelOverride, boolean useGuided, boolean dumpRequestShape) {
        Map<String, Object> result = new LinkedHashMap<>();
        String actualModel = modelOverride != null ? modelOverride : model;
        result.put("model", actualModel);
        result.put("guidedJson", useGuided);
        result.put("guidedJsonRequested", useGuided);
        result.put("guidedJsonApplied", false);
        String payloadMode = useGuided ? "nvext-guided" : "plain-openai";
        result.put("payloadMode", payloadMode);
        long start = 0;

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", actualModel);
            body.put("temperature", temperature);
            body.put("top_p", topP);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", message);
            messages.add(userMessage);
            body.put("messages", messages);

            if (useGuided) {
                Map<String, Object> nvext = new LinkedHashMap<>();
                Map<String, Object> guidedJson = new LinkedHashMap<>();
                guidedJson.put("type", "object");
                guidedJson.put("properties", Map.of(
                        "responseType", Map.of("type", "string", "enum", List.of("ANSWER", "ACTION", "MIXED", "CLARIFICATION", "UNSUPPORTED")),
                        "message", Map.of("type", "string"),
                        "commands", Map.of("type", "array",
                                "items", Map.of("type", "object",
                                        "properties", Map.of(
                                                "type", Map.of("type", "string", "enum", List.of("TOGGLE_THEME", "NO_ACTION")),
                                                "value", Map.of("type", List.of("string", "null")),
                                                "message", Map.of("type", List.of("string", "null"))
                                        ),
                                        "required", List.of("type")
                                )
                        ),
                        "requiresConfirmation", Map.of("type", "boolean")
                ));
                guidedJson.put("required", List.of("responseType", "message", "commands", "requiresConfirmation"));
                nvext.put("guided_json", guidedJson);
                body.put("nvext", nvext);
            }

            String jsonBody = toJson(body);

            if (dumpRequestShape) {
                // Return request shape without sending to API
                Map<String, Object> shape = new LinkedHashMap<>(body);
                shape.remove("nvext"); // still show nvext was there but don't include full schema
                result.put("requestShape", shape);
                result.put("requestJsonBytes", jsonBody.length());
                result.put("payloadMode", payloadMode);
                result.put("latencyMs", 0);
                result.put("httpStatus", 200);
                result.put("rawContent", null);
                result.put("error", null);
                result.put("parseOk", false);
                result.put("guidedJsonApplied", false);
                return result;
            }

            start = System.nanoTime();
            String rawResponse = sendRequestRaw(jsonBody);
            long elapsedNs = System.nanoTime() - start;
            result.put("latencyMs", TimeUnit.NANOSECONDS.toMillis(elapsedNs));
            result.put("httpStatus", 200);
            result.put("requestJsonBytes", jsonBody.length());

            JsonNode root = objectMapper.readTree(rawResponse);
            String content = root.at("/choices/0/message/content").asText(null);
            result.put("rawContent", content != null ? content : "");
            result.put("error", null);

            // Validate guided JSON was applied
            if (useGuided) {
                if (content != null) {
                    try {
                        JsonNode parsed = objectMapper.readTree(content);
                        // Guided JSON should produce a valid JSON object matching the schema
                        if (parsed.has("responseType") && parsed.has("message")) {
                            result.put("guidedJsonApplied", true);
                            result.put("parseOk", true);
                        } else {
                            result.put("guidedJsonApplied", false);
                            result.put("parseOk", false);
                            result.put("error", "guided_json_not_applied");
                        }
                    } catch (Exception e) {
                        result.put("guidedJsonApplied", false);
                        result.put("parseOk", false);
                        result.put("error", "guided_json_not_applied");
                    }
                } else {
                    result.put("guidedJsonApplied", false);
                    result.put("parseOk", false);
                    result.put("error", "guided_json_not_applied");
                }
            } else {
                // No guided JSON requested — just confirm content was received
                result.put("parseOk", content != null);
            }
        } catch (Exception e) {
            long elapsedNs = System.nanoTime() - start;
            result.put("latencyMs", TimeUnit.NANOSECONDS.toMillis(elapsedNs));
            result.put("httpStatus", extractHttpStatus(e));
            result.put("rawContent", null);
            result.put("error", e.getMessage());
            result.put("parseOk", false);
            result.put("requestJsonBytes", 0);
            if (useGuided) {
                result.put("guidedJsonApplied", false);
            }
        }
        return result;
    }

    private int extractHttpStatus(Exception e) {
        if (e instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().value();
        }
        if (e.getMessage() != null && e.getMessage().contains("timeout")) return 408;
        if (e.getMessage() != null && e.getMessage().contains("Failed to resolve")) return 502;
        return 500;
    }

    // ── Updated A/B Benchmark ────────────────────────────────────────

    public List<Map<String, Object>> runBenchmark(String prompt, List<String> modelsOverride) {
        List<String> models = modelsOverride != null && !modelsOverride.isEmpty()
                ? modelsOverride
                : getCandidateModels();
        List<Map<String, Object>> results = new ArrayList<>();
        String systemPrompt = buildBenchmarkSystemPrompt();
        String tinyPrompt = "Return JSON: {\"message\":\"ok\",\"commands\":[],\"requiresConfirmation\":false}";

        for (String candidateModel : models) {
            // Mode C: no guided_json, dashboard prompt with system prompt
            Map<String, Object> modeC = runSingleBenchmark(candidateModel, prompt, false, "C", systemPrompt);
            results.add(modeC);
        }

        // Add summary entry
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", "summary");
        summary.put("models", models);
        String fastestModel = null;
        long fastestLatency = Long.MAX_VALUE;
        String bestValidRateModel = null;
        double bestValidRate = 0;
        String lowestTimeoutModel = null;
        long lowestTimeoutRate = Long.MAX_VALUE;

        for (Map<String, Object> r : results) {
            String modelName = (String) r.get("model");
            long latency = ((Number) r.getOrDefault("latencyMs", 0)).longValue();
            boolean timeout = Boolean.TRUE.equals(r.get("timeout"));
            boolean parseOk = Boolean.TRUE.equals(r.get("parseOk"));

            if (!timeout && parseOk && latency > 0 && latency < fastestLatency) {
                fastestLatency = latency;
                fastestModel = modelName;
            }
            if (parseOk && !timeout && latency <= lowestTimeoutRate) {
                lowestTimeoutRate = latency;
                lowestTimeoutModel = modelName;
            }
        }

        summary.put("recommendedModel", model);
        summary.put("fastestSuccessfulModel", fastestModel);
        summary.put("lowestTimeoutModel", lowestTimeoutModel);
        summary.put("candidateModels", models);
        results.add(summary);
        return results;
    }

    private Map<String, Object> runSingleBenchmark(String modelName, String userPrompt, boolean useGuided, String mode) {
        return runSingleBenchmark(modelName, userPrompt, useGuided, mode, null);
    }

    private Map<String, Object> runSingleBenchmark(String modelName, String userPrompt, boolean useGuided, String mode, String systemPrompt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", modelName);
        result.put("mode", mode);
        result.put("guidedJson", useGuided);
        long start = System.nanoTime();
        int requestJsonBytes = 0;

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("temperature", temperature);
            body.put("top_p", topP);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);

            List<Map<String, Object>> messages = new ArrayList<>();
            if (systemPrompt != null) {
                Map<String, Object> systemMessage = new LinkedHashMap<>();
                systemMessage.put("role", "system");
                systemMessage.put("content", systemPrompt);
                messages.add(systemMessage);
            }
            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
            messages.add(userMessage);
            body.put("messages", messages);

            if (useGuided) {
                Map<String, Object> nvext = new LinkedHashMap<>();
                Map<String, Object> guidedJson = new LinkedHashMap<>();
                guidedJson.put("type", "object");
                guidedJson.put("properties", buildGuidedJsonProperties());
                guidedJson.put("required", List.of("responseType", "message", "commands", "requiresConfirmation"));
                nvext.put("guided_json", guidedJson);
                body.put("nvext", nvext);
            }

            String jsonBody = toJson(body);
            requestJsonBytes = jsonBody.length();
            String rawResponse = sendRequestRaw(jsonBody);
            long elapsedNs = System.nanoTime() - start;
            result.put("latencyMs", TimeUnit.NANOSECONDS.toMillis(elapsedNs));
            result.put("timeout", false);
            result.put("requestJsonBytes", requestJsonBytes);

            try {
                AssistantModelResponse parsed = parseModelResponse(rawResponse);
                result.put("parseOk", true);
                result.put("validatedCommandCount", parsed.getCommands() != null ? parsed.getCommands().size() : 0);
                result.put("responseChars", rawResponse != null ? rawResponse.length() : 0);
            } catch (Exception e) {
                result.put("parseOk", false);
                result.put("parseError", e.getMessage());
                result.put("responseChars", rawResponse != null ? rawResponse.length() : 0);
            }
        } catch (Exception e) {
            long elapsedNs = System.nanoTime() - start;
            result.put("latencyMs", TimeUnit.NANOSECONDS.toMillis(elapsedNs));
            result.put("timeout", e.getMessage() != null && e.getMessage().contains("timeout"));
            result.put("error", e.getMessage());
            result.put("parseOk", false);
            result.put("requestJsonBytes", requestJsonBytes);
        }
        return result;
    }

    private String buildBenchmarkSystemPrompt() {
        return "You are a helpful dashboard assistant. "
                + "Respond with a JSON object containing 'message' (string), "
                + "'commands' (array of {type: string, routeName?: string, elementId?: string, target?: string, value?: string, message?: string}), "
                + "and 'requiresConfirmation' (boolean). "
                + "Only use commands from: NAVIGATE, HIGHLIGHT_ELEMENT, SET_FILTER, REFRESH_VIEW, "
                + "OPEN_PANEL, TOGGLE_THEME, SEARCH_ALERT, SEARCH_USER, SEARCH_SESSION, NO_ACTION.";
    }

    // ── Error classification ─────────────────────────────────────────────────

    private static ClassifiedError classifyModelError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        Throwable cause = e.getCause();
        String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";

        if (msg.contains("timeout") || msg.contains("timed out") || causeMsg.contains("timeout") || causeMsg.contains("timed out")) {
            return new ClassifiedError("assistant_model_timeout",
                    "I couldn't reach the assistant planning model right now. Please try again.",
                    "model-error", "ASSISTANT_TIMEOUT");
        }
        if (msg.contains("network is unreachable") || msg.contains("connectexception") || msg.contains("connection refused")
                || msg.contains("failed to resolve") || msg.contains("unknownhost")
                || causeMsg.contains("network is unreachable") || causeMsg.contains("connectexception") || causeMsg.contains("connection refused")
                || causeMsg.contains("failed to resolve") || causeMsg.contains("unknownhost")) {
            return new ClassifiedError("assistant_model_network_error",
                    "I couldn't reach the assistant planning model right now. Please try again.",
                    "model-error", "ASSISTANT_NETWORK_ERROR");
        }
        if (msg.contains("5") || msg.contains("server error") || msg.contains("provider")) {
            return new ClassifiedError("assistant_model_unavailable",
                    "I couldn't reach the assistant planning model right now. Please try again.",
                    "model-error", "ASSISTANT_MODEL_UNAVAILABLE");
        }
        if (msg.contains("empty response") || msg.contains("no json found") || msg.contains("failed to parse") || msg.contains("empty content")) {
            return new ClassifiedError("assistant_response_parse_failed",
                    "I couldn't understand the assistant model response. Please try again.",
                    "model-error", "ASSISTANT_PARSE_ERROR");
        }
        return new ClassifiedError("assistant_model_unavailable",
                "I couldn't reach the assistant planning model right now. Please try again.",
                "model-error", "ASSISTANT_ERROR");
    }

    private static class ClassifiedError {
        final String warning;
        final String userMessage;
        final String decisionSource;
        final String logTag;

        ClassifiedError(String warning, String userMessage, String decisionSource, String logTag) {
            this.warning = warning;
            this.userMessage = userMessage;
            this.decisionSource = decisionSource;
            this.logTag = logTag;
        }
    }
}
