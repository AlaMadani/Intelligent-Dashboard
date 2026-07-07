package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.next-event-prediction")
public class NextEventPredictionProperties {
    private boolean enabled = false;
    private int topK = 5;
    private int minContextEvents = 3;
    private String modelPreference = "transformer";
    private boolean writeRedis = true;
    private boolean writeSql = true;
    private boolean evaluateDeviation = true;
    private boolean affectRiskScore = false;

    private List<String> heads = List.of(
            "frontend_action_name",
            "action_value",
            "page",
            "api_template",
            "api_family",
            "http_method",
            "status"
    );
}
