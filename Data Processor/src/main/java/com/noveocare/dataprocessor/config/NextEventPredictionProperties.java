package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for next-event prediction models.
 * Controls prediction cardinality (topK), model preference, persistence targets,
 * deviation evaluation, and the list of event header fields used as prediction heads.
 */
@Data
@ConfigurationProperties(prefix = "app.next-event-prediction")
public class NextEventPredictionProperties {
    /* --- Feature toggles --- */
    private boolean enabled = false;
    /* --- Prediction parameters --- */
    private int topK = 5;
    private int minContextEvents = 3;
    private String modelPreference = "transformer";
    /* --- Persistence targets --- */
    private boolean writeRedis = true;
    private boolean writeSql = true;
    /* --- Deviation and risk impact --- */
    private boolean evaluateDeviation = true;
    private boolean affectRiskScore = false;
    /* --- Prediction head fields --- */
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
