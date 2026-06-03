package com.noveocare.dataprocessor.ai.explanation;

import com.noveocare.dataprocessor.config.AiLlmExplanationProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmEvidencePayloadServiceTest {

    @Test
    void buildsEvidencePayloadWithoutLlmOutput() {
        LlmEvidencePayloadService service = new LlmEvidencePayloadService(new AiLlmExplanationProperties());
        AuditTrailEvent event = new AuditTrailEvent();
        event.setId("event-1");
        event.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        event.setApiTemplate("/api/documents");
        SessionSummary summary = SessionSummary.builder()
                .insuredId("insured-1")
                .sessionId("session-1")
                .totalEvents(4)
                .build();
        SessionInsight insight = SessionInsight.builder()
                .finalRiskScore(87.0)
                .riskLevel("CRITICAL")
                .fallbackMode("FULL_HYBRID")
                .anomalyType("data_exfiltration")
                .anomalyTypeConfidence(0.82)
                .anomalyTypeSource("hybrid_rules_models")
                .triggeredRules(List.of("LARGE_DOWNLOAD"))
                .modelScores(Map.of("xgboostAnomalyScore", 0.91))
                .modelContributions(Map.of("xgboost", 27.3))
                .personaLabel("persona_disabled")
                .personaSource("disabled_v3_6_refactor")
                .build();

        Map<String, Object> payload = service.build(summary, event, insight);

        assertThat(payload).containsEntry("schemaVersion", "v3.6.1");
        assertThat(payload).containsEntry("llmExplanationInDataprocessor", false);
        assertThat(payload).containsKey("llmInstruction");
        assertThat(payload).doesNotContainKey("llmResponse");
    }
}
