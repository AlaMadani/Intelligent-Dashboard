package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardAssistantResponse {
    private String responseType;
    private String message;
    @Builder.Default
    private List<DashboardAssistantCommand> commands = new ArrayList<>();
    private boolean requiresConfirmation;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    private Map<String, Object> debug;
}
