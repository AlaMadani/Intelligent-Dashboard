package com.noveocare.dataprocessor.ai.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.noveocare.dataprocessor.config.AiLlmExplanationProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LlmEvidencePayloadService: evidence payload building, hash stability,
 * sequence tracking fields, and evidence summary generation.
 */
class LlmEvidencePayloadServiceTest {

    /* --- Fields --- */

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    private final LlmEvidencePayloadService service = new LlmEvidencePayloadService(
            new AiLlmExplanationProperties(), objectMapper);

    /* --- Test methods: evidence payload structure --- */

    @Test
    void buildsEvidencePayloadWithHashAndVersion() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setId("event-1");
        event.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        SessionSummary summary = SessionSummary.builder()
                .insuredId("insured-1")
                .sessionId("session-1")
                .totalEvents(4)
                .build();
        SessionInsight insight = SessionInsight.builder()
                .finalRiskScore(87.0)
                .riskLevel("CRITICAL")
                .anomalyType("data_exfiltration")
                .build();

        Map<String, Object> payload = service.build(summary, event, insight);

        assertThat(payload).containsEntry("schemaVersion", "v3.6.1");
        assertThat(payload).containsEntry("evidenceVersion", "1.0");
        assertThat(payload).containsKey("evidenceHash");
        assertThat((String) payload.get("evidenceHash")).matches("[a-f0-9]{64}");
        assertThat(payload).containsKey("evidenceCreatedAt");
        assertThat(payload).containsEntry("llmExplanationInDataprocessor", false);
    }

    @Test
    void evidenceHashIsStableForSamePayload() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setId("event-1");
        event.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        SessionSummary summary = SessionSummary.builder()
                .insuredId("insured-1")
                .sessionId("session-1")
                .totalEvents(4)
                .build();
        SessionInsight insight = SessionInsight.builder()
                .finalRiskScore(87.0)
                .riskLevel("CRITICAL")
                .anomalyType("data_exfiltration")
                .build();

        String hash1 = (String) service.build(summary, event, insight).get("evidenceHash");
        String hash2 = (String) service.build(summary, event, insight).get("evidenceHash");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void evidenceHashChangesWhenRiskChanges() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setId("event-1");
        event.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        SessionSummary summary = SessionSummary.builder()
                .insuredId("insured-1")
                .sessionId("session-1")
                .totalEvents(4)
                .build();
        SessionInsight insightLow = SessionInsight.builder()
                .finalRiskScore(30.0).riskLevel("LOW").anomalyType("data_exfiltration").build();
        SessionInsight insightHigh = SessionInsight.builder()
                .finalRiskScore(87.0).riskLevel("CRITICAL").anomalyType("data_exfiltration").build();

        String hashLow = (String) service.build(summary, event, insightLow).get("evidenceHash");
        String hashHigh = (String) service.build(summary, event, insightHigh).get("evidenceHash");

        assertThat(hashLow).isNotEqualTo(hashHigh);
    }

    /* --- Test methods: sequence evidence --- */

    @Test
    void includesSequenceTrackingFields() {
        SessionInsight insight = SessionInsight.builder()
                .finalRiskScore(50.0)
                .riskLevel("MEDIUM")
                .selectedSequenceModel("TRANSFORMER")
                .sequenceContextAvailable(true)
                .sequenceRunBoth(true)
                .sequenceActuallyRanModels(List.of("transformer", "tcn"))
                .transformerUsedInFusion(true)
                .tcnUsedInFusion(true)
                .transformerScore(1.5)
                .transformerRiskScore100(65.0)
                .tcnScore(2.1)
                .tcnRiskScore100(72.0)
                .build();

        Map<String, Object> payload = service.build(null, null, insight);
        Map<String, Object> seq = (Map<String, Object>) payload.get("sequenceEvidence");

        assertThat(seq).containsEntry("selectedSequenceModel", "TRANSFORMER");
        assertThat(seq).containsEntry("contextAvailable", true);
        assertThat(seq).containsEntry("sequenceRunBoth", true);
        assertThat(seq).containsEntry("sequenceActuallyRanModels", List.of("transformer", "tcn"));
        assertThat(seq).containsEntry("transformerUsedInFusion", true);
        assertThat(seq).containsEntry("tcnUsedInFusion", true);
        assertThat(seq).containsEntry("transformerSurpriseScoreRaw", 1.5);
        assertThat(seq).containsEntry("transformerRiskScore100", 65.0);
        assertThat(seq).containsEntry("tcnSurpriseScoreRaw", 2.1);
        assertThat(seq).containsEntry("tcnRiskScore100", 72.0);
    }

    @Test
    void preservesTcnNullWhenTcnDidNotRun() {
        SessionInsight insight = SessionInsight.builder()
                .finalRiskScore(50.0)
                .riskLevel("MEDIUM")
                .selectedSequenceModel("TRANSFORMER")
                .sequenceRunBoth(false)
                .sequenceActuallyRanModels(List.of("transformer"))
                .transformerUsedInFusion(true)
                .tcnUsedInFusion(false)
                .transformerScore(1.5)
                .transformerRiskScore100(65.0)
                .tcnScore(null)
                .tcnRiskScore100(null)
                .build();

        Map<String, Object> payload = service.build(null, null, insight);
        Map<String, Object> seq = (Map<String, Object>) payload.get("sequenceEvidence");

        assertThat(seq).containsEntry("sequenceRunBoth", false);
        assertThat(seq).containsEntry("sequenceActuallyRanModels", List.of("transformer"));
        assertThat(seq).containsEntry("transformerUsedInFusion", true);
        assertThat(seq).containsEntry("tcnUsedInFusion", false);
        assertThat(seq.get("transformerSurpriseScoreRaw")).isEqualTo(1.5);
        assertThat(seq.get("transformerRiskScore100")).isEqualTo(65.0);
        assertThat(seq).containsEntry("tcnSurpriseScoreRaw", null);
        assertThat(seq).containsEntry("tcnRiskScore100", null);
    }

    /* --- Test methods: evidence summary --- */

    @Test
    void includesEvidenceSummaryInPayload() {
        SessionInsight insight = SessionInsight.builder()
                .finalRiskScore(87.0)
                .riskLevel("CRITICAL")
                .anomalyType("data_exfiltration")
                .fallbackMode("FULL_HYBRID")
                .build();

        Map<String, Object> payload = service.build(null, null, insight);

        assertThat(payload).containsKey("evidenceSummary");
        assertThat((String) payload.get("evidenceSummary")).contains("risk=87.0");
        assertThat((String) payload.get("evidenceSummary")).contains("level=CRITICAL");
    }
}
