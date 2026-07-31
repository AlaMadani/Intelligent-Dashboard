package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Detects timing anomalies such as extremely dense event bursts and unusually
 * long gaps inside a single session.
 */
@Component
@RequiredArgsConstructor
public class VelocityDetector {

    /* ---- Dependencies ---- */
    private final RuleProperties ruleProperties;

    /* ---- Public API ---- */

    public boolean isRapidFire(List<AuditTrailEvent> sessionEvents) {
        // A rapid-fire anomaly is defined as N events occurring within a tiny time window.
        int minEvents = ruleProperties.getRapidFire().getMinEvents();
        int windowSeconds = ruleProperties.getRapidFire().getWindowSeconds();

        if (sessionEvents.size() < minEvents) {
            return false;
        }

        for (int i = 0; i <= sessionEvents.size() - minEvents; i++) {
            AuditTrailEvent startEvent = sessionEvents.get(i);
            AuditTrailEvent endEvent = sessionEvents.get(i + minEvents - 1);

            // Compare the first and last events of each sliding window of size minEvents.
            if (startEvent.getCreatedAt() != null && endEvent.getCreatedAt() != null) {
                Duration duration = Duration.between(startEvent.getCreatedAt(), endEvent.getCreatedAt());
                if (Math.abs(duration.getSeconds()) <= windowSeconds) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isSessionTimeout(List<AuditTrailEvent> sessionEvents) {
        // A session timeout anomaly is any consecutive gap that exceeds the configured threshold.
        if (sessionEvents.size() < 2) {
            return false;
        }

        long thresholdSeconds = ruleProperties.getSessionTimeout().getThresholdSeconds();

        for (int i = 1; i < sessionEvents.size(); i++) {
            AuditTrailEvent prev = sessionEvents.get(i - 1);
            AuditTrailEvent curr = sessionEvents.get(i);

            // Evaluate each neighboring pair because a single long pause is enough to flag the session.
            if (prev.getCreatedAt() != null && curr.getCreatedAt() != null) {
                Duration duration = Duration.between(prev.getCreatedAt(), curr.getCreatedAt());
                if (duration.getSeconds() > thresholdSeconds) {
                    return true;
                }
            }
        }
        return false;
    }
}
