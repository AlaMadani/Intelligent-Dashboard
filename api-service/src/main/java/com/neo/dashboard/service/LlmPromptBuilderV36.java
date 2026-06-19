package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LlmPromptBuilderV36 {

    private static final double MODERATE_CONFIDENCE_UPPER = 0.75;

    private final ObjectMapper objectMapper;

    @Value("${app.v36.explanations.max-evidence-size-kb:128}")
    private int maxEvidenceSizeKb;

    public String buildSystemPrompt(String style, String language, boolean includeRecommendedActions) {
        return """
                You are a senior cybersecurity analyst assistant.
                
                Use only the evidence provided.
                Do not invent facts.
                Do not reveal reasoning or planning.
                Do not write analysis before the JSON.
                Do not use markdown.
                Return one valid JSON object only.
                The first non-whitespace character must be { and the last non-whitespace character must be }.
                Keep the entire response under 700 words.
                Each bullet must be one sentence.
                If evidence is missing, say unavailable in limitations.
                
                Important rules:
                - finalRiskScore is a fused 0-100 score, not a calibrated probability.
                - Distinguish model score from model contribution.
                - Distinguish security risk from churn risk.
                - CatBoost, OneClassSVM, churn, and forecast may be context-only if evidence indicates they are not in fusion.
                - If TCN did not run, explicitly say TCN did not run and contributed 0.
                - If no device/IP change and totalKOs is 0, do not imply credential theft.
                - If anomaly type confidence is moderate, mention uncertainty.
                """;
    }

    public String buildPrompt(JsonNode evidence, String style, String language, boolean includeRecommendedActions) {
        String compactEvidence = buildCompactEvidence(evidence);

        String outputSchema = """
                {
                  "summary": "4-6 sentence analyst explanation",
                  "keyEvidenceBullets": [
                    "specific evidence bullet 1",
                    "specific evidence bullet 2",
                    "specific evidence bullet 3",
                    "specific evidence bullet 4",
                    "specific evidence bullet 5",
                    "specific evidence bullet 6"
                  ],
                  "modelAnalysis": {
                    "fusion": "explain final risk score and top contributors",
                    "sequence": "explain transformer/TCN behavior",
                    "rules": "explain deterministic triggered rules",
                    "contextOnly": "explain CatBoost, OneClassSVM, churn, forecast if relevant"
                  },
                  "possibleInterpretation": "careful interpretation without overclaiming",
                  "recommendedActions": [
                    "action 1",
                    "action 2",
                    "action 3",
                    "action 4"
                  ],
                  "limitations": [
                    "caveat 1",
                    "caveat 2"
                  ],
                  "disclaimer": "Generated from model and rule evidence. Analyst aid only."
                }""";

        String userPrompt = """
                Explain this alert using only the evidence below.
                
                Return valid JSON matching this schema exactly:
                %s
                
                Evidence:
                %s
                """.formatted(outputSchema, compactEvidence);

        String eventId = extractEventId(evidence);
        int systemChars = buildSystemPrompt(style, language, includeRecommendedActions).length();
        log.info("LLM_PROMPT_BUILT eventId={} systemChars={} userChars={} evidenceHash=N/A compacted=true",
                eventId, systemChars, userPrompt.length());

        return userPrompt;
    }

    public String buildRetryPrompt(JsonNode evidence, String style, String language, boolean includeRecommendedActions) {
        String compactEvidence = buildCompactEvidence(evidence);

        String retrySchema = """
                {
                  "summary": "3-5 sentences",
                  "keyEvidenceBullets": ["5 bullets max"],
                  "possibleInterpretation": "1 paragraph",
                  "recommendedActions": ["3 actions"],
                  "limitations": ["2 caveats"],
                  "disclaimer": "Generated from model and rule evidence. Analyst aid only."
                }""";

        String retryPrompt = """
                Previous response was too long and was truncated. Return compact JSON only under 450 words.
                
                Return valid JSON matching this schema exactly:
                %s
                
                Evidence:
                %s
                """.formatted(retrySchema, compactEvidence);

        String eventId = extractEventId(evidence);
        log.info("LLM_PROMPT_RETRY eventId={} retryChars={} compacted=true",
                eventId, retryPrompt.length());

        return retryPrompt;
    }

    public String buildMarkdownPrompt(JsonNode evidence, String style, String language, boolean includeRecommendedActions) {
        return buildPrompt(evidence, style, language, includeRecommendedActions);
    }

    String buildCompactEvidence(JsonNode evidence) {
        Map<String, Object> compact = new LinkedHashMap<>();

        // Identity
        compact.put("identity", Map.of(
                "eventId", textOrDefault(evidence, "eventId", "?"),
                "insuredId", textOrDefault(evidence, "insuredId", "?"),
                "sessionId", textOrDefault(evidence, "sessionId", "?")
        ));

        // Risk
        copySectionCompact(compact, evidence, "risk", "riskLevel", "finalRiskScore", "fallbackMode");

        // Event
        Map<String, Object> event = new LinkedHashMap<>();
        JsonNode em = evidence.path("eventMetadata");
        copyField(event, em, "eventAction");
        copyField(event, em, "apiTemplate");
        copyField(event, em, "apiFamily");
        copyField(event, em, "page");
        copyField(event, em, "httpMethod");
        copyField(event, em, "status");
        copyField(event, em, "country");
        copyField(event, em, "device");
        copyField(event, em, "browser");
        copyField(event, em, "os");
        copyField(event, em, "requestDataSizeBytes");
        copyField(event, em, "responseDataSizeBytes");
        if (!event.isEmpty()) compact.put("event", event);

        // Session
        Map<String, Object> session = new LinkedHashMap<>();
        JsonNode sm = evidence.path("sessionMetadata");
        copyField(session, sm, "totalEvents");
        copyField(session, sm, "totalKOs");
        copyField(session, sm, "maxDownloadsIn2Minutes");
        copyField(session, sm, "pingPongCount");
        copyField(session, sm, "deviceChanged");
        copyField(session, sm, "ipChanged");
        String actionSeq = textAt(sm, "actionSequenceSignature");
        if (actionSeq != null) session.put("actionSequenceSignature", truncateString(actionSeq, 200));
        String routeSeq = textAt(sm, "routeSequenceSignature");
        if (routeSeq != null) session.put("routeSequenceSignature", truncateString(routeSeq, 200));
        if (!session.isEmpty()) compact.put("session", session);

        // Fusion
        Map<String, Object> fusion = buildFusionSection(evidence);
        if (!fusion.isEmpty()) compact.put("fusion", fusion);

        // Rules
        List<Map<String, Object>> rulesList = buildRulesList(evidence);
        if (!rulesList.isEmpty()) compact.put("rules", rulesList);

        // Sequence
        Map<String, Object> seq = new LinkedHashMap<>();
        JsonNode se = evidence.path("sequenceEvidence");
        copyField(seq, se, "selectedSequenceModel");
        copyField(seq, se, "sequenceRunBoth");
        copyField(seq, se, "sequenceActuallyRanModels");
        copyField(seq, se, "transformerUsedInFusion");
        copyField(seq, se, "tcnUsedInFusion");
        copyField(seq, se, "transformerSurpriseScoreRaw");
        copyField(seq, se, "transformerRiskScore100");
        copyField(seq, se, "tcnSurpriseScoreRaw");
        copyField(seq, se, "tcnRiskScore100");
        JsonNode topSurprise = se.path("topSequenceSurpriseFields");
        if (topSurprise.isArray() && !topSurprise.isEmpty()) {
            List<String> limited = new ArrayList<>();
            for (int i = 0; i < Math.min(topSurprise.size(), 5); i++) {
                limited.add(topSurprise.get(i).asText());
            }
            seq.put("topSurpriseFields", limited);
        }
        if (!seq.isEmpty()) compact.put("sequence", seq);

        // Tabular
        Map<String, Object> tabular = new LinkedHashMap<>();
        JsonNode te = evidence.path("tabularEvidence");
        copyField(tabular, te, "availableModels");
        copyField(tabular, te, "unavailableModels");
        if (!tabular.isEmpty()) compact.put("tabular", tabular);

        // Attribution
        copySectionCompact(compact, evidence, "anomalyTypeAttribution", "anomalyType", "confidence", "source");

        // Churn context
        Map<String, Object> churn = new LinkedHashMap<>();
        JsonNode cc = evidence.path("churnContext");
        copyField(churn, cc, "churnProbability");
        copyField(churn, cc, "churnRiskLevel");
        if (!churn.isEmpty()) compact.put("churnContext", churn);

        // Derived facts (pre-digested for the model)
        Map<String, Object> derivedFacts = buildDerivedFactsMap(evidence);
        if (!derivedFacts.isEmpty()) {
            compact.put("derivedFacts", derivedFacts);
        }

        return serializeCompact(compact);
    }

    Map<String, Object> buildDerivedFactsMap(JsonNode evidence) {
        Map<String, Object> facts = new LinkedHashMap<>();

        // Top contributors sorted by contribution descending
        JsonNode mc = evidence.path("modelContributions");
        List<Map<String, Object>> topContribs = new ArrayList<>();
        if (mc.isObject()) {
            mc.fields().forEachRemaining(entry -> {
                JsonNode v = entry.getValue();
                if (v.isNumber() && v.decimalValue().compareTo(BigDecimal.ZERO) > 0) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getKey());
                    item.put("contribution", v.decimalValue());
                    topContribs.add(item);
                }
            });
            topContribs.sort((a, b) -> ((BigDecimal) b.get("contribution")).compareTo((BigDecimal) a.get("contribution")));
        }
        facts.put("topContributors", topContribs);

        // TCN status
        JsonNode ms = evidence.path("modelScores");
        JsonNode tcnScore = ms.path("tcnRiskScore100");
        JsonNode tcnFusion = ms.path("tcnUsedInFusion");
        boolean tcnRan = !tcnScore.isNull() && !tcnScore.isMissingNode();
        boolean tcnUsed = tcnFusion.isBoolean() && tcnFusion.asBoolean();
        if (!tcnRan) {
            facts.put("tcnStatus", "not_run");
        } else if (!tcnUsed) {
            facts.put("tcnStatus", "ran_but_not_used");
        } else {
            facts.put("tcnStatus", "ran_and_used");
        }

        // Large response
        JsonNode respBytes = evidence.path("eventMetadata").path("responseDataSizeBytes");
        if (respBytes.isNumber() && respBytes.asLong() > 1_000_000) {
            facts.put("largeResponseBytes", respBytes.asLong());
        }

        // Device/IP changes
        JsonNode sm = evidence.path("sessionMetadata");
        facts.put("hasDeviceChange", sm.path("deviceChanged").asInt(0) > 0);
        facts.put("hasIpChange", sm.path("ipChanged").asInt(0) > 0);

        // Failures
        facts.put("hasFailures", sm.path("totalKOs").asInt(0) > 0);

        // Rules count
        int rulesCount = countTriggeredRules(evidence);
        facts.put("rulesTriggeredCount", rulesCount);

        // Confidence label
        JsonNode confidence = evidence.path("anomalyTypeAttribution").path("confidence");
        if (confidence.isNumber()) {
            double conf = confidence.asDouble();
            String label;
            if (conf < 0.4) label = "low";
            else if (conf < MODERATE_CONFIDENCE_UPPER) label = "moderate";
            else label = "high";
            facts.put("anomalyAttributionConfidenceLabel", label);
        }

        // Context-only models
        List<String> contextOnly = new ArrayList<>();
        if (isModelHighButMaybeContextOnly(evidence, "catboostAnomalyScore100")) {
            contextOnly.add("catboost");
        }
        if (isModelHighButMaybeContextOnly(evidence, "oneClassSvmNoveltyScore100")) {
            contextOnly.add("oneClassSvm");
        }
        if (!contextOnly.isEmpty()) {
            facts.put("contextOnlyModels", contextOnly);
        }

        // Dominant behavior description
        String dominantBehavior = buildDominantBehavior(evidence);
        if (dominantBehavior != null) {
            facts.put("dominantBehavior", dominantBehavior);
        }

        return facts;
    }

    private String buildDominantBehavior(JsonNode evidence) {
        String eventAction = textAt(evidence, "eventMetadata/eventAction");
        String apiTemplate = textAt(evidence, "eventMetadata/apiTemplate");
        JsonNode sm = evidence.path("sessionMetadata");
        int totalEvents = sm.path("totalEvents").asInt(0);
        int downloads = sm.path("maxDownloadsIn2Minutes").asInt(0);
        int pingPong = sm.path("pingPongCount").asInt(0);

        if (eventAction == null && apiTemplate == null) return null;
        StringBuilder sb = new StringBuilder();
        if (eventAction != null) sb.append(eventAction);
        if (apiTemplate != null) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(apiTemplate);
        }
        if (totalEvents > 0 || downloads > 0 || pingPong > 0) {
            sb.append(" (");
            List<String> parts = new ArrayList<>();
            if (totalEvents > 0) parts.add(totalEvents + " events");
            if (downloads > 0) parts.add(downloads + " downloads");
            if (pingPong > 0) parts.add("pingPong=" + pingPong);
            sb.append(String.join(", ", parts));
            sb.append(")");
        }
        return sb.toString();
    }

    String buildDerivedFacts(JsonNode evidence) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(buildDerivedFactsMap(evidence));
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> buildFusionSection(JsonNode evidence) {
        Map<String, Object> fusion = new LinkedHashMap<>();

        // Scores
        Map<String, Object> scores = new LinkedHashMap<>();
        JsonNode ms = evidence.path("modelScores");
        copyField(scores, ms, "xgboostAnomalyScore100");
        copyField(scores, ms, "lightgbmAlertScore100");
        copyField(scores, ms, "catboostAnomalyScore100");
        copyField(scores, ms, "oneClassSvmNoveltyScore100");
        copyField(scores, ms, "transformerRiskScore100");
        copyField(scores, ms, "tcnRiskScore100");
        copyField(scores, ms, "ruleRiskScore");
        copyField(scores, ms, "aggregationBoost");
        if (!scores.isEmpty()) fusion.put("scores", scores);

        // Contributions
        Map<String, Object> contribs = new LinkedHashMap<>();
        JsonNode mc = evidence.path("modelContributions");
        copyField(contribs, mc, "xgboost");
        copyField(contribs, mc, "lightgbm");
        copyField(contribs, mc, "transformer");
        copyField(contribs, mc, "tcn");
        copyField(contribs, mc, "rules");
        copyField(contribs, mc, "businessContext");
        copyField(contribs, mc, "aggregationBoost");
        if (!contribs.isEmpty()) fusion.put("contributions", contribs);

        // Used in fusion
        Map<String, Object> usedInFusion = new LinkedHashMap<>();
        usedInFusion.put("xgboost", true);
        usedInFusion.put("lightgbm", true);
        usedInFusion.put("transformer", ms.path("transformerUsedInFusion").asBoolean(false));
        usedInFusion.put("tcn", ms.path("tcnUsedInFusion").asBoolean(false));
        usedInFusion.put("rules", true);
        fusion.put("usedInFusion", usedInFusion);

        // Context-only models
        List<String> contextOnly = new ArrayList<>();
        JsonNode te = evidence.path("tabularEvidence");
        if (te.isObject()) {
            JsonNode available = te.path("availableModels");
            if (available.isArray()) {
                for (JsonNode m : available) {
                    String name = m.asText();
                    if ("catboost".equals(name) || "oneclasssvm".equals(name)) {
                        contextOnly.add(name.equals("oneclasssvm") ? "oneClassSvm" : name);
                    }
                }
            }
        }
        JsonNode churn = evidence.path("churnContext");
        if (churn.isObject() && churn.has("churnProbability")) {
            contextOnly.add("churn");
        }
        if (!contextOnly.isEmpty()) {
            fusion.put("contextOnlyModels", contextOnly);
        }

        return fusion;
    }

    private List<Map<String, Object>> buildRulesList(JsonNode evidence) {
        // Check top-level triggeredRules first (array of objects), then ruleEvidence.triggeredRules
        JsonNode topRules = evidence.path("triggeredRules");
        if (topRules.isArray() && !topRules.isEmpty()) {
            List<Map<String, Object>> result = new ArrayList<>();
            int limit = Math.min(topRules.size(), 10);
            for (int i = 0; i < limit; i++) {
                JsonNode rule = topRules.get(i);
                if (rule.isObject()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    copyField(item, rule, "ruleCode");
                    copyField(item, rule, "severity");
                    copyField(item, rule, "scoreContribution");
                    copyField(item, rule, "humanMessage");
                    if (!item.isEmpty()) result.add(item);
                }
            }
            if (topRules.size() > 10) {
                Map<String, Object> more = new LinkedHashMap<>();
                more.put("moreRulesCount", topRules.size() - 10);
                result.add(more);
            }
            return result;
        }

        // Fallback: ruleEvidence.triggeredRules as strings
        JsonNode ruleEv = evidence.path("ruleEvidence");
        JsonNode triggered = ruleEv.path("triggeredRules");
        if (triggered.isArray() && !triggered.isEmpty()) {
            List<Map<String, Object>> result = new ArrayList<>();
            int limit = Math.min(triggered.size(), 10);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleCode", triggered.get(i).asText());
                result.add(item);
            }
            return result;
        }
        return List.of();
    }

    private boolean isModelHighButMaybeContextOnly(JsonNode evidence, String field) {
        JsonNode val = evidence.path("modelScores").path(field);
        if (val.isNumber() && val.asDouble() > 70) {
            // Check if available in tabular evidence but not necessarily in fusion
            return true;
        }
        return false;
    }

    private int countTriggeredRules(JsonNode evidence) {
        JsonNode topRules = evidence.path("triggeredRules");
        if (topRules.isArray()) return topRules.size();
        JsonNode ruleEv = evidence.path("ruleEvidence").path("triggeredRules");
        if (ruleEv.isArray()) return ruleEv.size();
        return 0;
    }

    private String serializeCompact(Map<String, Object> compact) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(compact);
            int maxChars = Math.max(maxEvidenceSizeKb, 1) * 1024;
            if (json.length() <= maxChars) return json;
            return json.substring(0, maxChars) + "\n...<evidence truncated by api-service limit>";
        } catch (Exception e) {
            log.warn("LLM_PROMPT_EVIDENCE_SERIALIZE_ERROR message=\"{}\"", e.getMessage());
            return "{}";
        }
    }

    private void copySectionCompact(Map<String, Object> target, JsonNode source, String sectionName, String... fieldNames) {
        JsonNode section = source.path(sectionName);
        if (!section.isObject()) return;
        Map<String, Object> sectionMap = new LinkedHashMap<>();
        for (String fieldName : fieldNames) {
            copyField(sectionMap, section, fieldName);
        }
        if (!sectionMap.isEmpty()) {
            target.put(sectionName, sectionMap);
        }
    }

    private void copyField(Map<String, Object> target, JsonNode source, String fieldName) {
        if (source == null) return;
        JsonNode value = source.path(fieldName);
        if (value.isMissingNode() || value.isNull()) return;
        if (value.isNumber()) {
            target.put(fieldName, value.decimalValue());
        } else if (value.isBoolean()) {
            target.put(fieldName, value.asBoolean());
        } else if (value.isTextual()) {
            target.put(fieldName, value.asText());
        } else if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : value) {
                if (item.isTextual()) items.add(item.asText());
                else if (item.isNumber()) items.add(item.decimalValue());
                else items.add(item.toString());
            }
            target.put(fieldName, items);
        } else {
            target.put(fieldName, value.toString());
        }
    }

    private String textAt(JsonNode node, String fieldName) {
        if (node == null) return null;
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    private String truncateString(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String textOrDefault(JsonNode evidence, String field, String fallback) {
        JsonNode value = evidence.path(field);
        return value.isMissingNode() || !value.isTextual() ? fallback : value.asText();
    }

    private String extractEventId(JsonNode evidence) {
        return textOrDefault(evidence, "eventId", "?");
    }
}
