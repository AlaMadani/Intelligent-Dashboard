package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardAssistantCommand {
    private String type;
    private String routeName;
    private Map<String, String> params;
    private String elementId;
    private String panelId;
    private String query;
    private String target;
    private String value;
    private String message;
}
