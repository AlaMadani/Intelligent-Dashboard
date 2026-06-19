package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Alert payload shared between the detection pipeline, persistence, and Kafka publishing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyAlert {
    private String schemaVersion;
    // Event and session identifiers that let consumers correlate the alert back to source data.
    private String recordId;
    private String insuredId;
    private String sessionId;
    private String eventId;

    // Detection outputs coming from rules and ML models.
    private String anomalyTier;
    private String anomalyType;
    private Double anomalyScore;
    private Double anomalyProbability;
    private Double typeConfidence;
    private String ruleType;
    private Boolean anomalyFlag;
    private Double churnProbability;
    private Double riskScore;
    private Integer personaCluster;
    private String personaLabel;
    private Boolean pathDeviation;
    private Double transitionProbability;
    private String transitionFromAction;
    private String transitionToAction;
    private String modelArtifact;
    private Double aiRiskScore;
    private Double ruleRiskScore;
    private Double finalRiskScore;
    private String riskLevel;
    private String riskTier;
    private String riskScale;
    private String anomalyTypeSource;
    private Double anomalyTypeConfidence;
    private java.util.Map<String, Object> anomalyTypeEvidence;
    private java.util.Map<String, Object> modelScores;
    private java.util.Map<String, Object> modelContributions;
    private java.util.List<String> triggeredRules;
    private java.util.Map<String, Object> churn;
    private java.util.Map<String, Object> persona;
    private Boolean llmEvidencePayloadAvailable;
    private String llmEvidencePayloadRedisKey;
    private java.util.Map<String, String> artifactNames;
    private String eventAction;
    private String apiTemplate;
    private String apiFamily;
    private String controller;
    private String page;
    private String country;
    private String device;
    private String browser;
    private String os;
    private String httpMethod;
    private String status;
    private java.util.Map<String, Object> eventMetadata;
    private java.util.Map<String, Object> sequenceEvidence;
    private java.util.Map<String, Object> tabularEvidence;
    private List<NextActionScore> nextActions;

    // Timestamps for the triggering event and the actual detection moment.
    private Instant eventTime;
    private Instant detectedAt;
}
