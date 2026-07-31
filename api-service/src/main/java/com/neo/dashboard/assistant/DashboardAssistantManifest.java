package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Root JSON structure for the dashboard assistant manifest file. The manifest
 * is loaded from {@code dashboard-assistant/dashboardAssistantManifest.json}
 * and defines all routes, elements, tasks, panels, filters, refresh targets,
 * and search targets that the assistant is aware of.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardAssistantManifest {
    /** Schema version of the manifest file. */
    private String version;
    /** ISO-8601 timestamp of the last manifest update. */
    private String updatedAt;
    /** All dashboard routes the assistant can navigate to. */
    private List<ManifestRoute> routes;
    /** All UI elements the assistant can highlight or interact with. */
    private List<ManifestElement> elements;
    /** High-level tasks the assistant can perform across routes. */
    private List<ManifestTask> tasks;
    /** Slide-out panels the assistant can open. */
    private List<ManifestPanel> panels;
    /** Data view filters the assistant can apply. */
    private List<ManifestFilter> filters;
    /** Views that can be refreshed via the assistant. */
    private List<ManifestRefreshTarget> refreshTargets;
    /** Searchable data sources (alerts, users, sessions). */
    private List<ManifestSearchTarget> searchTargets;

    /** A single dashboard route with its display name, path, synonyms, and required parameters. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestRoute {
        private String id;
        private String routeName;
        private String path;
        private String label;
        private String description;
        @Builder.Default
        private List<String> synonyms = Collections.emptyList();
        @Builder.Default
        private List<String> requiresParams = Collections.emptyList();
        @Builder.Default
        private List<String> actionsSupported = Collections.emptyList();
    }

    /** A UI element that the assistant can highlight or perform actions on. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestElement {
        private String id;
        private String routeId;
        private String routeName;
        private String type;
        private String label;
        private String description;
        @Builder.Default
        private List<String> synonyms = Collections.emptyList();
        @Builder.Default
        private List<String> exampleUserQuestions = Collections.emptyList();
        @Builder.Default
        private List<String> actionsSupported = Collections.emptyList();
        private String visibility;
    }

    /** A cross-route task that may require context from the current page. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestTask {
        private String id;
        private String routeId;
        private String routeName;
        private String type;
        private String label;
        private String description;
        @Builder.Default
        private List<String> synonyms = Collections.emptyList();
        @Builder.Default
        private List<String> exampleUserQuestions = Collections.emptyList();
        @Builder.Default
        private List<String> actionsSupported = Collections.emptyList();
        @Builder.Default
        private List<String> requiresContext = Collections.emptyList();
        private String missingContextMessage;
    }

    /** A collapsible slide-out panel that can be opened. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestPanel {
        private String id;
        private String label;
        private String description;
        @Builder.Default
        private List<String> synonyms = Collections.emptyList();
        @Builder.Default
        private List<String> actionsSupported = Collections.emptyList();
    }

    /** A filter that can be applied to a data view (e.g. risk-level filter). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestFilter {
        private String id;
        private String routeId;
        private String routeName;
        private String label;
        private String description;
        @Builder.Default
        private List<String> allowedValues = Collections.emptyList();
        @Builder.Default
        private List<String> synonyms = Collections.emptyList();
        @Builder.Default
        private List<String> aliases = Collections.emptyList();
        @Builder.Default
        private List<String> actionsSupported = Collections.emptyList();
    }

    /** A view whose data can be refreshed via the assistant. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestRefreshTarget {
        private String id;
        private String routeId;
        private String routeName;
        private String label;
        private String description;
        @Builder.Default
        private List<String> synonyms = Collections.emptyList();
        @Builder.Default
        private List<String> actionsSupported = Collections.emptyList();
    }

    /** A searchable data source (alerts, users, sessions). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestSearchTarget {
        private String id;
        private String routeId;
        private String routeName;
        private String label;
        private String description;
        @Builder.Default
        private List<String> params = Collections.emptyList();
        @Builder.Default
        private List<String> synonyms = Collections.emptyList();
        @Builder.Default
        private List<String> actionsSupported = Collections.emptyList();
    }
}
