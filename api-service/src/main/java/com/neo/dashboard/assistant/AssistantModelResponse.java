package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantModelResponse {
    private String responseType;
    private String message;
    @Builder.Default
    private List<AssistantModelCommand> commands = new ArrayList<>();
    private boolean requiresConfirmation;

    public void setCommands(List<AssistantModelCommand> commands) {
        this.commands = commands != null ? commands : new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssistantModelCommand {
        private String type;
        private String routeName;
        private java.util.Map<String, String> params;
        private String elementId;
        private String panelId;
        private String query;
        private String target;
        private String value;
        private String message;
    }
}
