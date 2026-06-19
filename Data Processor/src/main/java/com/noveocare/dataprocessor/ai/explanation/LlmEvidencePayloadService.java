package com.noveocare.dataprocessor.ai.explanation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.AiLlmExplanationProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlmEvidencePayloadService {

    private final AiLlmExplanationProperties properties;
    private final ObjectMapper objectMapper;

    public Map<String, Object> build(SessionSummary summary, AuditTrailEvent event, SessionInsight insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("evidenceVersion", "1.0");
        payload.put("eventId", event == null ? null : event.getId());
        payload.put("recordId", event == null ? null : event.getId());
        payload.put("insuredId", summary == null ? null : summary.getInsuredId());
        payload.put("sessionId", summary == null ? null : summary.getSessionId());
        payload.put("timestamp", event == null ? null : event.getCreatedAt());
        payload.put("eventMetadata", eventMetadata(event));
        payload.put("userMetadata", userMetadata(summary, insight));
        payload.put("sessionMetadata", sessionMetadata(summary));
        payload.put("risk", Map.of(
                "finalRiskScore", valueOrZero(insight.getFinalRiskScore()),
                "riskLevel", valueOrDefault(insight.getRiskLevel(), "LOW"),
                "fallbackMode", valueOrDefault(insight.getFallbackMode(), "UNKNOWN")));
        payload.put("modelScores", nullToMap(insight.getModelScores()));
        payload.put("modelContributions", nullToMap(insight.getModelContributions()));
        payload.put("triggeredRules", insight.getRuleContributions() == null ? List.of() : insight.getRuleContributions());
        payload.put("sequenceEvidence", sequenceEvidence(insight));
        payload.put("tabularEvidence", tabularEvidence(insight));
        payload.put("ruleEvidence", insight.getRuleEvidence() == null ? Map.of() : insight.getRuleEvidence());
        payload.put("anomalyTypeAttribution", Map.of(
                "anomalyType", valueOrDefault(insight.getAnomalyType(), "unknown_suspicious_behavior"),
                "confidence", insight.getAnomalyTypeConfidence() == null ? 0.0 : insight.getAnomalyTypeConfidence(),
                "source", valueOrDefault(insight.getAnomalyTypeSource(), "unknown"),
                "evidence", insight.getAnomalyTypeEvidence() == null ? Map.of() : insight.getAnomalyTypeEvidence()));
        Map<String, Object> churnContext = new LinkedHashMap<>();
        churnContext.put("churnProbability", insight.getChurnProbability());
        churnContext.put("churnRiskLevel", valueOrDefault(insight.getChurnRiskLevel(), "UNKNOWN"));
        payload.put("churnContext", churnContext);
        payload.put("forecastContext", insight.getForecastContext() == null ? Map.of() : insight.getForecastContext());
        payload.put("runtimeWarnings", insight.getWarnings() == null ? List.of() : insight.getWarnings());
        payload.put("evidenceReferences", Map.of(
                "featureContract", "tabular_anomaly_feature_contract.json",
                "runtimeHealthKey", "ai:runtime:health:v3_6"));
        payload.put("llmInstruction", Map.of(
                "consumer", valueOrDefault(properties.getHandledBy(), "api-service"),
                "task", "Explain why this event is suspicious using only this evidence.",
                "doNotInventEvidence", properties.isDoNotInventEvidence()));
        payload.put("llmExplanationInDataprocessor", false);
        payload.put("evidenceSummary", evidenceSummary(insight));
        payload.put("evidenceHash", computeEvidenceHash(payload));
        payload.put("evidenceCreatedAt", Instant.now().toString());
        return payload;
    }

    private String computeEvidenceHash(Map<String, Object> payload) {
        try {
            String canonicalJson = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException e) {
            log.warn("EVIDENCE_HASH_FAILED json error: {}", e.getMessage());
            return "hash_error";
        } catch (NoSuchAlgorithmException e) {
            log.warn("EVIDENCE_HASH_FAILED no such algorithm: {}", e.getMessage());
            return "hash_error";
        }
    }

    private Map<String, Object> eventMetadata(AuditTrailEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (event == null) {
            return metadata;
        }
        metadata.put("eventId", event.getId());
        metadata.put("eventAction", event.getAction());
        metadata.put("apiTemplate", event.getApiTemplate());
        metadata.put("apiFamily", event.getApiFamily());
        metadata.put("controller", event.getController());
        metadata.put("page", event.getPage());
        metadata.put("country", firstNonBlank(event.getIpCountry(), event.getCountryCode()));
        metadata.put("device", event.getDevice());
        metadata.put("browser", event.getBrowser());
        metadata.put("os", event.getOs());
        metadata.put("httpMethod", event.getHttpMethod());
        metadata.put("status", event.getStatus());
        metadata.put("requestDataSizeBytes", event.getRequestDataSizeBytes());
        metadata.put("responseDataSizeBytes", event.getResponseDataSizeBytes());
        return metadata;
    }

    private Map<String, Object> userMetadata(SessionSummary summary, SessionInsight insight) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("insuredId", summary == null ? null : summary.getInsuredId());
        metadata.put("personaEnabled", false);
        metadata.put("personaCluster", insight.getPersonaCluster());
        metadata.put("personaLabel", insight.getPersonaLabel());
        metadata.put("personaSource", insight.getPersonaSource());
        metadata.put("personaConfidence", insight.getPersonaConfidence());
        metadata.put("personaWarnings", insight.getPersonaWarnings() == null ? List.of("persona_skipped_for_now") : insight.getPersonaWarnings());
        return metadata;
    }

    private Map<String, Object> sessionMetadata(SessionSummary summary) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (summary == null) {
            return metadata;
        }
        metadata.put("sessionId", summary.getSessionId());
        metadata.put("totalEvents", summary.getTotalEvents());
        metadata.put("totalKOs", summary.getTotalKOs());
        metadata.put("maxDownloadsIn2Minutes", summary.getMaxDownloadsIn2Minutes());
        metadata.put("pingPongCount", summary.getPingPongCount());
        metadata.put("deviceChanged", summary.getDeviceChanged());
        metadata.put("ipChanged", summary.getIpChanged());
        metadata.put("actionSequenceSignature", summary.getActionSequenceSignature());
        metadata.put("routeSequenceSignature", summary.getRouteSequenceSignature());
        return metadata;
    }

    private Map<String, Object> sequenceEvidence(SessionInsight insight) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("selectedSequenceModel", insight.getSelectedSequenceModel());
        evidence.put("sequenceModelArtifact", insight.getSequenceModelArtifact());
        evidence.put("contextAvailable", Boolean.TRUE.equals(insight.getSequenceContextAvailable()));
        evidence.put("windowSize", 10);
        evidence.put("sequenceRunBoth", insight.getSequenceRunBoth());
        evidence.put("sequenceActuallyRanModels", insight.getSequenceActuallyRanModels() == null
                ? List.of() : insight.getSequenceActuallyRanModels());
        evidence.put("transformerUsedInFusion", Boolean.TRUE.equals(insight.getTransformerUsedInFusion()));
        evidence.put("tcnUsedInFusion", Boolean.TRUE.equals(insight.getTcnUsedInFusion()));
        evidence.put("transformerSurpriseScoreRaw", insight.getTransformerScore());
        evidence.put("transformerRiskScore100", insight.getTransformerRiskScore100());
        evidence.put("tcnSurpriseScoreRaw", insight.getTcnScore());
        evidence.put("tcnRiskScore100", insight.getTcnRiskScore100());
        evidence.put("topSurpriseFields", insight.getSequenceTopContributions() == null
                ? List.of()
                : insight.getSequenceTopContributions().stream().map(item -> item.getField()).toList());
        return evidence;
    }

    private Map<String, Object> tabularEvidence(SessionInsight insight) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("featureContract", "tabular_anomaly_feature_contract.json");
        evidence.put("availableModels", insight.getAvailableTabularModels() == null ? List.of() : insight.getAvailableTabularModels());
        evidence.put("unavailableModels", insight.getUnavailableTabularModels() == null ? List.of() : insight.getUnavailableTabularModels());
        evidence.put("warnings", insight.getTabularWarnings() == null ? List.of() : insight.getTabularWarnings());
        return evidence;
    }

    private String evidenceSummary(SessionInsight insight) {
        return "risk=" + valueOrZero(insight.getFinalRiskScore())
                + "; level=" + valueOrDefault(insight.getRiskLevel(), "LOW")
                + "; type=" + valueOrDefault(insight.getAnomalyType(), "unknown_suspicious_behavior")
                + "; fallback=" + valueOrDefault(insight.getFallbackMode(), "UNKNOWN");
    }

    private Map<String, Object> nullToMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
