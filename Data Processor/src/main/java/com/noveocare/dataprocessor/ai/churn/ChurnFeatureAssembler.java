package com.noveocare.dataprocessor.ai.churn;

import com.noveocare.dataprocessor.ai.TextNormalization;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles session-level feature vectors for churn prediction from session
 * summaries and raw event data.
 */
@Service
public class ChurnFeatureAssembler {

    /* ========== Public API ========== */

    /* Builds a flat feature map from session summary and events. */
    public Map<String, Object> assemble(SessionSummary summary, List<AuditTrailEvent> events, boolean anomalousUser) {
        Map<String, Object> values = new LinkedHashMap<>();
        int total = summary == null || summary.getTotalEvents() == null ? 0 : summary.getTotalEvents();
        values.put("total_actions", total);
        values.put("total_sessions", 1.0);
        values.put("days_active", 1.0);
        values.put("days_active_ratio", 0.0);
        values.put("avg_session_duration_ms", summary == null || summary.getTotalDurationSeconds() == null ? 0.0 : summary.getTotalDurationSeconds() * 1000.0);
        values.put("avg_actions_per_session", total);
        values.put("sessions_per_week", 0.0);
        values.put("avg_inter_action_ms", summary == null || summary.getAvgInterActionSeconds() == null ? 0.0 : summary.getAvgInterActionSeconds() * 1000.0);
        values.put("pages_visited", summary == null || summary.getUniqueRoutes() == null ? 0 : summary.getUniqueRoutes());
        values.put("pct_logging_actions", actionTypeRatio(events, "logging"));
        values.put("pct_contact_actions", actionTypeRatio(events, "contact"));
        values.put("pct_document_actions", actionTypeRatio(events, "document"));
        values.put("pct_banking_actions", actionTypeRatio(events, "banking"));
        values.put("pct_insured_actions", actionTypeRatio(events, "insured"));
        values.put("pct_open_actions", actionTypeRatio(events, "open"));
        values.put("failure_rate", total == 0 || summary == null ? 0.0 : (double) defaultInt(summary.getTotalKOs()) / total);
        values.put("weekend_ratio", summary == null ? 0.0 : defaultInt(summary.getIsWeekend()));
        values.put("business_hours_ratio", businessHoursRatio(events));
        values.put("mobile_ratio", deviceRatio(events, "mobile"));
        values.put("desktop_ratio", deviceRatio(events, "desktop"));
        values.put("tablet_ratio", deviceRatio(events, "tablet"));
        values.put("unique_devices", summary == null ? 0 : summary.getUniqueDevicesUsed());
        values.put("unique_countries", summary == null ? 0 : summary.getUniqueIpsUsed());
        values.put("fr_events_ratio", frRatio(events));
        values.put("is_anomalous_user", anomalousUser ? 1.0 : 0.0);
        values.put("anomaly_event_count", summary == null ? 0.0 : defaultInt(summary.getAnomalyEventCount()));
        values.put("anomaly_ratio", total == 0 || summary == null ? 0.0 : (double) defaultInt(summary.getAnomalyEventCount()) / total);
        values.put("primary_device", primary(events, "device"));
        values.put("primary_browser", primary(events, "browser"));
        values.put("primary_os", primary(events, "os"));
        values.put("primary_region", primary(events, "region"));
        values.put("is_confuser", 0.0);
        return values;
    }

    /* ========== Private helpers ========== */

    /* Proportion of events occurring during business hours (8-19). */
    private double businessHoursRatio(List<AuditTrailEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        long count = events.stream().filter(event -> event.getIsBusinessHours() != null && event.getIsBusinessHours() == 1).count();
        return (double) count / events.size();
    }

    /* Proportion of events originating from France. */
    private double frRatio(List<AuditTrailEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        long count = events.stream().filter(event -> "fr".equals(TextNormalization.comparisonKey(firstNonBlank(event.getIpCountry(), event.getCountryCode())))).count();
        return (double) count / events.size();
    }

    /* Proportion of events using a given device type. */
    private double deviceRatio(List<AuditTrailEvent> events, String device) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        long count = events.stream().filter(event -> TextNormalization.comparisonKey(event.getDevice()).contains(device)).count();
        return (double) count / events.size();
    }

    /* Proportion of events matching a given action type keyword. */
    private double actionTypeRatio(List<AuditTrailEvent> events, String actionType) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        long count = events.stream()
                .filter(event -> TextNormalization.comparisonKey(firstNonBlank(event.getActionType(), event.getType())).contains(actionType))
                .count();
        return (double) count / events.size();
    }

    /* Returns the most frequent value for a given field (device, browser, os, region). */
    private String primary(List<AuditTrailEvent> events, String field) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AuditTrailEvent event : events) {
            String value = switch (field) {
                case "device" -> event.getDevice();
                case "browser" -> event.getBrowser();
                case "os" -> event.getOs();
                case "region" -> event.getIpRegion();
                default -> null;
            };
            if (value != null && !value.isBlank()) {
                String normalized = TextNormalization.comparisonKey(value).replace(' ', '-');
                counts.put(normalized, counts.getOrDefault(normalized, 0L) + 1L);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /* Utility: returns first non-blank value. */
    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /* Utility: null-safe integer default. */
    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
