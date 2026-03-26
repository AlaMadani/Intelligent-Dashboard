package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FutureBehaviorPredictionService {

    private final ObjectMapper objectMapper;

    public PredictionResult predict(List<AuditTrailEvent> events) {
        // Handle empty input defensively.
        if (events == null || events.isEmpty()) {
            return new PredictionResult(
                    "No events available to predict future behavior.",
                    "{\"note\":\"no_events\"}"
            );
        }

        // Aggregate per-action counts and failure rate.
        Map<String, Long> actionCounts = new LinkedHashMap<>();
        int failures = 0;

        for (AuditTrailEvent event : events) {
            String actionKey = normalizeAction(event);
            actionCounts.put(actionKey, actionCounts.getOrDefault(actionKey, 0L) + 1L);
            if (!Boolean.TRUE.equals(event.getSuccess())) {
                failures += 1;
            }
        }

        // Determine the most frequent action.
        String mostFrequentAction = null;
        long maxCount = -1;
        for (Map.Entry<String, Long> entry : actionCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mostFrequentAction = entry.getKey();
            }
        }

        // Compute simple heuristic scores.
        double failureRate = (double) failures / (double) events.size();
        String riskLevel = failureRate >= 0.6 ? "HIGH" : (failureRate >= 0.3 ? "MEDIUM" : "LOW");
        double confidence = maxCount > 0 ? ((double) maxCount / (double) events.size()) : 0.0;

        // Build a structured payload for downstream storage.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("most_likely_next_action", mostFrequentAction);
        payload.put("confidence", confidence);
        payload.put("failure_rate", failureRate);
        payload.put("risk_level", riskLevel);
        payload.put("recent_action_distribution", actionCounts);
        payload.put("note", "heuristic_prediction");

        // Create a concise human-readable summary.
        String summary = "Heuristic prediction based on the last "
                + events.size()
                + " actions. Most likely next action: "
                + mostFrequentAction
                + " (confidence "
                + String.format("%.2f", confidence)
                + "), risk level "
                + riskLevel
                + ".";

        try {
            // Serialize the prediction payload.
            return new PredictionResult(summary, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize prediction payload", e);
            return new PredictionResult(summary, "{\"note\":\"serialization_error\"}");
        }
    }

    private static String normalizeAction(AuditTrailEvent event) {
        String action = event.getAction() != null ? event.getAction().toUpperCase() : "UNKNOWN";
        String status = Boolean.TRUE.equals(event.getSuccess()) ? "SUCCESS" : "FAILED";
        return action + "_" + status;
    }

    public static class PredictionResult {
        private final String summary;
        private final String json;

        public PredictionResult(String summary, String json) {
            this.summary = summary;
            this.json = json;
        }

        public String getSummary() {
            return summary;
        }

        public String getJson() {
            return json;
        }
    }
}
