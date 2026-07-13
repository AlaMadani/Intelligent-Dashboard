package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashboardAssistantRequest {
    private String message;
    private String currentRoute;
    private Map<String, String> currentContext;
    private List<AssistantVisibleElement> visibleElements;
    private boolean debug;
    private String model;
}
