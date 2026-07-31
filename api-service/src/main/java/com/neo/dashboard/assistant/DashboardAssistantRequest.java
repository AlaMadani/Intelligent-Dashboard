package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Incoming request from the frontend to the dashboard assistant. Carries the
 * user's message along with contextual information such as the current route,
 * visible UI elements, and optional debug flags.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashboardAssistantRequest {
    /** The raw user message / question. */
    private String message;
    /** Identifier of the route the user is currently on (e.g. "alerts", "churn"). */
    private String currentRoute;
    /** Key-value context from the current page (e.g. eventId, insuredId). */
    private Map<String, String> currentContext;
    /** List of UI elements visible on the current page (reported by the frontend). */
    private List<AssistantVisibleElement> visibleElements;
    /** When true, the response will include detailed debug metadata. */
    private boolean debug;
    /** Optional model override for this specific request. */
    private String model;
}
