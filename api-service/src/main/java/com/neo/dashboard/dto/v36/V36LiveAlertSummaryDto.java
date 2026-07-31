package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight DTO for displaying an alert in a live-alerts list / table.
 * <p>
 * Contains the most important fields for a summary row: event metadata,
 * risk scores, model scores, churn/persona context, LLM availability,
 * and session-end information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36LiveAlertSummaryDto {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Primary alert identifier. */
    private Long id;
    /** Foreign key to the anomaly record in the persistence database. */
    private Long anomalyDbId;
    /** Unique event identifier. */
    private String eventId;
    /** Record / transaction identifier within the source system. */
    private String recordId;
    /** The insured person identifier this alert relates to. */
    private String insuredId;
    /** Session identifier grouping multiple events for the same user. */
    private String sessionId;
    /** Timestamp when the original event occurred. */
    private Instant timestamp;
    /** The action/operation performed in the event (e.g. "LOGIN", "PAYMENT"). */
    private String eventAction;
    /** API template or endpoint pattern that handled the event. */
    private String apiTemplate;
    /** API family / functional group (e.g. "payment", "auth"). */
    private String apiFamily;
    /** Controller class or identifier that processed the event. */
    private String controller;
    /** Application page or screen where the event originated. */
    private String page;
    /** Country code derived from the request. */
    private String country;
    /** Device type derived from user-agent. */
    private String device;
    /** Browser name derived from user-agent. */
    private String browser;
    /** Operating system derived from user-agent. */
    private String os;
    /** HTTP method of the request (GET, POST, PUT, etc.). */
    private String httpMethod;
    /** HTTP response status code. */
    private String status;
    /** Qualitative risk level label (e.g. LOW, MEDIUM, HIGH, CRITICAL). */
    private String riskLevel;
    /** Final composite risk score after fusing all model outputs. */
    private Double finalRiskScore;
    /** Type/category of anomaly detected. */
    private String anomalyType;
    /** Confidence level of the anomaly-type classification. */
    private Double anomalyTypeConfidence;
    /** Raw XGBoost anomaly score (0.0 – 1.0). */
    private Double xgboostAnomalyScore;
    /** XGBoost anomaly score scaled to 0–100 range. */
    private Double xgboostAnomalyScore100;
    /** Raw LightGBM alert score (0.0 – 1.0). */
    private Double lightgbmAlertScore;
    /** LightGBM alert score scaled to 0–100 range. */
    private Double lightgbmAlertScore100;
    /** Transformer model risk score scaled to 0–100 range. */
    private Double transformerRiskScore100;
    /** TCN model risk score scaled to 0–100 range. */
    private Double tcnRiskScore100;
    /** Risk score contribution from rule-based evaluation. */
    private Double ruleRiskScore;
    /** Contribution of each model/component to the final risk score. */
    private V36ModelContributionsDto modelContributions;
    /** List of rule codes that triggered during evaluation. */
    @JsonAlias("triggeredRules")
    private List<String> triggeredRuleCodes;
    /** Predicted churn probability for the insured user. */
    private Double churnProbability;
    /** Qualitative churn risk level (e.g. LOW, MEDIUM, HIGH). */
    private String churnRiskLevel;
    /** Persona label assigned to the user (e.g. "power_user", "new_user"). */
    private String personaLabel;
    /** Whether an LLM-generated evidence payload is available. */
    private Boolean llmEvidencePayloadAvailable;
    /** Redis key under which the LLM evidence payload is stored. */
    @JsonAlias({"llmEvidencePayloadRedisKey", "llmEvidenceRedisKey"})
    private String llmEvidenceRedisKey;
    /** Current alert processing status (e.g. "OPEN", "INVESTIGATING", "RESOLVED"). */
    private String alertStatus;
    /** Instant when the alert was created. */
    private Instant createdAt;
    /** Source system or component that produced this alert. */
    private String source;
    /** Human-readable warning messages for the alert. */
    private List<String> warnings;
    /** Reason why the session ended (if applicable). */
    private String sessionEndReason;
    /** Whether the session was explicitly ended by the user/system. */
    private Boolean sessionEndedExplicitly;
}
