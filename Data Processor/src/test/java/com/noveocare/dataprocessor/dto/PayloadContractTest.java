package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DTO payload contracts: AnomalyAlert serialization/deserialization,
 * SessionInsight risk scale, and V36LiveAlertSummary round-trip.
 */
class PayloadContractTest {

    /* --- Fields --- */

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /* --- Test methods: alert payload --- */

    @Test
    @SuppressWarnings("unchecked")
    void alertPayloadContainsRequiredFields() {
        Map<String, Object> churn = new HashMap<>();
        churn.put("probability", null);
        churn.put("riskLevel", null);
        churn.put("modelName", "ExtraTrees");
        churn.put("artifact", "churn_profile_only_ExtraTrees.json");

        Map<String, Object> persona = new HashMap<>();
        persona.put("enabled", false);
        persona.put("label", null);
        persona.put("source", null);
        persona.put("confidence", null);
        persona.put("warnings", List.of());

        AnomalyAlert alert = AnomalyAlert.builder()
                .schemaVersion("v3.6.1")
                .recordId("evt-1")
                .insuredId("insured-1")
                .sessionId("sess-1")
                .eventId("evt-1")
                .anomalyTier("SESSION_RUNTIME")
                .anomalyType("unknown_suspicious_behavior")
                .anomalyScore(35.89)
                .anomalyProbability(0.3589)
                .typeConfidence(0.85)
                .anomalyFlag(true)
                .riskScore(35.89)
                .riskLevel("MEDIUM")
                .riskTier("MEDIUM")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .finalRiskScore(35.89)
                .aiRiskScore(99.59)
                .ruleRiskScore(35.0)
                .modelScores(new LinkedHashMap<>(Map.of(
                        "xgboostAnomalyScore", 0.0,
                        "xgboostAnomalyScore100", 0.22,
                        "lightgbmAlertScore", 0.03,
                        "lightgbmAlertScore100", 2.78,
                        "transformerSurpriseScoreRaw", 27.53,
                        "transformerRiskScore100", 99.59,
                        "ruleRiskScore", 35.0
                )))
                .modelContributions(new LinkedHashMap<>(Map.of(
                        "xgboost", 0.066,
                        "lightgbm", 0.696,
                        "transformer", 19.919,
                        "tcn", 0.0,
                        "rules", 5.25,
                        "businessContext", 0.0,
                        "aggregationBoost", 0.0
                )))
                .triggeredRules(List.of("API_SCRAPING_PATTERN"))
                .churn(churn)
                .persona(persona)
                .llmEvidencePayloadAvailable(false)
                .artifactNames(Map.of("ranking", "anomaly_xgboost.json", "alerting", "anomaly_lightgbm.txt"))
                .eventAction("login")
                .apiTemplate("/api/v1/login")
                .country("US")
                .device("mobile")
                .browser("Chrome")
                .os("Android")
                .httpMethod("POST")
                .status("200")
                .eventTime(Instant.now())
                .detectedAt(Instant.now())
                .nextActions(List.of())
                .build();

        assertThat(alert.getSchemaVersion()).isEqualTo("v3.6.1");
        assertThat(alert.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(alert.getRiskTier()).isEqualTo("MEDIUM");
        assertThat(alert.getRiskScale()).isEqualTo("ZERO_TO_ONE_HUNDRED");
        assertThat(alert.getFinalRiskScore()).isEqualTo(35.89);
        assertThat(alert.getModelScores()).containsKey("xgboostAnomalyScore");
        assertThat(alert.getModelScores()).containsKey("transformerRiskScore100");
        assertThat(alert.getModelContributions()).containsKey("transformer");
        assertThat(alert.getModelContributions()).containsKey("tcn");
        assertThat(alert.getTriggeredRules()).contains("API_SCRAPING_PATTERN");
        assertThat(alert.getEventAction()).isEqualTo("login");
        assertThat(alert.getAnomalyTier()).isEqualTo("SESSION_RUNTIME");
    }

    @Test
    void alertPayloadSerializesToJsonWithoutError() throws Exception {
        AnomalyAlert alert = AnomalyAlert.builder()
                .schemaVersion("v3.6.1")
                .recordId("evt-1")
                .insuredId("insured-1")
                .sessionId("sess-1")
                .eventId("evt-1")
                .anomalyTier("SESSION_RUNTIME")
                .anomalyType("unknown_suspicious_behavior")
                .anomalyScore(50.0)
                .anomalyProbability(0.5)
                .anomalyFlag(true)
                .riskScore(50.0)
                .riskLevel("MEDIUM")
                .riskTier("MEDIUM")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .finalRiskScore(50.0)
                .aiRiskScore(80.0)
                .ruleRiskScore(30.0)
                .modelScores(Map.of("xgboostAnomalyScore", 0.5, "xgboostAnomalyScore100", 50.0))
                .modelContributions(Map.of("xgboost", 15.0))
                .triggeredRules(List.of("TEST_RULE"))
                .churn(Map.of("probability", 0.5))
                .persona(Map.of("enabled", false))
                .eventAction("test")
                .eventTime(Instant.now())
                .detectedAt(Instant.now())
                .nextActions(List.of())
                .build();

        String json = objectMapper.writeValueAsString(alert);
        assertThat(json).isNotNull();
        assertThat(json).contains("schemaVersion");
        assertThat(json).contains("riskLevel");
        assertThat(json).contains("riskTier");
        assertThat(json).contains("riskScale");
        assertThat(json).contains("finalRiskScore");
        assertThat(json).contains("modelScores");
        assertThat(json).contains("modelContributions");
        assertThat(json).contains("triggeredRules");
    }

    @Test
    void alertPayloadDeserializesCorrectly() throws Exception {
        String json = """
                {
                    "schemaVersion": "v3.6.1",
                    "recordId": "evt-1",
                    "insuredId": "insured-1",
                    "sessionId": "sess-1",
                    "eventId": "evt-1",
                    "anomalyTier": "SESSION_RUNTIME",
                    "anomalyType": "unknown_suspicious_behavior",
                    "anomalyScore": 35.89,
                    "anomalyProbability": 0.3589,
                    "anomalyFlag": true,
                    "riskScore": 35.89,
                    "riskLevel": "MEDIUM",
                    "riskTier": "MEDIUM",
                    "riskScale": "ZERO_TO_ONE_HUNDRED",
                    "finalRiskScore": 35.89,
                    "aiRiskScore": 99.59,
                    "ruleRiskScore": 35.0,
                    "modelScores": {
                        "xgboostAnomalyScore": 0.0,
                        "xgboostAnomalyScore100": 0.22,
                        "transformerRiskScore100": 99.59,
                        "tcnRiskScore100": null,
                        "ruleRiskScore": 35.0
                    },
                    "modelContributions": {
                        "xgboost": 0.066,
                        "lightgbm": 0.696,
                        "transformer": 19.919,
                        "tcn": 0.0,
                        "rules": 5.25
                    },
                    "triggeredRules": ["API_SCRAPING_PATTERN"],
                    "eventAction": "login",
                    "eventTime": "2026-06-16T20:00:00Z",
                    "detectedAt": "2026-06-16T20:00:01Z"
                }
                """;

        AnomalyAlert alert = objectMapper.readValue(json, AnomalyAlert.class);

        assertThat(alert.getSchemaVersion()).isEqualTo("v3.6.1");
        assertThat(alert.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(alert.getRiskTier()).isEqualTo("MEDIUM");
        assertThat(alert.getRiskScale()).isEqualTo("ZERO_TO_ONE_HUNDRED");
        assertThat(alert.getFinalRiskScore()).isEqualTo(35.89);
        assertThat(alert.getAnomalyFlag()).isTrue();
        assertThat(alert.getTriggeredRules()).containsExactly("API_SCRAPING_PATTERN");
        assertThat(alert.getEventAction()).isEqualTo("login");
        assertThat(alert.getModelScores()).containsEntry("xgboostAnomalyScore100", 0.22);
    }

    /* --- Test methods: session insight --- */

    @Test
    void sessionInsightHasRiskScale() {
        SessionInsight insight = SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(true)
                .anomalyScore(45.0)
                .anomalyProbability(0.45)
                .finalRiskScore(45.0)
                .riskLevel("MEDIUM")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(List.of())
                .build();

        assertThat(insight.getRiskScale()).isEqualTo("ZERO_TO_ONE_HUNDRED");
    }

    /* --- Test methods: V36LiveAlertSummary --- */

    @Test
    void v36LiveAlertSummaryRoundTrip() throws Exception {
        V36LiveAlertSummary summary = new V36LiveAlertSummary(
                "v3.6.1",
                "anom-1",
                "rec-1",
                "insured-1",
                "sess-1",
                "2026-06-25T10:00:00Z",
                "login",
                "/documents/file",
                "documents",
                "DocumentsController",
                "documents",
                "US",
                "mobile",
                "Chrome",
                "Android",
                "POST",
                "200",
                "CRITICAL",
                "CRITICAL",
                "ZERO_TO_ONE_HUNDRED",
                84.1,
                0.95,
                95.0,
                0.03,
                2.78,
                99.59,
                0.0,
                35.0,
                new LinkedHashMap<>(Map.of("xgboostAnomalyScore", 0.95)),
                new LinkedHashMap<>(Map.of("xgboost", 0.5)),
                List.of("API_SCRAPING_PATTERN"),
                "unknown_suspicious_behavior",
                0.85,
                0.5,
                "LOW",
                true,
                "redis:key:anom-1",
                "OPEN",
                Instant.parse("2026-06-25T10:00:01Z"),
                new LinkedHashMap<>(Map.of("ip", "1.2.3.4")),
                new LinkedHashMap<>(Map.of("states", List.of())),
                new LinkedHashMap<>(Map.of("features", List.of()))
        );

        String json = objectMapper.writeValueAsString(summary);
        assertThat(json).contains("anom-1");
        assertThat(json).contains("CRITICAL");
        assertThat(json).contains("84.1");

        V36LiveAlertSummary deserialized = objectMapper.readValue(json, V36LiveAlertSummary.class);
        assertThat(deserialized.getEventId()).isEqualTo("anom-1");
        assertThat(deserialized.getInsuredId()).isEqualTo("insured-1");
        assertThat(deserialized.getSessionId()).isEqualTo("sess-1");
        assertThat(deserialized.getTimestamp()).isEqualTo("2026-06-25T10:00:00Z");
        assertThat(deserialized.getRiskLevel()).isEqualTo("CRITICAL");
        assertThat(deserialized.getFinalRiskScore()).isEqualTo(84.1);
        assertThat(deserialized.getApiTemplate()).isEqualTo("/documents/file");
        assertThat(deserialized.getApiFamily()).isEqualTo("documents");
        assertThat(deserialized.getAlertStatus()).isEqualTo("OPEN");
        assertThat(deserialized.getCreatedAt()).isEqualTo(Instant.parse("2026-06-25T10:00:01Z"));
    }

    @Test
    void v36LiveAlertSummaryDeserializesJsonWithoutConstructorError() throws Exception {
        String json = """
                {
                    "schemaVersion": "v3.6.1",
                    "eventId": "anom-1",
                    "recordId": "rec-1",
                    "insuredId": "insured-1",
                    "sessionId": "sess-1",
                    "timestamp": "2026-06-25T10:00:00Z",
                    "eventAction": "login",
                    "apiTemplate": "/documents/file",
                    "apiFamily": "documents",
                    "controller": "DocumentsController",
                    "page": "documents",
                    "country": "US",
                    "device": "mobile",
                    "browser": "Chrome",
                    "os": "Android",
                    "httpMethod": "POST",
                    "status": "200",
                    "riskLevel": "CRITICAL",
                    "finalRiskScore": 84.1,
                    "triggeredRuleCodes": ["API_SCRAPING_PATTERN"],
                    "alertStatus": "OPEN",
                    "createdAt": "2026-06-25T10:00:01Z"
                }
                """;

        V36LiveAlertSummary summary = objectMapper.readValue(json, V36LiveAlertSummary.class);
        assertThat(summary.getEventId()).isEqualTo("anom-1");
        assertThat(summary.getRiskLevel()).isEqualTo("CRITICAL");
        assertThat(summary.getFinalRiskScore()).isEqualTo(84.1);
        assertThat(summary.getTimestamp()).isEqualTo("2026-06-25T10:00:00Z");
        assertThat(summary.getCreatedAt()).isEqualTo(Instant.parse("2026-06-25T10:00:01Z"));
    }

    @Test
    void v36LiveAlertSummaryTimestampPreserved() throws Exception {
        V36LiveAlertSummary summary = new V36LiveAlertSummary();
        summary.setSchemaVersion("v3.6.1");
        summary.setEventId("anom-ts-1");
        summary.setInsuredId("insured-1");
        summary.setSessionId("sess-1");
        summary.setTimestamp("2026-06-25T12:00:00Z");
        summary.setRiskLevel("HIGH");
        summary.setFinalRiskScore(50.0);
        summary.setAlertStatus("OPEN");
        summary.setCreatedAt(Instant.parse("2026-06-25T12:00:00Z"));

        String json = objectMapper.writeValueAsString(summary);
        V36LiveAlertSummary read = objectMapper.readValue(json, V36LiveAlertSummary.class);
        assertThat(read.getTimestamp()).isNotNull();
        assertThat(read.getTimestamp()).isEqualTo("2026-06-25T12:00:00Z");
    }

    /* --- Test methods: defaults --- */

    @Test
    void defaultTransformerTcnBothNullWhenNotSet() {
        SessionInsight insight = SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(false)
                .anomalyScore(10.0)
                .anomalyProbability(0.1)
                .finalRiskScore(10.0)
                .riskLevel("LOW")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(List.of())
                .build();

        assertThat(insight.getTransformerRiskScore100()).isNull();
        assertThat(insight.getTcnRiskScore100()).isNull();
    }
}
