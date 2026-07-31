package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a single UI element visible on the current page, as reported by
 * the frontend. The assistant uses this information to match user requests
 * (e.g. "highlight the risk score card") to concrete element identifiers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantVisibleElement {
    /** Unique element identifier matching the frontend DOM (e.g. "card-risk-score"). */
    private String id;
    /** Semantic type: "button", "card", "table", "nav-item", "kpi", etc. */
    private String type;
    /** Human-readable label shown to the user. */
    private String label;
    /** Longer description of what this element represents. */
    private String description;
    /** The route this element belongs to. */
    private String routeId;
    /** Whether the element is currently visible in the viewport. */
    private boolean visible;
    /** List of supported assistant actions (e.g. HIGHLIGHT_ELEMENT, CLICK_ELEMENT). */
    private List<String> actions;
    /** The rendered text content of the element (e.g. "Risk Score: 85"). */
    private String textContent;
}
