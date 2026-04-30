package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuntimeArtifactService {

    private static final String MARKOV_TRANSITIONS_CSV = "markov_transitions.csv";
    private static final Set<String> FORECAST_BASE_COLUMNS = Set.of("ds", "yhat", "yhat_lower", "yhat_upper", "trend");

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Getter
    private DeploymentManifest deploymentManifest;

    @Getter
    private FeatureBundle featureBundle;

    @Getter
    private List<String> binaryFeatureColumns = List.of();

    @Getter
    private List<String> typeFeatureColumns = List.of();

    @Getter
    private List<String> churnFeatureColumns = List.of();

    @Getter
    private Map<String, Double> sessionNumericMedians = Map.of();

    @Getter
    private Map<String, Double> churnNumericMedians = Map.of();

    @Getter
    private Map<Integer, String> anomalyTypeLabels = Map.of();

    @Getter
    private ClusterScalerParams clusterScalerParams = new ClusterScalerParams();

    @Getter
    private Map<String, List<MarkovTransition>> markovLookup = Map.of();

    @Getter
    private Map<String, List<ForecastSeriesPoint>> forecastSeries = Map.of();

    @Getter
    private Map<String, String> forecastModelJson = Map.of();

    @Getter
    private Map<String, List<Map<String, String>>> dashboardExports = Map.of();

    @Getter
    private Map<String, Double> binaryFeatureImportance = Map.of();

    @Getter
    private Map<String, Double> anomalyTypeFeatureImportance = Map.of();

    @PostConstruct
    public void load() throws IOException {
        deploymentManifest = readJson(properties.getManifest(), DeploymentManifest.class);
        featureBundle = readJson(properties.getFeatureBundle(), FeatureBundle.class);

        binaryFeatureColumns = normalizeStrings(readJsonArray(deploymentManifest.getBinaryDetection().getFeatureColumns()));
        typeFeatureColumns = normalizeStrings(readJsonArray(deploymentManifest.getAnomalyType().getFeatureColumns()));
        churnFeatureColumns = normalizeStrings(readJsonArray(deploymentManifest.getChurn().getFeatureColumns()));
        sessionNumericMedians = readJsonMapDouble(deploymentManifest.getBinaryDetection().getNumericMedians());
        churnNumericMedians = readJsonMapDouble(deploymentManifest.getChurn().getNumericMedians());
        anomalyTypeLabels = normalizeLabelValues(readJsonMapIntegerString(deploymentManifest.getAnomalyType().getLabels()));
        clusterScalerParams = readJson(deploymentManifest.getClustering().getScalerParams(), ClusterScalerParams.class);
        Map<String, List<MarkovTransition>> manifestMarkovLookup =
                normalizeMarkovLookup(readJsonMarkovLookup(deploymentManifest.getNextAction().getArtifact()));
        markovLookup = mergeMarkovLookups(manifestMarkovLookup, loadMarkovLookupFromCsv(MARKOV_TRANSITIONS_CSV));
        forecastSeries = loadForecastSeries();
        forecastModelJson = loadForecastModels();
        dashboardExports = normalizeDashboardExports(loadDashboardExports());
        binaryFeatureImportance = loadFeatureImportance("binary_detector_feature_importance.csv");
        anomalyTypeFeatureImportance = loadFeatureImportance("anomaly_type_feature_importance.csv");

        log.info("Loaded runtime artifacts: binaryCols={}, churnCols={}, markovStates={}, forecasts={}, binaryImportance={}, typeImportance={}",
                binaryFeatureColumns.size(),
                churnFeatureColumns.size(),
                markovLookup.size(),
                forecastSeries.size(),
                binaryFeatureImportance.size(),
                anomalyTypeFeatureImportance.size());
    }

    public Resource resource(String name) {
        return resourceLoader.getResource(properties.getBasePath() + name);
    }

    public boolean resourceExists(String name) {
        return name != null && !name.isBlank() && resource(name).exists();
    }

    public String resolveBinaryArtifact() {
        if (featureBundle != null && featureBundle.isUsesXgboostBinary() && resourceExists("xgb_binary.onnx")) {
            return "xgb_binary.onnx";
        }
        String preferred = deploymentManifest.getBinaryDetection().getPreferred();
        if (resourceExists(preferred)) {
            return preferred;
        }
        if (resourceExists(deploymentManifest.getBinaryDetection().getFallback())) {
            return deploymentManifest.getBinaryDetection().getFallback();
        }
        if (resourceExists("iso_binary.onnx")) {
            return "iso_binary.onnx";
        }
        return deploymentManifest.getBinaryDetection().getFallback();
    }

    private <T> T readJson(String name, Class<T> type) throws IOException {
        try (InputStream inputStream = resource(name).getInputStream()) {
            return objectMapper.readValue(inputStream, type);
        }
    }

    private List<String> readJsonArray(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        try (InputStream inputStream = resource(name).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<String>>() { });
        }
    }

    private Map<String, Double> readJsonMapDouble(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        try (InputStream inputStream = resource(name).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<Map<String, Double>>() { });
        }
    }

    private Map<Integer, String> readJsonMapIntegerString(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        try (InputStream inputStream = resource(name).getInputStream()) {
            Map<String, String> raw = objectMapper.readValue(inputStream, new TypeReference<Map<String, String>>() { });
            Map<Integer, String> labels = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                labels.put(Integer.parseInt(entry.getKey()), entry.getValue());
            }
            return labels;
        }
    }

    private Map<String, List<MarkovTransition>> readJsonMarkovLookup(String name) throws IOException {
        if (name == null || name.isBlank() || !resourceExists(name)) {
            return Map.of();
        }
        try (InputStream inputStream = resource(name).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<Map<String, List<MarkovTransition>>>() { });
        }
    }

    private Map<String, List<MarkovTransition>> loadMarkovLookupFromCsv(String name) {
        if (!resourceExists(name)) {
            return Map.of();
        }
        try {
            List<Map<String, String>> rows = readCsvAsMaps(name);
            Map<String, List<MarkovTransition>> lookup = new LinkedHashMap<>();
            for (Map<String, String> row : rows) {
                String fromAction = TextNormalization.normalizeLabel(row.get("from_action"));
                String toAction = TextNormalization.normalizeLabel(row.get("to_action"));
                Double probability = parseDouble(row.get("probability"));
                if (!hasText(fromAction) || !hasText(toAction) || probability == null) {
                    continue;
                }
                MarkovTransition transition = new MarkovTransition();
                transition.setToAction(toAction);
                transition.setProbability(probability);
                lookup.computeIfAbsent(fromAction, ignored -> new ArrayList<>()).add(transition);
            }
            for (Map.Entry<String, List<MarkovTransition>> entry : lookup.entrySet()) {
                entry.setValue(entry.getValue().stream()
                        .sorted(Comparator.comparing(MarkovTransition::getProbability, Comparator.nullsLast(Double::compareTo)).reversed())
                        .toList());
            }
            return lookup;
        } catch (IOException ex) {
            log.warn("Failed to load markov transitions fallback {}", name, ex);
            return Map.of();
        }
    }

    private Map<String, List<MarkovTransition>> mergeMarkovLookups(Map<String, List<MarkovTransition>> primary,
                                                                    Map<String, List<MarkovTransition>> fallback) {
        if ((primary == null || primary.isEmpty()) && (fallback == null || fallback.isEmpty())) {
            return Map.of();
        }
        if (primary == null || primary.isEmpty()) {
            return fallback == null ? Map.of() : fallback;
        }
        if (fallback == null || fallback.isEmpty()) {
            return primary;
        }

        Map<String, List<MarkovTransition>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<MarkovTransition>> entry : primary.entrySet()) {
            merged.put(entry.getKey(), entry.getValue() == null ? List.of() : entry.getValue());
        }

        for (Map.Entry<String, List<MarkovTransition>> entry : fallback.entrySet()) {
            String fromAction = entry.getKey();
            List<MarkovTransition> primaryTransitions = merged.get(fromAction);
            if (primaryTransitions == null || primaryTransitions.isEmpty()) {
                merged.put(fromAction, entry.getValue());
                continue;
            }

            Map<String, MarkovTransition> byTarget = new LinkedHashMap<>();
            for (MarkovTransition transition : primaryTransitions) {
                if (transition != null && hasText(transition.getToAction())) {
                    byTarget.put(transition.getToAction(), transition);
                }
            }
            for (MarkovTransition transition : entry.getValue()) {
                if (transition != null && hasText(transition.getToAction())) {
                    byTarget.putIfAbsent(transition.getToAction(), transition);
                }
            }
            merged.put(fromAction, byTarget.values().stream()
                    .sorted(Comparator.comparing(MarkovTransition::getProbability, Comparator.nullsLast(Double::compareTo)).reversed())
                    .toList());
        }
        return merged;
    }

    private Map<String, List<ForecastSeriesPoint>> loadForecastSeries() throws IOException {
        Map<String, List<ForecastSeriesPoint>> series = new LinkedHashMap<>();
        for (Map.Entry<String, DeploymentManifest.ForecastArtifact> entry : deploymentManifest.getForecasting().entrySet()) {
            DeploymentManifest.ForecastArtifact artifact = entry.getValue();
            if (artifact == null || artifact.getForecastCsv() == null) {
                continue;
            }
            series.put(entry.getKey(), readForecastCsv(artifact.getForecastCsv()));
        }
        return series;
    }

    private Map<String, String> loadForecastModels() throws IOException {
        Map<String, String> models = new LinkedHashMap<>();
        for (Map.Entry<String, DeploymentManifest.ForecastArtifact> entry : deploymentManifest.getForecasting().entrySet()) {
            DeploymentManifest.ForecastArtifact artifact = entry.getValue();
            if (artifact == null || artifact.getProphetJson() == null || artifact.getProphetJson().isBlank()) {
                continue;
            }
            if (!resourceExists(artifact.getProphetJson())) {
                continue;
            }
            models.put(entry.getKey(), readResourceAsString(artifact.getProphetJson()));
        }
        return models;
    }

    private Map<String, List<Map<String, String>>> loadDashboardExports() throws IOException {
        Map<String, List<Map<String, String>>> exports = new LinkedHashMap<>();
        for (String name : deploymentManifest.getDashboardExports()) {
            if (!resourceExists(name)) {
                continue;
            }
            exports.put(name, readCsvAsMaps(name));
        }
        return exports;
    }

    private Map<String, Double> loadFeatureImportance(String name) {
        if (!resourceExists(name)) {
            log.debug("Feature importance file {} not found, explainability will use heuristics", name);
            return Map.of();
        }
        try {
            List<Map<String, String>> rows = readCsvAsMaps(name);
            Map<String, Double> importance = new LinkedHashMap<>();
            for (Map<String, String> row : rows) {
                String feature = TextNormalization.normalizeLabel(row.get("feature"));
                String value = row.get("importance");
                if (feature != null && value != null) {
                    importance.put(feature, parseDouble(value));
                }
            }
            return importance;
        } catch (IOException ex) {
            log.warn("Failed to load feature importance from {}", name, ex);
            return Map.of();
        }
    }

    private List<ForecastSeriesPoint> readForecastCsv(String name) throws IOException {
        List<Map<String, String>> rows = readCsvAsMaps(name);
        List<ForecastSeriesPoint> points = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            points.add(ForecastSeriesPoint.builder()
                    .ds(row.get("ds"))
                    .yhat(parseDouble(row.get("yhat")))
                    .yhatLower(parseDouble(row.get("yhat_lower")))
                    .yhatUpper(parseDouble(row.get("yhat_upper")))
                    .trend(parseDouble(row.get("trend")))
                    .metrics(parseForecastMetrics(row))
                    .build());
        }
        return points;
    }

    private Map<String, Double> parseForecastMetrics(Map<String, String> row) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key == null || FORECAST_BASE_COLUMNS.contains(key)) {
                continue;
            }
            Double value = parseDouble(entry.getValue());
            if (value != null) {
                metrics.put(key, value);
            }
        }
        return metrics;
    }

    private List<Map<String, String>> readCsvAsMaps(String name) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource(name).getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            String[] headers = splitCsv(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = splitCsv(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i], normalizeCsvValue(i < values.length ? values[i] : ""));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private String readResourceAsString(String name) throws IOException {
        try (InputStream inputStream = resource(name).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String[] splitCsv(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace("\"", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> normalizeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(TextNormalization::normalizeLabel).toList();
    }

    private Map<Integer, String> normalizeLabelValues(Map<Integer, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : values.entrySet()) {
            normalized.put(entry.getKey(), TextNormalization.normalizeLabel(entry.getValue()));
        }
        return normalized;
    }

    private Map<String, List<MarkovTransition>> normalizeMarkovLookup(Map<String, List<MarkovTransition>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<MarkovTransition>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<MarkovTransition>> entry : values.entrySet()) {
            String fromAction = TextNormalization.normalizeLabel(entry.getKey());
            List<MarkovTransition> transitions = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream()
                    .map(this::normalizeTransition)
                    .toList();
            normalized.put(fromAction, transitions);
        }
        return normalized;
    }

    private MarkovTransition normalizeTransition(MarkovTransition transition) {
        if (transition == null) {
            return null;
        }
        MarkovTransition normalized = new MarkovTransition();
        normalized.setToAction(TextNormalization.normalizeLabel(transition.getToAction()));
        normalized.setProbability(transition.getProbability());
        return normalized;
    }

    private Map<String, List<Map<String, String>>> normalizeDashboardExports(Map<String, List<Map<String, String>>> exports) {
        if (exports == null || exports.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Map<String, String>>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, String>>> entry : exports.entrySet()) {
            List<Map<String, String>> rows = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream()
                    .map(this::normalizeRowValues)
                    .toList();
            normalized.put(entry.getKey(), rows);
        }
        return normalized;
    }

    private Map<String, String> normalizeRowValues(Map<String, String> row) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            normalized.put(entry.getKey(), normalizeCsvValue(entry.getValue()));
        }
        return normalized;
    }

    private String normalizeCsvValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.replace("\"\"", "\"");
        return TextNormalization.normalizeLabel(normalized);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
