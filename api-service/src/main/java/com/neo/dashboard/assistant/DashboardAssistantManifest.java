package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardAssistantManifest {
    private String version;
    private String updatedAt;
    private List<ManifestRoute> routes;
    private List<ManifestElement> elements;
    private List<ManifestTask> tasks;
    private List<ManifestPanel> panels;
    private List<ManifestFilter> filters;
    private List<ManifestRefreshTarget> refreshTargets;
    private List<ManifestSearchTarget> searchTargets;

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
