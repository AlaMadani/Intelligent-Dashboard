package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A validated command returned to the frontend for execution. This is the
 * sanitised version of the raw model command; invalid or unsafe commands are
 * filtered out during validation in {@link DashboardAssistantService}.
 * <p>
 * Fields are conditionally serialised (null values are omitted).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardAssistantCommand {
    /** Command type: NAVIGATE, HIGHLIGHT_ELEMENT, CLICK_ELEMENT, SET_FILTER, etc. */
    private String type;
    /** Target route name for NAVIGATE commands (maps to a frontend route). */
    private String routeName;
    /** Route parameters (e.g. eventId for alert-investigation). */
    private Map<String, String> params;
    /** Element ID for HIGHLIGHT_ELEMENT / CLICK_ELEMENT. */
    private String elementId;
    /** Panel ID for OPEN_PANEL commands. */
    private String panelId;
    /** Search query for SEARCH_ALERT / SEARCH_USER / SEARCH_SESSION. */
    private String query;
    /** Filter or refresh target identifier. */
    private String target;
    /** Value to apply (filter value, theme name, etc.). */
    private String value;
    /** Human-readable description of the command for the user. */
    private String message;
}
