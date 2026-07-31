package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPromptBuilderV36Test {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private LlmPromptBuilderV36 builder;

    @BeforeEach
    void setUp() {
        builder = new LlmPromptBuilderV36(objectMapper);
        ReflectionTestUtils.setField(builder, "maxEvidenceSizeKb", 128);
    }

    /**
     * System Prompt Contains Analyst Instructions
     */
    @Test
    void systemPromptContainsAnalystInstructions() {
        String prompt = builder.buildSystemPrompt("security_analyst", "en", true);
        assertThat(prompt).contains("senior cybersecurity analyst");
        assertThat(prompt).contains("Do not invent facts");
        assertThat(prompt).contains("TCN did not run");
        assertThat(prompt).contains("Distinguish security risk from churn risk");
        assertThat(prompt).contains("Return one valid JSON object only");
        assertThat(prompt).contains("Do not use markdown");
        assertThat(prompt).contains("finalRiskScore is a fused");
        assertThat(prompt).contains("Distinguish model score from model contribution");
        assertThat(prompt).contains("Do not reveal reasoning");
        assertThat(prompt).contains("under 700 words");
    }

    /**
     * System Prompt Explicitly Warns Against Inventing Evidence
     */
    @Test
    void systemPromptExplicitlyWarnsAgainstInventingEvidence() {
        String prompt = builder.buildSystemPrompt("security_analyst", "en", true);
        assertThat(prompt).contains("Do not invent facts");
        assertThat(prompt).contains("TCN did not run");
        assertThat(prompt).contains("do not imply credential theft");
    }

    /**
     * Build Prompt Contains Compact Schema And Evidence
     */
    @Test
    void buildPromptContainsCompactSchemaAndEvidence() {
        ObjectNode risk = objectMapper.createObjectNode();
        risk.put("riskLevel", "HIGH");
        risk.put("finalRiskScore", 66.10287612208538);
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("eventId", "evt-66");
        evidence.set("risk", risk);

        String prompt = builder.buildPrompt(evidence, "security_analyst", "en", true);

        assertThat(prompt).contains("modelAnalysis");
        assertThat(prompt).contains("fusion");
        assertThat(prompt).contains("sequence");
        assertThat(prompt).contains("rules");
        assertThat(prompt).contains("contextOnly");
        assertThat(prompt).contains("keyEvidenceBullets");
        assertThat(prompt).contains("limitations");
        assertThat(prompt).contains("HIGH");
        assertThat(prompt).contains("66.10287612208538");
    }

    /**
     * Compact Evidence Includes Identity And Risk
     */
    @Test
    void compactEvidenceIncludesIdentityAndRisk() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "eventId": "evt-fixture",
                  "insuredId": "ins-123",
                  "sessionId": "sess-456",
                  "risk": { "riskLevel": "HIGH", "finalRiskScore": 66.10287612208538 }
                }
                """);

        String compact = builder.buildCompactEvidence(evidence);

        assertThat(compact).contains("evt-fixture");
        assertThat(compact).contains("HIGH");
        assertThat(compact).contains("66.10287612208538");
    }

    /**
     * Compact Evidence With Full Fixture
     */
    @Test
    void compactEvidenceWithFullFixture() throws Exception {
        String fixture = """
                {
                  "eventId": "anom-000000000036",
                  "risk": { "riskLevel": "HIGH", "finalRiskScore": 66.10287612208538, "fallbackMode": "PARTIAL_HYBRID" },
                  "eventMetadata": {
                    "eventAction": "Exporter remboursement",
                    "apiTemplate": "/insured/refund-resume",
                    "apiFamily": "insured",
                    "httpMethod": "POST",
                    "status": "SUCCESS",
                    "country": "FR",
                    "responseDataSizeBytes": 6312415
                  },
                  "sessionMetadata": {
                    "totalEvents": 16,
                    "totalKOs": 0,
                    "maxDownloadsIn2Minutes": 7,
                    "pingPongCount": 2,
                    "deviceChanged": 0,
                    "ipChanged": 0
                  },
                  "modelScores": {
                    "xgboostAnomalyScore100": 45.856777837292384,
                    "lightgbmAlertScore100": 98.00636569225148,
                    "catboostAnomalyScore100": 96.71069839471241,
                    "oneClassSvmNoveltyScore100": 99.99666913714314,
                    "transformerRiskScore100": 57.322506079123755,
                    "tcnRiskScore100": null,
                    "ruleRiskScore": 65,
                    "tcnUsedInFusion": false
                  },
                  "modelContributions": {
                    "xgboost": 15.591304464679409,
                    "lightgbm": 27.768470279471252,
                    "transformer": 12.99310137793472,
                    "tcn": 0,
                    "rules": 9.75,
                    "aggregationBoost": 0
                  },
                  "triggeredRules": [
                    { "ruleCode": "RAPID_FIRE_EVENTS", "severity": "MEDIUM", "scoreContribution": 30 },
                    { "ruleCode": "API_SCRAPING_PATTERN", "severity": "MEDIUM", "scoreContribution": 35 }
                  ],
                  "sequenceEvidence": {
                    "selectedSequenceModel": "transformer",
                    "sequenceActuallyRanModels": ["transformer"],
                    "transformerUsedInFusion": true,
                    "tcnUsedInFusion": false,
                    "transformerRiskScore100": 57.322506079123755,
                    "tcnRiskScore100": null
                  },
                  "tabularEvidence": {
                    "availableModels": ["catboost", "lightgbm", "oneclasssvm", "xgboost"]
                  },
                  "anomalyTypeAttribution": {
                    "anomalyType": "unknown_suspicious_behavior",
                    "confidence": 0.55,
                    "source": "hybrid_rules_models"
                  },
                  "churnContext": { "churnProbability": 0.845, "churnRiskLevel": "HIGH" }
                }
                """;
        JsonNode evidence = objectMapper.readTree(fixture);
        String compact = builder.buildCompactEvidence(evidence);

        assertThat(compact).contains("66.10287612208538");
        assertThat(compact).contains("HIGH");
        assertThat(compact).contains("Exporter remboursement");
        assertThat(compact).contains("/insured/refund-resume");
        assertThat(compact).contains("6312415");
        assertThat(compact).contains("totalEvents");
        assertThat(compact).contains("maxDownloadsIn2Minutes");
        assertThat(compact).contains("pingPongCount");
        assertThat(compact).contains("RAPID_FIRE_EVENTS");
        assertThat(compact).contains("API_SCRAPING_PATTERN");
        assertThat(compact).contains("98.00636569225148");
        assertThat(compact).contains("transformerRiskScore100");
        assertThat(compact).contains("57.322506079123755");
        assertThat(compact).contains("tcnUsedInFusion");
        assertThat(compact).contains("churnContext");
    }

    /**
     * Derived Facts Includes Top Contributors And Tcn Status
     */
    @Test
    void derivedFactsIncludesTopContributorsAndTcnStatus() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "modelContributions": {
                    "xgboost": 15.59,
                    "lightgbm": 27.77,
                    "transformer": 12.99,
                    "rules": 9.75,
                    "aggregationBoost": 0
                  },
                  "modelScores": { "tcnRiskScore100": null, "tcnUsedInFusion": false }
                }
                """);

        String facts = builder.buildDerivedFacts(evidence);

        assertThat(facts).contains("topContributors");
        assertThat(facts).contains("lightgbm");
        assertThat(facts).contains("27.77");
        assertThat(facts).contains("not_run");
    }

    /**
     * Derived Facts Labels Confidence Moderate
     */
    @Test
    void derivedFactsLabelsConfidenceModerate() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "anomalyTypeAttribution": { "confidence": 0.55 }
                }
                """);

        String facts = builder.buildDerivedFacts(evidence);
        assertThat(facts).contains("moderate");
    }

    /**
     * Compact Evidence Limits Triggered Rules To Ten
     */
    @Test
    void compactEvidenceLimitsTriggeredRulesToTen() throws Exception {
        ObjectNode risk = objectMapper.createObjectNode();
        risk.put("riskLevel", "HIGH");

        ArrayNode topRules = objectMapper.createArrayNode();
        for (int i = 1; i <= 12; i++) {
            ObjectNode rule = objectMapper.createObjectNode();
            rule.put("ruleCode", String.format("RULE_%02d", i));
            topRules.add(rule);
        }

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("eventId", "evt-rules");
        evidence.set("risk", risk);
        evidence.set("triggeredRules", topRules);

        String compact = builder.buildCompactEvidence(evidence);

        assertThat(compact).contains("RULE_01");
        assertThat(compact).contains("RULE_10");
        assertThat(compact).doesNotContain("RULE_11");
    }

    /**
     * Compact Evidence Truncates Long Signatures
     */
    @Test
    void compactEvidenceTruncatesLongSignatures() throws Exception {
        String longSig = "A".repeat(500);

        ObjectNode sessionMetadata = objectMapper.createObjectNode();
        sessionMetadata.put("totalEvents", 10);
        sessionMetadata.put("actionSequenceSignature", longSig);
        sessionMetadata.put("routeSequenceSignature", longSig);

        ObjectNode risk = objectMapper.createObjectNode();
        risk.put("riskLevel", "LOW");

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("eventId", "evt-long");
        evidence.set("risk", risk);
        evidence.set("sessionMetadata", sessionMetadata);

        String compact = builder.buildCompactEvidence(evidence);

        assertThat(compact).contains("AAA...");
        assertThat(compact).doesNotContain(longSig);
    }

    /**
     * Non Minimal Prompt Contains Compact Schema And Evidence
     */
    @Test
    void nonMinimalPromptContainsCompactSchemaAndEvidence() {
        ObjectNode risk = objectMapper.createObjectNode();
        risk.put("riskLevel", "HIGH");
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("eventId", "evt-test");
        evidence.set("risk", risk);

        String system = builder.buildSystemPrompt("security_analyst", "en", true);
        String user = builder.buildPrompt(evidence, "security_analyst", "en", true);

        assertThat(system).contains("senior cybersecurity analyst");
        assertThat(user).contains("Explain this alert");
        assertThat(user).contains("modelAnalysis");
        assertThat(user).contains("fusion");
        assertThat(user).contains("sequence");
        assertThat(user).contains("rules");
        assertThat(user).contains("contextOnly");
        assertThat(user).contains("keyEvidenceBullets");
        assertThat(user).contains("limitations");
        assertThat(user).contains("Evidence:");
        assertThat(user).contains("\"derivedFacts\"");
    }
}
