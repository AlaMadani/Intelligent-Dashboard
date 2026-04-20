package com.noveocare.dataprocessor.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.noveocare.dataprocessor.ai.DeploymentManifest;
import com.noveocare.dataprocessor.ai.FeatureEngineeringService;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
import com.noveocare.dataprocessor.dto.AnomalyTypeResult;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.FeatureContribution;
import com.noveocare.dataprocessor.dto.NextActionScore;
import com.noveocare.dataprocessor.dto.PathDeviationResult;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModelInferenceService {

    private final RuntimeArtifactService runtimeArtifactService;
    private final FeatureEngineeringService featureEngineeringService;
    private final TransitionMatrixService transitionMatrixService;

    private OrtEnvironment environment;
    private LoadedSession binaryDetector;
    private LoadedSession anomalyType;
    private LoadedSession churn;
    private LoadedSession clustering;

    @PostConstruct
    public void init() throws OrtException {
        environment = OrtEnvironment.getEnvironment();
        binaryDetector = loadSession(runtimeArtifactService.resolveBinaryArtifact());
        anomalyType = loadSession(runtimeArtifactService.getDeploymentManifest().getAnomalyType().getModel());
        churn = loadSession(runtimeArtifactService.getDeploymentManifest().getChurn().getModel());
        clustering = loadSession(runtimeArtifactService.getDeploymentManifest().getClustering().getModel());

        log.info("Manifest runtime initialized binary={}, type={}, churn={}, clustering={}",
                binaryDetector.artifactName(),
                anomalyType.artifactName(),
                churn.artifactName(),
                clustering.artifactName());
    }

    public SessionInsight infer(SessionSummary summary, List<AuditTrailEvent> enrichedEvents, List<String> triggeredRules) {
        List<String> warnings = new ArrayList<>();
        BinaryDetectionResult binaryResult = detectBinaryAnomaly(summary, warnings);
        PathDeviationResult pathDeviation = transitionMatrixService.evaluatePathDeviation(enrichedEvents);
        List<PathDeviationResult> rareTransitions = transitionMatrixService.rareTransitions(enrichedEvents);
        boolean overallAnomaly = binaryResult.anomalyFlag() || pathDeviation.isDeviated() || !triggeredRules.isEmpty();

        AnomalyTypeResult anomalyTypeResult = overallAnomaly
                ? classifyAnomaly(summary, warnings, triggeredRules, enrichedEvents)
                : AnomalyTypeResult.builder().type("normal").confidence(1.0).build();
        double churnProbability = predictChurn(summary, warnings);
        Integer personaCluster = predictCluster(summary, warnings);
        List<NextActionScore> nextActions = transitionMatrixService.predictNextActions(summary.getLastAction(), 3);
        double ensembleRiskScore = computeEnsembleRisk(summary, binaryResult.anomalyProbability(), churnProbability);
        List<String> contextTags = buildContextTags(summary, triggeredRules, pathDeviation, rareTransitions);

        List<FeatureContribution> topFeatures = overallAnomaly
                ? buildFeatureContributions(summary, anomalyTypeResult.getType())
                : List.of();
        String explainabilityText = overallAnomaly
                ? buildExplainabilityText(summary, anomalyTypeResult.getType(), topFeatures, triggeredRules)
                : null;

        return SessionInsight.builder()
                .insuredId(summary.getInsuredId())
                .sessionId(summary.getSessionId())
                .computedAt(Instant.now())
                .binaryAnomaly(binaryResult.anomalyFlag())
                .anomaly(overallAnomaly)
                .anomalyScore(binaryResult.score())
                .anomalyProbability(binaryResult.anomalyProbability())
                .binaryDetectorArtifact(binaryResult.artifact())
                .anomalyType(anomalyTypeResult.getType())
                .anomalyTypeConfidence(anomalyTypeResult.getConfidence())
                .churnProbability(churnProbability)
                .personaCluster(personaCluster)
                .ensembleRiskScore(ensembleRiskScore)
                .riskLevel(resolveRiskLevel(ensembleRiskScore))
                .pathDeviation(pathDeviation)
                .rareTransitions(rareTransitions)
                .nextActions(nextActions)
                .contextTags(contextTags)
                .triggeredRules(triggeredRules)
                .warnings(warnings)
                .topContributingFeatures(topFeatures)
                .explainabilityText(explainabilityText)
                .build();
    }

    public SessionInsight inferLightweight(SessionSummary summary,
                                           List<AuditTrailEvent> enrichedEvents,
                                           List<String> triggeredRules,
                                           String reason) {
        List<String> warnings = new ArrayList<>();
        warnings.add(reason == null || reason.isBlank() ? "heavy_inference_disabled" : reason);
        PathDeviationResult pathDeviation = transitionMatrixService.evaluatePathDeviation(enrichedEvents);
        List<PathDeviationResult> rareTransitions = transitionMatrixService.rareTransitions(enrichedEvents);
        boolean heuristicAnomaly = defaultInt(summary.getIpChanged()) == 1
                || defaultInt(summary.getDeviceChanged()) == 1
                || defaultInt(summary.getTotalKOs()) >= 3
                || defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10
                || defaultInt(summary.getPingPongCount()) >= 2;
        boolean anomaly = heuristicAnomaly || pathDeviation.isDeviated() || !triggeredRules.isEmpty();
        double riskScore = computeEnsembleRisk(summary, heuristicAnomaly ? 1.0 : 0.0, 0.0);
        String anomalyType = heuristicType(summary, triggeredRules, enrichedEvents).getType();
        List<String> contextTags = buildContextTags(summary, triggeredRules, pathDeviation, rareTransitions);

        List<FeatureContribution> topFeatures = anomaly
                ? buildFeatureContributions(summary, anomalyType)
                : List.of();
        String explainabilityText = anomaly
                ? buildExplainabilityText(summary, anomalyType, topFeatures, triggeredRules)
                : null;

        return SessionInsight.builder()
                .insuredId(summary.getInsuredId())
                .sessionId(summary.getSessionId())
                .computedAt(Instant.now())
                .binaryAnomaly(heuristicAnomaly)
                .anomaly(anomaly)
                .anomalyScore(riskScore)
                .anomalyProbability(heuristicAnomaly ? 1.0 : 0.0)
                .binaryDetectorArtifact("load_shedding_fallback")
                .anomalyType(anomalyType)
                .anomalyTypeConfidence(0.5)
                .churnProbability(0.0)
                .personaCluster(null)
                .ensembleRiskScore(riskScore)
                .riskLevel(resolveRiskLevel(riskScore))
                .pathDeviation(pathDeviation)
                .rareTransitions(rareTransitions)
                .nextActions(transitionMatrixService.predictNextActions(summary.getLastAction(), 3))
                .contextTags(contextTags)
                .triggeredRules(triggeredRules)
                .warnings(warnings)
                .topContributingFeatures(topFeatures)
                .explainabilityText(explainabilityText)
                .build();
    }

    private BinaryDetectionResult detectBinaryAnomaly(SessionSummary summary, List<String> warnings) {
        if (binaryDetector.available()) {
            float[] vector = featureEngineeringService.buildTabularFeatures(
                    summary,
                    runtimeArtifactService.getBinaryFeatureColumns(),
                    runtimeArtifactService.getSessionNumericMedians());
            try {
                ResultBundle bundle = runSingle(binaryDetector, vector);
                long label = bundle.longOutput("label", 1L);
                double score = bundle.doubleOutput("scores", 0.0);
                boolean anomalyFlag;
                double anomalyProbability;

                if (binaryDetector.artifactName() != null && binaryDetector.artifactName().contains("iso")) {
                    Double threshold = runtimeArtifactService.getFeatureBundle().getIsoThreshold();
                    anomalyFlag = label < 0 || (threshold != null && score >= threshold);
                    anomalyProbability = anomalyFlag ? 1.0 : 0.0;
                } else {
                    anomalyProbability = bundle.probabilityForClass(1L);
                    anomalyFlag = label == 1L || anomalyProbability >= 0.5;
                    score = anomalyProbability;
                }
                return new BinaryDetectionResult(anomalyFlag, score, anomalyProbability, binaryDetector.artifactName());
            } catch (Exception ex) {
                warnings.add("binary_detection_runtime_failed");
                log.warn("Binary detection failed for session {}", summary.getSessionId(), ex);
            }
        } else {
            warnings.add("binary_detection_artifact_unavailable");
        }

        boolean heuristicAnomaly = defaultInt(summary.getIpChanged()) == 1
                || defaultInt(summary.getDeviceChanged()) == 1
                || defaultInt(summary.getTotalKOs()) >= 3
                || defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10
                || defaultInt(summary.getPingPongCount()) >= 2;
        return new BinaryDetectionResult(
                heuristicAnomaly,
                heuristicAnomaly ? summary.getRiskScoreMax() : 0.0,
                heuristicAnomaly ? 1.0 : 0.0,
                "heuristic_fallback");
    }

    private AnomalyTypeResult classifyAnomaly(SessionSummary summary,
                                              List<String> warnings,
                                              List<String> triggeredRules,
                                              List<AuditTrailEvent> enrichedEvents) {
        if (anomalyType.available()) {
            float[] vector = featureEngineeringService.buildTabularFeatures(
                    summary,
                    runtimeArtifactService.getTypeFeatureColumns(),
                    runtimeArtifactService.getSessionNumericMedians());
            try {
                ResultBundle bundle = runSingle(anomalyType, vector);
                long label = bundle.longOutput("output_label", 0L);
                double confidence = bundle.probabilityForClass(label);
                String type = runtimeArtifactService.getAnomalyTypeLabels()
                        .getOrDefault((int) label, "unknown");
                return AnomalyTypeResult.builder()
                        .type(type)
                        .confidence(confidence)
                        .build();
            } catch (Exception ex) {
                warnings.add("anomaly_type_runtime_failed");
                log.warn("Anomaly type classification failed for session {}", summary.getSessionId(), ex);
            }
        } else {
            warnings.add("anomaly_type_artifact_unavailable");
        }
        return heuristicType(summary, triggeredRules, enrichedEvents);
    }

    private double predictChurn(SessionSummary summary, List<String> warnings) {
        if (churn.available()) {
            float[] vector = featureEngineeringService.buildTabularFeatures(
                    summary,
                    runtimeArtifactService.getChurnFeatureColumns(),
                    runtimeArtifactService.getChurnNumericMedians());
            try {
                ResultBundle bundle = runSingle(churn, vector);
                return bundle.probabilityForClass(1L);
            } catch (Exception ex) {
                warnings.add("churn_runtime_failed");
                log.warn("Churn prediction failed for session {}", summary.getSessionId(), ex);
            }
        } else {
            warnings.add("churn_artifact_unavailable");
        }
        return defaultInt(summary.getEndedAbruptly()) == 1 ? 1.0 : 0.0;
    }

    private Integer predictCluster(SessionSummary summary, List<String> warnings) {
        if (clustering.available()) {
            float[] vector = featureEngineeringService.buildClusterFeatures(
                    summary,
                    runtimeArtifactService.getDeploymentManifest().getClustering().getClusterFeatures());
            try {
                ResultBundle bundle = runSingle(clustering, vector);
                return (int) bundle.longOutput("label", 0L);
            } catch (Exception ex) {
                warnings.add("clustering_runtime_failed");
                log.warn("Persona clustering failed for session {}", summary.getSessionId(), ex);
            }
        } else {
            warnings.add("clustering_artifact_unavailable");
        }
        return null;
    }

    private ResultBundle runSingle(LoadedSession loadedSession, float[] vector) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, new float[][]{vector});
             OrtSession.Result result = loadedSession.session().run(Map.of(loadedSession.inputName(), tensor))) {
            return ResultBundle.from(result);
        }
    }

    private LoadedSession loadSession(String artifactName) throws OrtException {
        if (artifactName == null || artifactName.isBlank()) {
            return LoadedSession.unavailable(artifactName);
        }
        if (!artifactName.endsWith(".onnx")) {
            log.warn("Artifact {} is not ONNX; Java runtime will not load it directly.", artifactName);
            return LoadedSession.unavailable(artifactName);
        }
        if (!runtimeArtifactService.resourceExists(artifactName)) {
            log.warn("Artifact {} does not exist under {}", artifactName, runtimeArtifactService.resource(artifactName));
            return LoadedSession.unavailable(artifactName);
        }
        try (InputStream inputStream = runtimeArtifactService.resource(artifactName).getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            OrtSession session = environment.createSession(bytes, new OrtSession.SessionOptions());
            String inputName = session.getInputNames().iterator().next();
            return new LoadedSession(artifactName, session, inputName, true);
        } catch (IOException ex) {
            log.warn("Failed to read artifact {}", artifactName, ex);
            return LoadedSession.unavailable(artifactName);
        }
    }

    private AnomalyTypeResult heuristicType(SessionSummary summary,
                                            List<String> triggeredRules,
                                            List<AuditTrailEvent> enrichedEvents) {
        if (defaultInt(summary.getDeviceChanged()) == 1) {
            return AnomalyTypeResult.builder().type("impossible_device_switch").confidence(0.8).build();
        }
        if (defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10) {
            return AnomalyTypeResult.builder().type("data_exfiltration").confidence(0.8).build();
        }
        if (defaultInt(summary.getPingPongCount()) >= 2) {
            return AnomalyTypeResult.builder().type("ping_pong_loop").confidence(0.8).build();
        }
        if (defaultInt(summary.getTotalKOs()) >= 3) {
            return AnomalyTypeResult.builder().type("repeated_fail").confidence(0.75).build();
        }
        if (defaultInt(summary.getIpChanged()) == 1) {
            return AnomalyTypeResult.builder().type("geo_jump").confidence(0.7).build();
        }
        if (transitionMatrixService.isImpossibleTransition(enrichedEvents)) {
            return AnomalyTypeResult.builder().type("impossible_seq").confidence(0.7).build();
        }
        if (triggeredRules.contains("unusual_hour")) {
            return AnomalyTypeResult.builder().type("unusual_hour").confidence(0.7).build();
        }
        if (triggeredRules.contains("skip_login")) {
            return AnomalyTypeResult.builder().type("skip_login").confidence(0.7).build();
        }
        if (defaultInt(summary.getEndedAbruptly()) == 1
                && defaultInt(summary.getTotalDurationSeconds() != null ? summary.getTotalDurationSeconds().intValue() : 0) > 1_200) {
            return AnomalyTypeResult.builder().type("zombie_session").confidence(0.65).build();
        }
        return AnomalyTypeResult.builder().type("unknown").confidence(0.5).build();
    }

    private List<FeatureContribution> buildFeatureContributions(SessionSummary summary, String anomalyType) {
        Map<String, Double> importance = runtimeArtifactService.getAnomalyTypeFeatureImportance();
        if (importance.isEmpty()) {
            importance = runtimeArtifactService.getBinaryFeatureImportance();
        }
        if (importance.isEmpty()) {
            return buildHeuristicContributions(summary, anomalyType);
        }

        Map<String, Object> summaryValues = buildSummaryValueMap(summary);
        List<FeatureContribution> contributions = new ArrayList<>();

        for (Map.Entry<String, Double> entry : importance.entrySet()) {
            String feature = entry.getKey();
            Double imp = entry.getValue();
            Object value = summaryValues.get(feature);
            if (value != null && imp != null && imp > 0.01) {
                contributions.add(FeatureContribution.builder()
                        .feature(feature)
                        .importance(imp)
                        .actualValue(value)
                        .description(describeFeature(feature, value))
                        .build());
            }
        }

        return contributions.stream()
                .sorted(Comparator.comparing(FeatureContribution::getImportance).reversed())
                .limit(5)
                .toList();
    }

    private List<FeatureContribution> buildHeuristicContributions(SessionSummary summary, String anomalyType) {
        List<FeatureContribution> contributions = new ArrayList<>();
        if (defaultInt(summary.getTotalKOs()) >= 3) {
            contributions.add(FeatureContribution.builder()
                    .feature("totalKOs")
                    .importance(0.33)
                    .actualValue(summary.getTotalKOs())
                    .description("High error count: " + summary.getTotalKOs() + " failures")
                    .build());
        }
        if (defaultInt(summary.getIpChanged()) == 1) {
            contributions.add(FeatureContribution.builder()
                    .feature("ipChanged")
                    .importance(0.30)
                    .actualValue(1)
                    .description("IP address changed during session")
                    .build());
        }
        if (defaultInt(summary.getDeviceChanged()) == 1) {
            contributions.add(FeatureContribution.builder()
                    .feature("deviceChanged")
                    .importance(0.25)
                    .actualValue(1)
                    .description("Device changed during session")
                    .build());
        }
        if (defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10) {
            contributions.add(FeatureContribution.builder()
                    .feature("maxDownloadsIn2Minutes")
                    .importance(0.20)
                    .actualValue(summary.getMaxDownloadsIn2Minutes())
                    .description("Excessive downloads: " + summary.getMaxDownloadsIn2Minutes() + " in 2 minutes")
                    .build());
        }
        if (defaultInt(summary.getPingPongCount()) >= 2) {
            contributions.add(FeatureContribution.builder()
                    .feature("pingPongCount")
                    .importance(0.15)
                    .actualValue(summary.getPingPongCount())
                    .description("Navigation loop detected: " + summary.getPingPongCount() + " ping-pong patterns")
                    .build());
        }
        return contributions;
    }

    private Map<String, Object> buildSummaryValueMap(SessionSummary summary) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("totalEvents", summary.getTotalEvents());
        values.put("totalDurationSeconds", summary.getTotalDurationSeconds());
        values.put("avgInterActionSeconds", summary.getAvgInterActionSeconds());
        values.put("minInterActionSeconds", summary.getMinInterActionSeconds());
        values.put("maxInterActionSeconds", summary.getMaxInterActionSeconds());
        values.put("uniqueActions", summary.getUniqueActions());
        values.put("uniqueRoutes", summary.getUniqueRoutes());
        values.put("uniqueIpsUsed", summary.getUniqueIpsUsed());
        values.put("uniqueDevicesUsed", summary.getUniqueDevicesUsed());
        values.put("totalKOs", summary.getTotalKOs());
        values.put("totalOKs", summary.getTotalOKs());
        values.put("longestKoStreak", summary.getLongestKoStreak());
        values.put("hasLogin", summary.getHasLogin());
        values.put("hasLogout", summary.getHasLogout());
        values.put("ipChanged", summary.getIpChanged());
        values.put("deviceChanged", summary.getDeviceChanged());
        values.put("totalDownloadActions", summary.getTotalDownloadActions());
        values.put("maxDownloadsIn2Minutes", summary.getMaxDownloadsIn2Minutes());
        values.put("pingPongCount", summary.getPingPongCount());
        values.put("startHour", summary.getStartHour());
        values.put("endHour", summary.getEndHour());
        values.put("dayOfWeek", summary.getDayOfWeek());
        values.put("isWeekend", summary.getIsWeekend());
        return values;
    }

    private String describeFeature(String feature, Object value) {
        return switch (feature) {
            case "totalKOs" -> "Error count: " + value;
            case "longestKoStreak" -> "Consecutive failures: " + value;
            case "ipChanged" -> Integer.valueOf(1).equals(value) ? "IP changed during session" : "IP stable";
            case "deviceChanged" -> Integer.valueOf(1).equals(value) ? "Device changed during session" : "Device stable";
            case "uniqueIpsUsed" -> "Unique IPs: " + value;
            case "uniqueDevicesUsed" -> "Unique devices: " + value;
            case "maxDownloadsIn2Minutes" -> "Downloads in 2min window: " + value;
            case "pingPongCount" -> "Navigation loops: " + value;
            case "totalDurationSeconds" -> "Session duration: " + value + "s";
            case "avgInterActionSeconds" -> "Avg time between actions: " + value + "s";
            case "totalEvents" -> "Total events: " + value;
            case "hasLogin" -> Integer.valueOf(1).equals(value) ? "User logged in" : "No login detected";
            case "hasLogout" -> Integer.valueOf(1).equals(value) ? "User logged out" : "No logout (abrupt end)";
            case "startHour", "endHour" -> "Hour: " + value;
            default -> feature + " = " + value;
        };
    }

    private String buildExplainabilityText(SessionSummary summary, String anomalyType,
                                           List<FeatureContribution> topFeatures, List<String> triggeredRules) {
        StringBuilder sb = new StringBuilder();
        sb.append("Flagged as ").append(anomalyType);

        if (!topFeatures.isEmpty()) {
            sb.append(" because ");
            List<String> reasons = topFeatures.stream()
                    .limit(3)
                    .map(fc -> fc.getFeature() + " = " + fc.getActualValue()
                            + " (importance: " + String.format("%.2f", fc.getImportance()) + ")")
                    .toList();
            sb.append(String.join(", ", reasons));
        }

        if (!triggeredRules.isEmpty()) {
            sb.append(". Rules triggered: ").append(String.join(", ", triggeredRules));
        }

        return sb.toString();
    }

    private List<String> buildContextTags(SessionSummary summary,
                                          List<String> triggeredRules,
                                          PathDeviationResult pathDeviation,
                                          List<PathDeviationResult> rareTransitions) {
        Set<String> tags = new LinkedHashSet<>();
        if (defaultInt(summary.getIpChanged()) == 1) {
            tags.add("IP Changed");
        }
        if (defaultInt(summary.getDeviceChanged()) == 1) {
            tags.add("Device Changed");
        }
        if (defaultInt(summary.getTotalKOs()) >= 3 || defaultInt(summary.getLongestKoStreak()) >= 3) {
            tags.add("High Error Rate");
        }
        if (defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10) {
            tags.add("High Download Rate");
        }
        if (defaultInt(summary.getPingPongCount()) >= 2) {
            tags.add("Ping-Pong Loop");
        }
        if (pathDeviation != null && pathDeviation.isDeviated()) {
            tags.add("Path Deviation");
        }
        if (rareTransitions != null && !rareTransitions.isEmpty()) {
            tags.add("Rare Transition");
        }
        if (triggeredRules.contains("geo_jump")) {
            tags.add("Geo-Jump");
        }
        if (triggeredRules.contains("skip_login")) {
            tags.add("Skip Login");
        }
        if (triggeredRules.contains("rapid_fire")) {
            tags.add("Rapid Fire");
        }
        if (triggeredRules.contains("session_timeout")) {
            tags.add("Zombie Session");
        }
        return List.copyOf(tags);
    }

    private double computeEnsembleRisk(SessionSummary summary, double anomalyProbability, double churnProbability) {
        return clamp(
                30.0 * defaultInt(summary.getIpChanged())
                        + 25.0 * defaultInt(summary.getDeviceChanged())
                        + 15.0 * (defaultInt(summary.getTotalKOs()) >= 3 ? 1.0 : 0.0)
                        + 15.0 * (defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10 ? 1.0 : 0.0)
                        + 15.0 * (defaultInt(summary.getPingPongCount()) >= 2 ? 1.0 : 0.0)
                        + 40.0 * anomalyProbability
                        + 15.0 * churnProbability,
                0.0,
                100.0);
    }

    private String resolveRiskLevel(double riskScore) {
        if (riskScore >= 80.0) {
            return "HIGH";
        }
        if (riskScore >= 50.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    @PreDestroy
    public void close() throws OrtException {
        closeSession(binaryDetector);
        closeSession(anomalyType);
        closeSession(churn);
        closeSession(clustering);
        if (environment != null) {
            environment.close();
        }
    }

    private void closeSession(LoadedSession loadedSession) throws OrtException {
        if (loadedSession != null && loadedSession.session() != null) {
            loadedSession.session().close();
        }
    }

    private record LoadedSession(String artifactName, OrtSession session, String inputName, boolean available) {
        private static LoadedSession unavailable(String artifactName) {
            return new LoadedSession(artifactName, null, null, false);
        }
    }

    private record BinaryDetectionResult(boolean anomalyFlag, double score, double anomalyProbability, String artifact) {
    }

    private record ResultBundle(Map<String, Object> outputs) {
        private static ResultBundle from(OrtSession.Result result) throws OrtException {
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (Map.Entry<String, ? extends OnnxValue> entry : result) {
                outputs.put(entry.getKey(), entry.getValue().getValue());
            }
            return new ResultBundle(outputs);
        }

        private long longOutput(String name, long fallback) {
            Object value = outputs.get(name);
            if (value instanceof long[] array && array.length > 0) {
                return array[0];
            }
            if (value instanceof long[][] array && array.length > 0 && array[0].length > 0) {
                return array[0][0];
            }
            if (value instanceof int[] array && array.length > 0) {
                return array[0];
            }
            if (value instanceof int[][] array && array.length > 0 && array[0].length > 0) {
                return array[0][0];
            }
            return fallback;
        }

        private double doubleOutput(String name, double fallback) {
            Object value = outputs.get(name);
            if (value instanceof float[] array && array.length > 0) {
                return array[0];
            }
            if (value instanceof float[][] array && array.length > 0 && array[0].length > 0) {
                return array[0][0];
            }
            if (value instanceof double[] array && array.length > 0) {
                return array[0];
            }
            if (value instanceof double[][] array && array.length > 0 && array[0].length > 0) {
                return array[0][0];
            }
            return fallback;
        }

        @SuppressWarnings("unchecked")
        private double probabilityForClass(long label) {
            Object value = outputs.get("output_probability");
            if (value instanceof List<?> sequence && !sequence.isEmpty() && sequence.get(0) instanceof Map<?, ?> probabilityMap) {
                Object probability = probabilityMap.get(label);
                if (probability == null) {
                    probability = probabilityMap.get((int) label);
                }
                if (probability instanceof Number number) {
                    return number.doubleValue();
                }
            }
            value = outputs.get("scores");
            if (value instanceof float[][] matrix && matrix.length > 0 && matrix[0].length > (int) label) {
                return matrix[0][(int) label];
            }
            return 0.0;
        }
    }
}
