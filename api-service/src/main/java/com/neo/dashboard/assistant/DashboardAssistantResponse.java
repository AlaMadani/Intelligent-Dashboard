package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Response sent back to the frontend after processing a user request through
 * the dashboard assistant. Contains a response type, a natural-language
 * message, any validated commands to execute, and optional warnings or debug
 * information. Null fields are omitted from serialisation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardAssistantResponse {
    /** Type of response: ANSWER, ACTION, MIXED, CLARIFICATION, UNSUPPORTED. */
    private String responseType;
    /** Natural-language reply shown to the user. */
    private String message;
    /** List of validated dashboard commands to execute. */
    @Builder.Default
    private List<DashboardAssistantCommand> commands = new ArrayList<>();
    /** Whether the frontend should ask the user for confirmation before executing. */
    private boolean requiresConfirmation;
    /** Non-fatal warnings about command filtering (e.g. unknown element, invalid filter). */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    /** Debug metadata attached when the request has debug=true. */
    private Map<String, Object> debug;
}
