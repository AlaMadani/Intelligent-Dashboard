package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.sequence.SequenceScoreResult;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyResult;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AnomalyTypeAttributionServiceV36 {

    public AnomalyTypeAttributionResult attribute(TabularAnomalyResult tabular,
                                                  SequenceScoreResult transformerScore,
                                                  SequenceScoreResult tcnScore,
                                                  RuleRiskResult rules,
                                                  AuditTrailEvent event,
                                                  SessionSummary summary,
                                                  RiskFusionResult fusion) {
        List<String> candidates = new ArrayList<>();
        Set<String> ruleCodes = Set.copyOf(rules == null ? List.of() : rules.getTriggeredRules());
        double xgb = score(tabular == null ? null : tabular.getXgboostAnomalyScore100());
        double cat = score(tabular == null ? null : tabular.getCatboostAnomalyScore100());
        double svm = score(tabular == null ? null : tabular.getOneClassSvmNoveltyScore100());
        double seq = Math.max(score(transformerScore == null ? null : transformerScore.getAiRiskScore()),
                score(tcnScore == null ? null : tcnScore.getAiRiskScore()));

        String type = "unknown_suspicious_behavior";
        double confidence = 0.45;
        if (xgb >= 60.0 && ruleCodes.contains("API_SCRAPING_PATTERN") && repeatedEndpointPattern(summary)) {
            type = "api_scraping";
            confidence = 0.82;
        } else if ((ruleCodes.contains("STATUS_CODE_BURST") || defaultInt(summary == null ? null : summary.getTotalKOs()) >= 3)
                && loginRelated(event) && seq >= 50.0) {
            type = "credential_stuffing";
            confidence = 0.80;
        } else if ((ruleCodes.contains("LARGE_DOWNLOAD") || ruleCodes.contains("DATA_EXTRACTION_PATTERN"))
                && sensitiveApi(event) && (xgb >= 55.0 || cat >= 55.0)) {
            type = "data_exfiltration";
            confidence = 0.82;
        } else if ((ruleCodes.contains("OFF_HOURS_ACCESS") || ruleCodes.contains("SENSITIVE_API_OFF_HOURS"))
                && (ruleCodes.contains("COUNTRY_SWITCH") || ruleCodes.contains("DEVICE_SWITCH") || svm >= 55.0)) {
            type = "off_hours_compromise";
            confidence = 0.78;
        } else if ((ruleCodes.contains("COUNTRY_SWITCH") || ruleCodes.contains("DEVICE_SWITCH"))
                && seq >= 55.0) {
            type = "session_hijacking";
            confidence = 0.76;
        } else if (seq >= 60.0 && (rules == null || rules.getRuleRiskScore() < 35.0)) {
            type = "behavioral_sequence_anomaly";
            confidence = 0.68;
        } else if (fusion != null && fusion.getFinalRiskScore() >= 60.0) {
            type = "unknown_suspicious_behavior";
            confidence = 0.55;
        }
        candidates.add(type);
        candidates.add("unknown_suspicious_behavior");

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("xgboostScore100", xgb);
        evidence.put("catboostScore100", cat);
        evidence.put("oneClassSvmScore100", svm);
        evidence.put("sequenceRisk100", seq);
        evidence.put("triggeredRules", rules == null ? List.of() : rules.getTriggeredRules());
        evidence.put("apiTemplate", event == null ? null : event.getApiTemplate());
        evidence.put("apiFamily", event == null ? null : event.getApiFamily());
        evidence.put("finalRiskScore", fusion == null ? null : fusion.getFinalRiskScore());
        return AnomalyTypeAttributionResult.builder()
                .anomalyType(type)
                .anomalyTypeSource("hybrid_rules_models")
                .anomalyTypeConfidence(confidence)
                .candidateTypes(candidates.stream().distinct().toList())
                .anomalyTypeEvidenceJson(evidence)
                .build();
    }

    private boolean repeatedEndpointPattern(SessionSummary summary) {
        return summary != null && defaultInt(summary.getPingPongCount()) >= 2;
    }

    private boolean loginRelated(AuditTrailEvent event) {
        String value = joined(event == null ? null : event.getApiTemplate(), event == null ? null : event.getActionValue(),
                event == null ? null : event.getFrontendActionName(), event == null ? null : event.getActionType());
        return value.contains("login") || value.contains("connexion") || value.contains("auth");
    }

    private boolean sensitiveApi(AuditTrailEvent event) {
        String value = joined(event == null ? null : event.getApiTemplate(), event == null ? null : event.getApiFamily(),
                event == null ? null : event.getController(), event == null ? null : event.getActionValue());
        return value.contains("document") || value.contains("download") || value.contains("wallet")
                || value.contains("bank") || value.contains("insured") || value.contains("file");
    }

    private String joined(String... values) {
        StringBuilder builder = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    builder.append(' ').append(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        return builder.toString();
    }

    private double score(Double value) {
        return value == null ? 0.0 : value;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
