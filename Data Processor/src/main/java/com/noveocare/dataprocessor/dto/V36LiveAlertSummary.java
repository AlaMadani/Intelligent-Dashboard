package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
/**
 * Lightweight alert payload for the V36 live dashboard, mirroring a subset
 * of the full AnomalyAlert fields for low-latency display.
 */
public class V36LiveAlertSummary {
    /* Schema version and event identifiers. */
    String schemaVersion;
    String eventId;
    String recordId;
    String insuredId;
    String sessionId;
    String timestamp;
    /* Request context dimensions. */
    String eventAction;
    String apiTemplate;
    String apiFamily;
    String controller;
    String page;
    String country;
    String device;
    String browser;
    String os;
    String httpMethod;
    String status;
    /* Risk classification. */
    String riskLevel;
    String riskTier;
    String riskScale;
    Double finalRiskScore;
    /* Model-specific scores. */
    Double xgboostAnomalyScore;
    Double xgboostAnomalyScore100;
    Double lightgbmAlertScore;
    Double lightgbmAlertScore100;
    Double transformerRiskScore100;
    Double tcnRiskScore100;
    Double ruleRiskScore;
    Map<String, Object> modelScores;
    Map<String, Object> modelContributions;
    List<String> triggeredRuleCodes;
    /* Anomaly type and churn details. */
    String anomalyType;
    Double anomalyTypeConfidence;
    Double churnProbability;
    String churnRiskLevel;
    Boolean llmEvidencePayloadAvailable;
    String llmEvidencePayloadRedisKey;
    /* Mutable status and creation time. */
    @With
    String alertStatus;
    @With
    Instant createdAt;
    /* Evidence payloads for downstream investigation. */
    Map<String, Object> eventMetadata;
    Map<String, Object> sequenceEvidence;
    Map<String, Object> tabularEvidence;
}
