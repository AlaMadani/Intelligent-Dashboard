package com.neo.dashboard.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantVisibleElement {
    private String id;
    private String type;
    private String label;
    private String description;
    private String routeId;
    private boolean visible;
    private List<String> actions;
    private String textContent;
}
