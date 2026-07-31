package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the raw JSON response parsed from the LLM model. The model is
 * instructed to return a JSON object with {@code responseType}, {@code message},
 * {@code commands}, and {@code requiresConfirmation}. Commands are represented
 * by the nested {@link AssistantModelCommand} DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantModelResponse {
    /** Type of response: ANSWER, ACTION, MIXED, CLARIFICATION, UNSUPPORTED. */
    private String responseType;
    /** Natural-language message from the model. */
    private String message;
    /** List of commands the model wants to execute. */
    @Builder.Default
    private List<AssistantModelCommand> commands = new ArrayList<>();
    /** Whether the user should confirm the action before execution. */
    private boolean requiresConfirmation;

    /** Ensures the commands list is never null (replaces null with an empty list). */
    public void setCommands(List<AssistantModelCommand> commands) {
        this.commands = commands != null ? commands : new ArrayList<>();
    }

    /**
     * A single command within the model response. Each field is optional and
     * used depending on the command type (e.g. NAVIGATE uses routeName,
     * HIGHLIGHT_ELEMENT uses elementId, SET_FILTER uses target + value).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssistantModelCommand {
        /** Command type: NAVIGATE, HIGHLIGHT_ELEMENT, CLICK_ELEMENT, etc. */
        private String type;
        /** Target route name for NAVIGATE commands. */
        private String routeName;
        /** Route parameters (e.g. eventId, insuredId). */
        private java.util.Map<String, String> params;
        /** Target element ID for HIGHLIGHT_ELEMENT / CLICK_ELEMENT. */
        private String elementId;
        /** Target panel ID for OPEN_PANEL commands. */
        private String panelId;
        /** Search query for SEARCH_ALERT / SEARCH_USER / SEARCH_SESSION. */
        private String query;
        /** Target identifier for SET_FILTER or REFRESH_VIEW. */
        private String target;
        /** Value to apply (e.g. filter value, theme name). */
        private String value;
        /** Human-readable description of the command. */
        private String message;
    }
}
