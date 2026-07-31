package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * "User 360" response DTO aggregating all data known about a single
 * insured user.
 * <p>
 * Bundles persona assignment, churn prediction, risk assessment,
 * behavioral baseline, recent sessions, risk timeline, and next-event
 * prediction into a single response for the user-360 dashboard view.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36User360Response {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** The insured person identifier this 360 view relates to. */
    private String insuredId;
    /** Persona/cluster assignment for this user. */
    private V36PersonaDisabledDto persona;
    /** Churn-prediction data (probability, risk level, etc.). */
    private Map<String, Object> churn;
    /** Risk-assessment data (scores, levels, history). */
    private Map<String, Object> risk;
    /** Behavioral baseline / profile for the user. */
    private Map<String, Object> baseline;
    /** List of the user's most recent sessions with summary data. */
    private List<Map<String, Object>> recentSessions;
    /** Timeline of risk-score changes over time. */
    private List<Map<String, Object>> riskTimeline;
    /** Next-event prediction for this user's current session. */
    private Map<String, Object> nextEventPrediction;
}
