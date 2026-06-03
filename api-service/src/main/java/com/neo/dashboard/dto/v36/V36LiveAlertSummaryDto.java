package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36LiveAlertSummaryDto {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private Long id;
    private Long anomalyDbId;
    private String eventId;
    private String recordId;
    private String insuredId;
    private String sessionId;
    private Instant timestamp;
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
    private String riskLevel;
    private Double finalRiskScore;
    private String anomalyType;
    private Double anomalyTypeConfidence;
    private Double xgboostAnomalyScore;
    private Double xgboostAnomalyScore100;
    private Double lightgbmAlertScore;
    private Double lightgbmAlertScore100;
    private Double transformerRiskScore100;
    private Double tcnRiskScore100;
    private Double ruleRiskScore;
    private V36ModelContributionsDto modelContributions;
    @JsonAlias("triggeredRules")
    private List<String> triggeredRuleCodes;
    private Double churnProbability;
    private String churnRiskLevel;
    private String personaLabel;
    private Boolean llmEvidencePayloadAvailable;
    @JsonAlias({"llmEvidencePayloadRedisKey", "llmEvidenceRedisKey"})
    private String llmEvidenceRedisKey;
    private String alertStatus;
    private Instant createdAt;
    private String source;
    private List<String> warnings;
}
