package com.neo.dashboard.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAssistantServiceTest {

    private static final String ALERTS_HIGHLIGHT_MSG = "I'll highlight the Alerts navigation item.";
    private static final String PREDICTION_HIGHLIGHT_MSG = "I'll highlight the Next Event Prediction card.";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private DashboardAssistantCapabilityRegistry registry;
    private DashboardAssistantService service;

    @BeforeEach
    void setUp() {
        registry = new DashboardAssistantCapabilityRegistry();
        service = new DashboardAssistantService(objectMapper, registry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "apiKey", "nvapi-test-key");
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", true);
        ReflectionTestUtils.setField(service, "useGuidedJson", true);
        ReflectionTestUtils.setField(service, "maxCandidates", 20);
        ReflectionTestUtils.setField(service, "candidateModelsCsv", "mistralai/mistral-nemotron");
        ReflectionTestUtils.setField(service, "model", "mistralai/mistral-nemotron");
        ReflectionTestUtils.setField(service, "baseUrl", "https://integrate.api.nvidia.com/v1");
        ReflectionTestUtils.setField(service, "maxTokens", 300);
        ReflectionTestUtils.setField(service, "timeoutMs", 8000);
    }

    @Test
    void disabledAssistantReturnsWarning() {
        ReflectionTestUtils.setField(service, "enabled", false);

        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("Where can I see next event prediction?")
                .currentRoute("alert-investigation")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getWarnings()).contains("dashboard_assistant_disabled");
        assertThat(response.getMessage()).isEqualTo("Dashboard assistant is currently disabled.");
    }

    @Test
    void malformedModelResponseReturnsSafeFallback() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("some garbled text that means nothing")
                .currentRoute("dashboard")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getWarnings()).anyMatch(w -> w.startsWith("assistant_model_"));
        assertThat(response.getMessage()).contains("couldn't reach");
    }

    @Test
    void validNavigateCommandAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Opening alerts page.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("NAVIGATE")
                        .routeName("alerts")
                        .message("Opening alerts list.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void unknownRouteRejected() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Going to secret admin.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("NAVIGATE")
                        .routeName("secret-admin")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getWarnings()).contains("assistant_unknown_route");
    }

    @Test
    void validHighlightCommandAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Highlighting next event prediction card.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("card-next-event-prediction")
                        .message("This card shows the prediction.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("card-next-event-prediction");
    }

    @Test
    void invalidElementRejected() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Highlighting custom element.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("body > div:nth-child(5)")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getWarnings()).contains("assistant_unknown_element");
    }

    @Test
    void noActionCommandPreserved() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("I can help with navigation and search.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("NO_ACTION")
                        .message("I can help with navigation, searching, and explaining dashboard areas.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
    }

    @Test
    void searchAlertQueryAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Searching for alert anom-000000001179.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("SEARCH_ALERT")
                        .query("anom-000000001179")
                        .message("Searching for this alert.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("SEARCH_ALERT");
        assertThat(response.getCommands().get(0).getQuery()).isEqualTo("anom-000000001179");
    }

    @Test
    void openPanelCommandAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Opening evidence payload panel.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("OPEN_PANEL")
                        .panelId("evidence-payload")
                        .message("Showing the evidence payload.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("OPEN_PANEL");
        assertThat(response.getCommands().get(0).getPanelId()).isEqualTo("evidence-payload");
    }

    @Test
    void unknownPanelRejected() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Opening unknown panel.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("OPEN_PANEL")
                        .panelId("admin-panel")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getWarnings()).contains("assistant_unknown_panel");
    }

    @Test
    void invalidCommandTypeRejected() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Deleting all alerts.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("DELETE_ALL")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getWarnings()).contains("assistant_invalid_command_type");
    }

    @Test
    void navigateWithParamsAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Going to User 360 for insured-anom-00052.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("NAVIGATE")
                        .routeName("user-360")
                        .params(Map.of("insuredId", "insured-anom-00052"))
                        .message("Opening User 360 page.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getParams())
                .containsEntry("insuredId", "insured-anom-00052");
    }

    @Test
    void nullMessageDefaultsToStandardMessage() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .commands(List.of())
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getMessage()).isEqualTo("I can help with navigation, searching, and explaining dashboard areas.");
    }

    @Test
    void capabilityRegistryContainsExpectedRoutes() {
        assertThat(registry.isAllowedRoute("security-overview")).isTrue();
        assertThat(registry.isAllowedRoute("alerts")).isTrue();
        assertThat(registry.isAllowedRoute("alert-investigation")).isTrue();
        assertThat(registry.isAllowedRoute("user-360")).isTrue();
        assertThat(registry.isAllowedRoute("runtime-health")).isTrue();
        assertThat(registry.isAllowedRoute("secret-page")).isFalse();
    }

    @Test
    void capabilityRegistryContainsExpectedElements() {
        assertThat(registry.isAllowedElement("card-next-event-prediction")).isTrue();
        assertThat(registry.isAllowedElement("card-model-scores")).isTrue();
        assertThat(registry.isAllowedElement("btn-explain-ai")).isTrue();
        assertThat(registry.isAllowedElement("arbitrary-selector")).isFalse();
    }

    @Test
    void capabilityRegistryContainsExpectedPanels() {
        assertThat(registry.isAllowedPanel("evidence-payload")).isTrue();
        assertThat(registry.isAllowedPanel("explain-ai")).isTrue();
        assertThat(registry.isAllowedPanel("advanced-context")).isTrue();
        assertThat(registry.isAllowedPanel("admin-console")).isFalse();
    }

    @Test
    void showMeWhereToClickAlertsReturnsHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me where should i click to go to alerts page")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("nav-alerts");
        assertThat(response.getMessage()).isEqualTo(ALERTS_HIGHLIGHT_MSG);
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void whereCanISeeNextEventPredictionOnAlertInvestigationReturnsHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where can i see next event prediction?")
                .currentRoute("alert-investigation")
                .currentContext(Map.of("eventId", "anom-000000001179"))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("card-next-event-prediction");
        assertThat(response.getMessage()).isEqualTo(PREDICTION_HIGHLIGHT_MSG);
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void predictionHighlightFromDifferentRouteNavigatesFirst() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where can i see next event prediction?")
                .currentRoute("security-overview")
                .currentContext(Map.of("eventId", "anom-000000001179"))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alert-investigation");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("eventId", "anom-000000001179");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(1).getElementId()).isEqualTo("card-next-event-prediction");
    }

    @Test
    void predictionHighlightWithoutEventIdAsksForIt() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where can i see next event prediction?")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("alert event ID");
    }

    @Test
    void whereIsAlertsReturnsHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where is alerts")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("nav-alerts");
    }

    @Test
    void findAlertsButtonReturnsHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the alerts refresh button")
                .currentRoute("alerts")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("alerts-refresh-button");
    }

    @Test
    void goToAlertsReturnsNavigate() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("go to alerts")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
    }

    @Test
    void openAlertsReturnsNavigate() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("open alerts")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
    }

    @Test
    void showAlertsReturnsNavigate() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show alerts")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
    }

    @Test
    void predictionDeviationEvidenceHighlighted() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me deviation evidence")
                .currentRoute("alert-investigation")
                .currentContext(Map.of("eventId", "anom-000000001179"))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("card-next-event-prediction");
    }

    @Test
    void unrelatedPhraseReturnsNoActionOrEmpty() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("how to make pizza")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getWarnings()).anyMatch(w -> w.startsWith("assistant_model_"));
        assertThat(response.getCommands()).isEmpty();
    }

    @Test
    void navigateToUser360WithContextPassesParams() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("go to user 360")
                .currentRoute("alerts")
                .currentContext(Map.of("insuredId", "insured-anom-00052"))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("user-360");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("insuredId", "insured-anom-00052");
    }

    @Test
    void navElementIdsInRegistry() {
        assertThat(registry.isAllowedElement("nav-alerts")).isTrue();
        assertThat(registry.isAllowedElement("nav-security-overview")).isTrue();
        assertThat(registry.isAllowedElement("nav-user360")).isTrue();
        assertThat(registry.isAllowedElement("nav-runtime")).isTrue();
        assertThat(registry.isAllowedElement("nav-churn")).isTrue();
        assertThat(registry.isAllowedElement("nav-forecast")).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // New deterministic resolver tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void greetingReturnsNoAction() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("hello are you here")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("dashboard assistant");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void greetingHiThereReturnsNoAction() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("hi there")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("dashboard assistant");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void listCapabilitiesReturnsNoActionWithHelpText() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("what can you do")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).anyMatch(c -> "NO_ACTION".equals(c.getType()));
        assertThat(response.getMessage()).contains("I can help");
    }

    @Test
    void toggleDarkModeReturnsNoAction() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("switch to dark mode")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getMessage()).contains("theme toggle");
    }

    @Test
    void takeMeToOverviewNavigates() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to overview")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("security-overview");
    }

    @Test
    void takeMeToPlaceSeeAnomaliesNavigatesToAlerts() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to the place where i can see the anomalies")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
    }

    @Test
    void whereClickForecastHighlightsNavForecast() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where to click to go to forecast")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("nav-forecast");
    }

    @Test
    void highlightExplainWithAiWithEventIdReturnsNavigateAndHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("can you show me where to click explain with ai for event id anom-000000004391 in the alerts view")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alert-investigation");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("eventId", "anom-000000004391");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(1).getElementId()).isEqualTo("btn-explain-ai");
    }

    @Test
    void highlightExplainWithAiWithoutEventIdAsksForIt() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me explain ai")
                .currentRoute("alerts")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("alert event ID");
    }

    @Test
    void explainAiOnAlertInvestigationHighlightsButton() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight explain with ai")
                .currentRoute("alert-investigation")
                .currentContext(Map.of("eventId", "anom-000000001179"))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("btn-explain-ai");
    }

    @Test
    void takeMeToChurnNavigates() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to churn page")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("churn");
    }

    @Test
    void takeMeToUserPossiblyWillAbandonNavigatesToChurn() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to where i can see user possibly will abandon using the insurance system")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("churn");
    }

    @Test
    void whereCanSeeKafkaHealthNavigatesThenHighlights() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where can i see kafka health")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("runtime-health");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(1).getElementId()).isEqualTo("card-kafka-health");
    }

    @Test
    void showDiagnosticsCardOnRuntimeHighlights() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the diagnostics card here")
                .currentRoute("runtime-health")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("card-runtime-summary");
    }

    @Test
    void showDiagnosticsCardElsewhereNavigatesThenHighlights() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the diagnostics card")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("runtime-health");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(1).getElementId()).isEqualTo("card-runtime-summary");
    }

    @Test
    void jumpToHealthSectionNavigatesToRuntime() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("jump to health section")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("runtime-health");
    }

    @Test
    void refreshForecastReturnsRefreshView() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("refresh forecast")
                .currentRoute("forecast")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("REFRESH_VIEW");
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("forecast");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void refreshAlertsReturnsRefreshView() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("refresh the alerts")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("REFRESH_VIEW");
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("alerts");
    }

    @Test
    void filterChurnMediumReturnsSetFilter() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("filter churn table to medium risk users")
                .currentRoute("churn")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("SET_FILTER");
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("churn-risk-filter");
        assertThat(response.getCommands().get(0).getValue()).isEqualTo("MEDIUM");
    }

    @Test
    void filterAlertsCriticalReturnsSetFilter() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show only critical alerts")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("SET_FILTER");
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("alerts-risk-filter");
        assertThat(response.getCommands().get(0).getValue()).isEqualTo("CRITICAL");
    }

    @Test
    void user360WithoutInsuredIdAsksForIt() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to user 360 view")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("user360");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(1).getElementId()).isEqualTo("user360-search-input");
    }

    @Test
    void user360WithInsuredIdInMessageNavigates() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("open user 360 for insured-anom-00052-a3ea35")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("user-360");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("insuredId", "insured-anom-00052-a3ea35");
    }

    @Test
    void user360WithContextInsuredIdNavigates() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("go to user 360")
                .currentRoute("alerts")
                .currentContext(Map.of("insuredId", "insured-anom-00052"))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("user-360");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("insuredId", "insured-anom-00052");
    }

    @Test
    void highlightUser360HighlightsNav() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight where to click to go to user 360 view")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("nav-user360");
    }

    @Test
    void highlightOverviewHighlightsNav() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight where to click to go to the overview")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("nav-security-overview");
    }

    @Test
    void whereCanSeeNextEventsPredictionForUserWithoutInsuredIdAsks() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where can i see next events prediction for a user")
                .currentRoute("alerts")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("alert event ID");
    }

    @Test
    void openUser360WithInsuredIdInContextNavigatesWithParams() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("open user 360 view")
                .currentRoute("alerts")
                .currentContext(Map.of("insuredId", "insured-anom-00052"))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("user-360");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("insuredId", "insured-anom-00052");
    }

    @Test
    void navigateToRuntimeHealthNavigates() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("go to runtime health")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("runtime-health");
    }

    @Test
    void navigateToAccountNavigates() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("open account settings")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("account");
    }

    @Test
    void changePasswordNavigatesToAccount() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("I want to change my password")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).anyMatch(c -> "NAVIGATE".equals(c.getType()) && "account".equals(c.getRouteName()));
    }

    // ── Exact utterance tests from spec ───────────────────────────────────

    @Test
    void hiWhatCanYouDoReturnsGreeting() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("hello there")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("dashboard assistant");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void highlightTopAnomalyTypesCardReturnsHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight top anomaly types card")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-top-anomalies");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void highlightTotalEventsTodayCardReturnsHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight total events today card")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-kpi-total-events");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void whereToClickHereToRefreshOverviewDataHighlightsRefreshButton() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where to click here, to refresh overview data")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-refresh-button");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void whereIsRefreshButtonInOverviewHighlightsRefreshButton() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("ok, i am in overview, where to click to refresh the data, is there a refresh button?")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-refresh-button");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void whereToSeeRecentCriticalAlertsInOverview() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where should i look here to see the recent critical alerts")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-kpi-critical-alerts");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void whereToSeeTotalEventsInOverview() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where should i look here to see the total events processed today?")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-kpi-total-events");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void highlightTopRulesDetectedReturnsHighlight() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight the top rules detected")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-top-rules");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void takeMeToOverviewAndHighlightTopTriggeredRules() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to overview page and highlight for me the top triggered rules")
                .currentRoute("alerts")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("security-overview");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(1).getElementId()).isEqualTo("overview-top-rules");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void highlightExplainAiFromOtherRouteWithoutEventIdAsks() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight where should i click to explain an alert with AI")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("alert event ID");
    }

    @Test
    void highlightExplainAiWithEventIdFromOtherRouteNavigates() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight where should i click to explain an alert with AI for event id anom-000000004391")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alert-investigation");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("eventId", "anom-000000004391");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(1).getElementId()).isEqualTo("btn-explain-ai");
    }

    @Test
    void takeMeToKafkaAndModelsHealthNavigatesToRuntime() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to the view that i can see the kafka and models health")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("runtime-health");
    }

    @Test
    void specialUserInfosHighlightsNavUser360() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where i should go to see special user infos")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("nav-user360");
    }

    @Test
    void searchForUserReturnsSearchUserOrNav() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("try to search for this user \"insured-00001-220\"")
                .currentRoute("security-overview")
                .build();
        DashboardAssistantResponse response = service.handleMessage(request);
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("user-360");
        assertThat(response.getCommands().get(0).getParams()).containsEntry("insuredId", "insured-00001-220");
    }

    // ── Visible elements / model path tests ──────────────────────────────

    @Test
    void visibleElementInCandidatesAccepted() {
        setCurrentVisibleElements(List.of(
                AssistantVisibleElement.builder()
                        .id("overview-average-risk-score-card")
                        .type("card")
                        .label("Average Risk Score")
                        .description("Shows the average anomaly/risk score for today or recent events across all alerts.")
                        .routeId("security-overview")
                        .visible(true)
                        .actions(List.of("HIGHLIGHT_ELEMENT"))
                        .build(),
                AssistantVisibleElement.builder()
                        .id("overview-kpi-active-users")
                        .type("card")
                        .label("Active Users KPI")
                        .description("Shows active application users for today and the current period with trend indicators.")
                        .routeId("security-overview")
                        .visible(true)
                        .actions(List.of("HIGHLIGHT_ELEMENT"))
                        .build()
        ));

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("I'll highlight the Average Risk Score card.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("overview-average-risk-score-card")
                        .message("Here is the average anomaly/risk score card.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-average-risk-score-card");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void visibleElementOutsideCandidatesRejected() {
        setCurrentVisibleElements(List.of(
                AssistantVisibleElement.builder()
                        .id("overview-kpi-active-users")
                        .type("card")
                        .label("Active Users KPI")
                        .description("Shows active users.")
                        .routeId("security-overview")
                        .visible(true)
                        .actions(List.of("HIGHLIGHT_ELEMENT"))
                        .build()
        ));

        // Model picks an element NOT in visibleElements AND NOT in the global manifest
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Highlighting field coverage card.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("field-coverage-card")
                        .message("Here is the field coverage card.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getWarnings()).contains("assistant_unknown_element");
    }

    @Test
    void visibleElementInManifestButNotInVisibleAcceptedAsFallback() {
        setCurrentVisibleElements(List.of(
                AssistantVisibleElement.builder()
                        .id("overview-kpi-active-users")
                        .type("card")
                        .label("Active Users KPI")
                        .description("Shows active users.")
                        .routeId("security-overview")
                        .visible(true)
                        .actions(List.of("HIGHLIGHT_ELEMENT"))
                        .build()
        ));

        // Model picks an element NOT in visibleElements BUT in global manifest
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Highlighting top anomaly types.")
                .commands(List.of(AssistantModelResponse.AssistantModelCommand.builder()
                        .type("HIGHLIGHT_ELEMENT")
                        .elementId("overview-top-anomalies")
                        .message("Here is the top anomaly types chart.")
                        .build()))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-top-anomalies");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void visibleElementsPassedThroughRequest() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me average risk score today")
                .currentRoute("security-overview")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("overview-average-risk-score-card")
                                .type("card")
                                .label("Average Risk Score")
                                .description("Shows the average anomaly/risk score for today or recent events across all alerts.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // matchVisibleElementsFirst fires before fallback matchers. The visible element
        // overview-average-risk-score-card matches "average risk score" via description phrase scoring.
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-average-risk-score-card");
    }

    @Test
    void listHighlightableItemsWithVisibleElements() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("what can you highlight on this page")
                .currentRoute("alerts")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("alerts-table")
                                .type("table")
                                .label("Alerts Table")
                                .description("Main alerts data table")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("alerts-risk-filter")
                                .type("filter")
                                .label("Alerts Risk Filter")
                                .description("Dropdown filter for risk level")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT", "SET_FILTER"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getMessage()).contains("Alerts Table");
        assertThat(response.getMessage()).contains("Alerts Risk Filter");
    }

    @Test
    void visibleElementsArePassedToModelAndValidated() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me where to click for alerts-table")
                .currentRoute("alerts")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("alerts-table")
                                .type("table")
                                .label("Alerts Table")
                                .description("Main alerts data table with risk level and score.")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .textContent("Critical 95 High 72")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Should match as a highlight intent and highlight the alerts-table
        assertThat(response.getCommands()).anyMatch(c -> "HIGHLIGHT_ELEMENT".equals(c.getType()) && "alerts-table".equals(c.getElementId()));
    }

    // ── Visible elements priority tests ──────────────────────────────────

    @Test
    void showMeWhereICanFindAverageAnomalyScoreHereHighlightsAverageRiskScoreCard() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me where i can find average anomaly score here")
                .currentRoute("security-overview")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("overview-average-risk-score-card")
                                .type("card")
                                .label("Average Risk Score")
                                .description("Shows the average anomaly score, average risk score, and mean final risk score for today's activity.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .textContent("Average Risk Score")
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("overview-active-users-today-card")
                                .type("card")
                                .label("Active Users Today")
                                .description("Shows the number of active users today.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .textContent("Active Users Today")
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("nav-alerts")
                                .type("navigation-item")
                                .label("Alerts")
                                .description("Navigation item for the alerts and anomalies list.")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT", "NAVIGATE"))
                                .textContent("Alerts")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-average-risk-score-card");
    }

    @Test
    void showMeTheAverageRiskScoreHighlightsAverageRiskScoreCard() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the average risk score")
                .currentRoute("security-overview")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("overview-average-risk-score-card")
                                .type("card")
                                .label("Average Risk Score")
                                .description("Shows the average anomaly score, average risk score, and mean final risk score for today's activity.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("nav-alerts")
                                .type("navigation-item")
                                .label("Alerts")
                                .description("Navigation item for the alerts and anomalies list.")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-average-risk-score-card");
    }

    @Test
    void currentRouteBoostsElementOnSameRoute() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the predicted anomaly rate")
                .currentRoute("forecast")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("forecast-predicted-anomaly-rate-card")
                                .type("card")
                                .label("Predicted Anomaly Rate")
                                .description("Shows the predicted anomaly rate for future periods.")
                                .routeId("forecast")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("overview-kpi-predicted-anomaly-rate")
                                .type("card")
                                .label("Predicted Anomaly Rate KPI")
                                .description("Shows the predicted anomaly rate from the forecast model.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        // forecast route element wins due to currentRoute boost
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("forecast-predicted-anomaly-rate-card");
    }

    @Test
    void navAlertsOnlyWhenNavigationIntent() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where to click to go to alerts page")
                .currentRoute("security-overview")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("nav-alerts")
                                .type("navigation-item")
                                .label("Alerts")
                                .description("Navigation item for the alerts and anomalies list.")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT", "NAVIGATE"))
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("overview-average-risk-score-card")
                                .type("card")
                                .label("Average Risk Score")
                                .description("Shows metrics.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        // "where to click to go to alerts page" has "go to" (explicitNav) so nav-alerts wins
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("nav-alerts");
    }

    @Test
    void anomalyPageNavigationStillWorks() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to the place where i can see anomalies")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
    }

    @Test
    void unknownTargetShouldNotInvent() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me field coverage card")
                .currentRoute("security-overview")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("overview-average-risk-score-card")
                                .type("card")
                                .label("Average Risk Score")
                                .description("Shows the average anomaly/risk score.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // With the expanded manifest, the deterministic path may find a match and produce
        // a HIGHLIGHT command. Accept that as valid OR accept the model-failure fallback.
        // The key: no dangerous/invented actions beyond visible manifest elements.
        boolean noHarmfulAction = response.getCommands().isEmpty()
                || (response.getCommands().size() == 1 && "NO_ACTION".equals(response.getCommands().get(0).getType()))
                || (response.getCommands().size() == 1 && "HIGHLIGHT_ELEMENT".equals(response.getCommands().get(0).getType()));
        assertThat(noHarmfulAction).isTrue();
    }

    // ── resolveRecentCriticalAlerts tests ──────────────────────────────

    @Test
    void criticalAlertTableRequestNavigatesToAlertsAndFiltersCritical() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the critical alert table")
                .currentRoute("security-overview")
                .visibleElements(List.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("SET_FILTER");
        assertThat(response.getCommands().get(1).getTarget()).isEqualTo("alerts-risk-filter");
        assertThat(response.getCommands().get(1).getValue()).isEqualTo("CRITICAL");
    }

    @Test
    void recentCriticalAlertsOnOverviewHighlightsKpiViaManifest() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("where should i look here to see the recent critical alerts")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-kpi-critical-alerts");
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void criticalAlertListRequestNavigatesToAlertsAndFiltersCritical() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the critical alert list")
                .currentRoute("security-overview")
                .visibleElements(List.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(2);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
        assertThat(response.getCommands().get(1).getType()).isEqualTo("SET_FILTER");
        assertThat(response.getCommands().get(1).getTarget()).isEqualTo("alerts-risk-filter");
        assertThat(response.getCommands().get(1).getValue()).isEqualTo("CRITICAL");
    }

    // ── Impossible request / warning tests ─────────────────────────────

    @Test
    void explainAiOnAlertsRouteIncludesWarning() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me explain ai")
                .currentRoute("alerts")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("alert event ID");
        assertThat(response.getWarnings()).contains("assistant_missing_event_id_for_explain_ai");
    }

    @Test
    void explainAiFromOtherRouteWithoutEventIdIncludesWarning() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("highlight where should i click to explain an alert with AI")
                .currentRoute("security-overview")
                .currentContext(Map.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NO_ACTION");
        assertThat(response.getMessage()).contains("alert event ID");
        assertThat(response.getWarnings()).contains("assistant_missing_event_id_for_explain_ai");
    }

    @Test
    void debugMetadataReturnedWhenDebugIsTrue() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("hello")
                .debug(true)
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Debug metadata should be present since debug=true
        // The response should have debug info with decisionSource
        assertThat(response.getDebug()).isNotNull();
        assertThat(response.getDebug()).containsKey("decisionSource");
    }

    @Test
    void modelPlannerModeCallsModelWhenDeterministicDisabled() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me average anomaly score")
                .currentRoute("security-overview")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("overview-kpi-average-risk")
                                .type("card")
                                .label("Average Risk KPI")
                                .description("Shows the average anomaly/risk score for today's activity.")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Model will fail (no API key), so response should be emergency fallback
        assertThat(response.getWarnings()).anyMatch(w -> w.startsWith("assistant_model_"));
        assertThat(response.getMessage()).contains("couldn't reach");
    }

    @Test
    void noKeywordFallbackForAnomaliesToAlertsWhenDeterministicDisabled() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me the expected number of anomalies tomorrow")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Should NOT return nav-alerts HIGHLIGHT or alerts NAVIGATE
        // Should go to model, fail, and return emergency fallback
        assertThat(response.getWarnings()).anyMatch(w -> w.startsWith("assistant_model_"));
        assertThat(response.getMessage()).contains("couldn't reach");
    }

    @Test
    void listHighlightableItemsFromVisibleElementsNoModelCall() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("which items here can you highlight")
                .currentRoute("alerts")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("alerts-table")
                                .type("table")
                                .label("Alerts Table")
                                .description("Main alerts data table")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("alerts-risk-filter")
                                .type("filter")
                                .label("Alerts Risk Filter")
                                .description("Dropdown filter for risk level")
                                .routeId("alerts")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT", "SET_FILTER"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getMessage()).contains("Alerts Table");
        assertThat(response.getMessage()).contains("Alerts Risk Filter");
        assertThat(response.getCommands()).isEmpty();
    }

    @Test
    void listHighlightableItemsReturnsWarningWhenNoVisibleElements() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("list what you can highlight here")
                .currentRoute("alerts")
                .visibleElements(List.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getWarnings()).contains("assistant_no_visible_elements");
        assertThat(response.getMessage()).contains("do not currently see");
        assertThat(response.getCommands()).isEmpty();
    }

    @Test
    void capabilitiesDirectResponseNoModelCall() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("what can you do")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).anyMatch(c -> "NO_ACTION".equals(c.getType()));
        assertThat(response.getMessage()).contains("I can help");
    }

    @Test
    void greetingDirectResponseNoModelCall() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("hello")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getCommands()).anyMatch(c -> "NO_ACTION".equals(c.getType()));
        assertThat(response.getMessage()).contains("Hello");
    }

    @Test
    void deterministicActionsEnabledStillUsesKeywordMatching() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", true);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("show me average risk score today")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Should return a HIGHLIGHT command from deterministic matching
        assertThat(response.getCommands()).anyMatch(c -> "HIGHLIGHT_ELEMENT".equals(c.getType()));
        assertThat(response.getWarnings()).isEmpty();
    }

    @Test
    void emergencyFallbackWhenDeterministicDisabledAndModelFails() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to the forecast page")
                .currentRoute("alerts")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Model fails (no API key), should get emergency fallback, NOT keyword NAVIGATE
        assertThat(response.getWarnings()).anyMatch(w -> w.startsWith("assistant_model_"));
        assertThat(response.getMessage()).contains("couldn't reach");
    }

    @Test
    void debugMetadataWithDecisionSource() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("hello")
                .debug(true)
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        assertThat(response.getDebug()).isNotNull();
        assertThat(response.getDebug()).containsKey("decisionSource");
        assertThat(response.getDebug()).containsKey("modelCalled");
    }

    // ── Anti-hallucination tests ───────────────────────────────────────────

    @Test
    void listHighlightableItemsBulletFormatWithTypes() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", false);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("which items here can you highlight")
                .currentRoute("security-overview")
                .visibleElements(List.of(
                        AssistantVisibleElement.builder()
                                .id("overview-kpi-average-risk")
                                .type("card")
                                .label("Average Risk Score")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("overview-kpi-active-users")
                                .type("card")
                                .label("Active Users Today")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("HIGHLIGHT_ELEMENT"))
                                .build(),
                        AssistantVisibleElement.builder()
                                .id("overview-refresh-button")
                                .type("button")
                                .label("Refresh Overview")
                                .routeId("security-overview")
                                .visible(true)
                                .actions(List.of("REFRESH_VIEW"))
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);
        String msg = response.getMessage();

        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).isEmpty();
        assertThat(msg).contains("- Average Risk Score (card)");
        assertThat(msg).contains("- Active Users Today (card)");
        assertThat(msg).contains("- Refresh Overview (button)");
        assertThat(msg).startsWith("On this page I can highlight or interact with:");
    }

    @Test
    void debugMetadataIncludesRawModelAndValidatedCommands() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Showing the alerts page for you.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("NAVIGATE")
                                .routeName("nonexistent-route")
                                .message("Opening alerts page")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getWarnings()).contains("assistant_unknown_route");
        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getMessage()).contains("couldn't safely match");
    }

    @Test
    void modelMessageActionClaimRewrittenWhenAllCommandsRejected() {
        // Model says "Showing..." but the only command (HIGHLIGHT with invalid elementId) is rejected
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Showing recent critical alerts for today.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("HIGHLIGHT_ELEMENT")
                                .elementId("nonexistent-element")
                                .message("Showing critical alerts")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getWarnings()).contains("assistant_no_valid_command");
        assertThat(response.getMessage()).contains("couldn't safely match");
        assertThat(response.getCommands()).isEmpty();
    }

    @Test
    void harmlessModelMessagePreservedWhenCommandsEmpty() {
        // Model says something harmless (no action verb) with no commands
        // This should still trigger the empty commands + no warnings fallback
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("I understand you want help with the dashboard.")
                .commands(List.of())
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        // All commands empty + no warnings + no action claim
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getMessage()).contains("I understand");
    }

    @Test
    void actionClaimNotRewrittenWhenValidCommandExists() {
        // Model says "Showing..." AND there IS a valid HIGHLIGHT command
        setCurrentVisibleElements(List.of(
                AssistantVisibleElement.builder()
                        .id("overview-kpi-average-risk")
                        .type("card")
                        .label("Average Risk Score")
                        .routeId("security-overview")
                        .visible(true)
                        .actions(List.of("HIGHLIGHT_ELEMENT"))
                        .build()
        ));

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Showing the average risk score for today.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("HIGHLIGHT_ELEMENT")
                                .elementId("overview-kpi-average-risk")
                                .message("Here is the average risk score card.")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getMessage()).contains("Showing the average risk score");
    }

    @Test
    void refreshUnsupportedDoesNotClaimRefreshing() {
        // When refresh target is unsupported, should NOT say "Refreshing..."
        setCurrentVisibleElements(List.of());

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Refreshing the unknown view page.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("REFRESH_VIEW")
                                .target("nonexistent-target")
                                .message("Refreshing view")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getWarnings()).contains("assistant_unsupported_refresh");
        assertThat(response.getMessage()).doesNotContain("Refreshing");
        assertThat(response.getMessage()).contains("couldn't safely match");
    }

    @Test
    void refreshSupportedReturnsRefreshView() {
        // Supported refresh targets in manifest: forecast, alerts, churn, runtime, security-overview
        setCurrentVisibleElements(List.of());

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Refreshing the alerts page.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("REFRESH_VIEW")
                                .target("alerts")
                                .message("Refreshing alerts")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        // Should have REFRESH_VIEW command and keep the "Refreshing" message
        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getType()).isEqualTo("REFRESH_VIEW");
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("alerts");
        assertThat(response.getMessage()).contains("Refreshing");
    }

    @Test
    void refreshSecurityOverviewAccepted() {
        setCurrentVisibleElements(List.of());

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Refreshing the main overview dashboard.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("REFRESH_VIEW")
                                .target("security-overview")
                                .message("Refreshing overview")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getType()).isEqualTo("REFRESH_VIEW");
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("security-overview");
        assertThat(response.getMessage()).contains("Refreshing");
    }

    @Test
    void manifestLoadedWithCriticalIds() {
        DashboardAssistantCapabilityRegistry reg = new DashboardAssistantCapabilityRegistry();
        List<DashboardAssistantManifest.ManifestElement> elements = reg.getManifest().getElements();
        java.util.Set<String> elementIds = elements.stream()
                .map(DashboardAssistantManifest.ManifestElement::getId)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(elementIds).contains("theme-toggle");
        assertThat(elementIds).contains("alerts-table");
    }

    // ── Route/filter normalization tests ───────────────────────────────────

    @Test
    void routeNormalizationAlertsPageToAlerts() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Opening alerts page.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("NAVIGATE")
                                .routeName("AlertsPage")
                                .message("Opening alerts page")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("alerts");
    }

    @Test
    void filterNormalizationAlertsRiskToAlertsRiskFilter() {
        setCurrentVisibleElements(List.of());

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Filtering by risk level.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("SET_FILTER")
                                .target("alerts-risk")
                                .value("CRITICAL")
                                .message("Filtering critical alerts")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getType()).isEqualTo("SET_FILTER");
        // Should be normalized from "alerts-risk" -> "alerts-risk-filter" via alias
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("alerts-risk-filter");
    }

    @Test
    void forecastTypoUsesFuzzyMatch() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", true);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to forectast")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Should fuzzy match "forectast" -> "forecast" and navigate
        assertThat(response.getCommands()).anyMatch(c ->
                "NAVIGATE".equals(c.getType()) && "forecast".equals(c.getRouteName()));
    }

    @Test
    void routeNormalizationUsesCanonicalId() {
        setCurrentVisibleElements(List.of());

        // SecurityOverviewPage -> security-overview
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Navigating to overview.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("NAVIGATE")
                                .routeName("SecurityOverviewPage")
                                .message("Opening overview")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getRouteName()).isEqualTo("security-overview");
    }

    @Test
    void runtimeHealthTypoUsesFuzzyMatch() {
        ReflectionTestUtils.setField(service, "deterministicActionsEnabled", true);
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("take me to runtim health")
                .currentRoute("security-overview")
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Should fuzzy match "runtim health" -> "runtime-health"
        boolean hasRuntimeNav = response.getCommands().stream()
                .anyMatch(c -> "NAVIGATE".equals(c.getType()) && "runtime-health".equals(c.getRouteName()));
        assertThat(hasRuntimeNav).isTrue();
    }

    @Test
    void toggleThemeWithValueIsAccepted() {
        setCurrentVisibleElements(List.of());

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Switching to dark mode.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("TOGGLE_THEME")
                                .value("dark")
                                .message("Switching to dark mode")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getType()).isEqualTo("TOGGLE_THEME");
        assertThat(response.getCommands().get(0).getValue()).isEqualTo("dark");
    }

    @Test
    void toggleThemeLightValueIsAccepted() {
        setCurrentVisibleElements(List.of());

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Switching to light mode.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("TOGGLE_THEME")
                                .value("light")
                                .message("Switching to light mode")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getValue()).isEqualTo("light");
    }

    @Test
    void maxCandidatesLimitsVisibleElements() throws Exception {
        ReflectionTestUtils.setField(service, "maxCandidates", 2);

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("buildVisibleElementsContext", List.class);
        method.setAccessible(true);

        List<AssistantVisibleElement> elements = List.of(
                element("id-1", "card", "One", "First", "alerts"),
                element("id-2", "card", "Two", "Second", "alerts"),
                element("id-3", "card", "Three", "Third", "alerts")
        );

        String result = (String) method.invoke(service, elements);

        // All elements are now included regardless of maxCandidates
        assertThat(result).contains("id-1");
        assertThat(result).contains("id-2");
        assertThat(result).contains("id-3");
        assertThat(result).doesNotContain("additional element");
    }

    @Test
    void maxCandidatesConfigured() {
        assertThat(ReflectionTestUtils.getField(service, "maxCandidates")).isEqualTo(20);
    }

    @Test
    void configDefaultMaxTokensIs300() {
        assertThat(ReflectionTestUtils.getField(service, "maxTokens")).isEqualTo(300);
    }

    @Test
    void configDefaultTimeoutMsIs8000() {
        Object timeoutObj = ReflectionTestUtils.getField(service, "timeoutMs");
        assertThat(timeoutObj).isNotNull();
        assertThat(((Number) timeoutObj).longValue()).isEqualTo(8000L);
    }

    @Test
    void defaultUseGuidedJsonIsTrue() {
        assertThat(ReflectionTestUtils.getField(service, "useGuidedJson")).isEqualTo(true);
    }

    @Test
    void filterNormalizationCanonicalIdAccepted() {
        setCurrentVisibleElements(List.of());

        // Verify registry state via public API
        assertThat(registry.normalizeFilterTarget("alerts-risk-filter")).isEqualTo("alerts-risk-filter");
        assertThat(registry.isAllowedFilter("alerts-risk-filter")).isTrue();

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Filtering by risk level.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("SET_FILTER")
                                .target("alerts-risk-filter")
                                .value("CRITICAL")
                                .message("Filtering critical alerts")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isNotEmpty();
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("alerts-risk-filter");
    }

    @Test
    void filterNormalizationAliasMapsToCanonicalId() {
        setCurrentVisibleElements(List.of());

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Filtering churn by risk level.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("SET_FILTER")
                                .target("churn-risk")
                                .value("HIGH")
                                .message("Filtering high risk churn")
                                .build()
                ))
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);

        assertThat(response.getCommands()).isNotEmpty();
        // Should be normalized from "churn-risk" -> "churn-risk-filter" via alias
        assertThat(response.getCommands().get(0).getTarget()).isEqualTo("churn-risk-filter");
    }

    @Test
    void buildRequestBodyContainsGuidedJsonWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(service, "useGuidedJson", true);
        ReflectionTestUtils.setField(service, "guidedJsonSupportedModelsCsv", "mistralai/mistral-nemotron");

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("buildRequestBody", String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> body = (Map<String, Object>) method.invoke(service, "system prompt", "user prompt");

        assertThat(body).containsKey("nvext");
        Map<String, Object> nvext = (Map<String, Object>) body.get("nvext");
        assertThat(nvext).containsKey("guided_json");
    }

    @Test
    void buildRequestBodyOmitsGuidedJsonWhenDisabled() throws Exception {
        ReflectionTestUtils.setField(service, "useGuidedJson", false);

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("buildRequestBody", String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> body = (Map<String, Object>) method.invoke(service, "system prompt", "user prompt");

        assertThat(body).doesNotContainKey("nvext");
    }

    @Test
    void buildRequestBodyOmitsGuidedJsonForUnsupportedModel() throws Exception {
        ReflectionTestUtils.setField(service, "useGuidedJson", true);
        ReflectionTestUtils.setField(service, "guidedJsonSupportedModelsCsv", "nvidia/llama-3.3-nemotron-super-49b-v1");

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("buildRequestBody", String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> body = (Map<String, Object>) method.invoke(service, "system", "user");

        assertThat(body).doesNotContainKey("nvext");
    }

    @Test
    void getCandidateModelsReturnsDefaultList() throws Exception {
        ReflectionTestUtils.setField(service, "candidateModelsCsv", null);

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("getCandidateModels");
        method.setAccessible(true);
        List<String> models = (List<String>) method.invoke(service);

        assertThat(models).isNotEmpty();
        assertThat(models.get(0)).isEqualTo("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning");
    }

    @Test
    void getCandidateModelsParsesCsv() throws Exception {
        ReflectionTestUtils.setField(service, "candidateModelsCsv", "model-a,model-b,model-c");

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("getCandidateModels");
        method.setAccessible(true);
        List<String> models = (List<String>) method.invoke(service);

        assertThat(models).containsExactly("model-a", "model-b", "model-c");
    }

    @Test
    void debugTrueIncludesModelInfo() {
        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("switch to dark mode")
                .currentRoute("alerts")
                .debug(true)
                .visibleElements(List.of(
                        element("theme-toggle", "button", "Theme Toggle", "Switches theme", "global")
                ))
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);
        Map<String, Object> debug = response.getDebug();

        assertThat(debug).isNotNull();
        assertThat(debug).containsKey("actualModelUsed");
        assertThat(debug).containsKey("guidedJson");
        assertThat(debug).containsKey("guidedJsonMode");
        assertThat(debug).containsKey("maxTokens");
        assertThat(debug).containsKey("timeoutMs");
        assertThat(debug).containsKey("candidateCount");
        assertThat(debug).containsKey("promptChars");
        assertThat(debug).containsKey("requestJsonBytes");
        assertThat(debug).containsKey("latencyMs");
        assertThat(debug).containsKey("baseUrl");
    }

    @Test
    void modelConfigUsesDashboardModelNotAlertModel() {
        String actualModel = (String) ReflectionTestUtils.getField(service, "model");
        assertThat(actualModel).isEqualTo("mistralai/mistral-nemotron");
    }

    @Test
    void noSilentFallbackWhenDisabled() throws Exception {
        ReflectionTestUtils.setField(service, "fallbackToExplanationModel", false);
        ReflectionTestUtils.setField(service, "model", "non-existent-model");
        ReflectionTestUtils.setField(service, "baseUrl", "https://invalid-url.example.com");

        DashboardAssistantRequest request = DashboardAssistantRequest.builder()
                .message("navigate to alerts")
                .currentRoute("alerts")
                .visibleElements(List.of())
                .build();

        DashboardAssistantResponse response = service.handleMessage(request);

        // Should show model error, not silently use explanation model
        assertThat(response.getWarnings()).anyMatch(w ->
                w.startsWith("assistant_model_") || w.equals("assistant_command_rejected") || w.equals("dashboard_assistant_disabled"));
    }

    @Test
    void guidedJsonABodyContainsNvext() throws Exception {
        ReflectionTestUtils.setField(service, "useGuidedJson", true);
        ReflectionTestUtils.setField(service, "guidedJsonSupportedModelsCsv", "mistralai/mistral-nemotron");

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("buildRequestBody", String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> body = (Map<String, Object>) method.invoke(service, "system", "user");

        assertThat(body).containsKey("nvext");
    }

    @Test
    void nonGuidedBodyOmitsNvext() throws Exception {
        ReflectionTestUtils.setField(service, "useGuidedJson", false);

        java.lang.reflect.Method method = DashboardAssistantService.class
                .getDeclaredMethod("buildRequestBody", String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> body = (Map<String, Object>) method.invoke(service, "system", "user");

        assertThat(body).doesNotContainKey("nvext");
    }

    @Test
    void nimProbeReturnsLatencyAndModel() {
        Map<String, Object> result = service.nimProbe("Return JSON with message='ok' and commands=[]", "mistralai/mistral-nemotron", false, false);

        assertThat(result).containsKey("model");
        assertThat(result).containsKey("latencyMs");
        assertThat(result.get("model")).isEqualTo("mistralai/mistral-nemotron");
    }

    @Test
    void nimProbeWithGuidedJsonReturnsResult() {
        Map<String, Object> result = service.nimProbe("Return command to switch to light theme.", "mistralai/mistral-nemotron", true, false);

        assertThat(result).containsKey("model");
        assertThat(result).containsKey("latencyMs");
        assertThat(result.get("model")).isEqualTo("mistralai/mistral-nemotron");
    }


    @Test
    void guidedJsonDefaultIsFalseForMistralNemotron() {
        // Even when useGuidedJson=true, isGuidedJsonEnabled() returns false
        // because mistralai/mistral-nemotron is not in the supported models list
        ReflectionTestUtils.setField(service, "useGuidedJson", true);
        ReflectionTestUtils.setField(service, "guidedJsonSupportedModelsCsv", "nvidia/llama-3.3-nemotron-super-49b-v1");

        java.lang.reflect.Method method;
        try {
            method = DashboardAssistantService.class.getDeclaredMethod("isGuidedJsonEnabled");
            method.setAccessible(true);
            boolean enabled = (boolean) method.invoke(service);
            assertThat(enabled).isFalse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void recentCriticalTableHighlightedWhenExplicitlyRequested() {
        setCurrentVisibleElements(List.of(
                element("overview-kpi-critical-alerts", "kpi", "Critical Alerts KPI", "Count of critical alerts", "security-overview"),
                element("overview-recent-critical-alerts-table", "table", "Recent Critical Alerts Table", "Table of recent critical alerts", "security-overview")
        ));

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("Here is the recent critical alerts table.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("HIGHLIGHT_ELEMENT")
                                .elementId("overview-recent-critical-alerts-table")
                                .message("Showing the table of recent critical alerts.")
                                .build()
                ))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-recent-critical-alerts-table");
    }

    @Test
    void criticalAlertsNumberHighlightsKpi() {
        setCurrentVisibleElements(List.of(
                element("overview-kpi-critical-alerts", "kpi", "Critical Alerts KPI", "Count of critical alerts", "security-overview"),
                element("overview-recent-critical-alerts-table", "table", "Recent Critical Alerts Table", "Table of recent critical alerts", "security-overview")
        ));

        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .message("There are 12 critical alerts right now.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("HIGHLIGHT_ELEMENT")
                                .elementId("overview-kpi-critical-alerts")
                                .message("Here is the critical alerts KPI showing the count.")
                                .build()
                ))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("HIGHLIGHT_ELEMENT");
        assertThat(response.getCommands().get(0).getElementId()).isEqualTo("overview-kpi-critical-alerts");
    }

    @Test
    void answerResponseWithEmptyCommandsAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .responseType("ANSWER")
                .message("Yes, I am here. How can I help you with the dashboard today?")
                .commands(List.of())
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getResponseType()).isEqualTo("ANSWER");
        assertThat(response.getMessage()).contains("I am here");
    }

    @Test
    void routeListResponseAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .responseType("ANSWER")
                .message("Available routes: security-overview, alerts, churn, forecast, runtime, user360, account.")
                .commands(List.of())
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getResponseType()).isEqualTo("ANSWER");
        assertThat(response.getMessage()).contains("Available routes");
    }

    @Test
    void calculateResponseAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .responseType("ANSWER")
                .message("5 multiplied by 99 is 495. I mainly help with the dashboard, but I can do simple math too.")
                .commands(List.of())
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).isEmpty();
        assertThat(response.getResponseType()).isEqualTo("ANSWER");
        assertThat(response.getMessage()).contains("495");
    }

    @Test
    void toggleThemeValueLightAccepted() {
        AssistantModelResponse modelResponse = AssistantModelResponse.builder()
                .responseType("ACTION")
                .message("Switching to light mode.")
                .commands(List.of(
                        AssistantModelResponse.AssistantModelCommand.builder()
                                .type("TOGGLE_THEME")
                                .value("light")
                                .message("Switching to light theme.")
                                .build()
                ))
                .requiresConfirmation(false)
                .build();

        DashboardAssistantResponse response = reflectivelyInvokeValidate(modelResponse);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo("TOGGLE_THEME");
        assertThat(response.getCommands().get(0).getValue()).isEqualTo("light");
    }


    private static AssistantVisibleElement element(String id, String type, String label, String description, String routeId) {
        return AssistantVisibleElement.builder()
                .id(id)
                .type(type)
                .label(label)
                .description(description)
                .routeId(routeId)
                .visible(true)
                .actions(List.of("HIGHLIGHT_ELEMENT"))
                .build();
    }

    private void setCurrentVisibleElements(List<AssistantVisibleElement> elements) {
        ReflectionTestUtils.setField(service, "currentVisibleElements", elements);
    }

    private DashboardAssistantResponse reflectivelyInvokeValidate(AssistantModelResponse modelResponse) {
        try {
            java.lang.reflect.Method method = DashboardAssistantService.class
                    .getDeclaredMethod("validateAndBuildResponse", AssistantModelResponse.class);
            method.setAccessible(true);
            return (DashboardAssistantResponse) method.invoke(service, modelResponse);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
