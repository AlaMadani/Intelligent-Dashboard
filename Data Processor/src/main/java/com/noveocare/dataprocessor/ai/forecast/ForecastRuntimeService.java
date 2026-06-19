package com.noveocare.dataprocessor.ai.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveocare.dataprocessor.ai.artifact.ForecastConfig;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.tree.XGBoostJsonPredictor;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.service.StatisticsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ForecastRuntimeService {

    private final RuntimeArtifactService artifactService;
    private final StatisticsService statisticsService;
    private final AiForecastProperties forecastProperties;
    private RidgeModel anomalyRateModel;
    private XGBoostJsonPredictor totalEventsModel;
    private final List<String> loadWarnings = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (!forecastProperties.isEnabled()) {
            loadWarnings.add("forecast_disabled");
            return;
        }
        try {
            if (artifactService.modelExists(RuntimeArtifactService.FORECAST_ANOMALY_RATE_RIDGE)) {
                anomalyRateModel = RidgeModel.from(artifactService.readModelJson(RuntimeArtifactService.FORECAST_ANOMALY_RATE_RIDGE, JsonNode.class));
                log.info("Loaded Ridge anomaly-rate forecast JSON");
            } else {
                loadWarnings.add("forecast_ridge_model_unavailable");
            }
        } catch (Exception ex) {
            loadWarnings.add("forecast_ridge_runtime_unavailable");
            log.warn("Ridge anomaly-rate forecast unavailable", ex);
        }
        try {
            if (artifactService.modelExists(RuntimeArtifactService.FORECAST_TOTAL_EVENTS_XGBOOST_JSON)) {
                totalEventsModel = new XGBoostJsonPredictor(artifactService.readModelJson(RuntimeArtifactService.FORECAST_TOTAL_EVENTS_XGBOOST_JSON, JsonNode.class));
                log.info("Loaded XGBoost total-events forecast JSON trees={}", totalEventsModel.treeCount());
            } else {
                loadWarnings.add("forecast_xgboost_model_unavailable");
            }
        } catch (Exception ex) {
            loadWarnings.add("forecast_xgboost_runtime_unavailable");
            log.warn("XGBoost total-events forecast unavailable", ex);
        }
    }

    public boolean isLoaded() {
        return anomalyRateModel != null || totalEventsModel != null;
    }

    public boolean ridgeLoaded() {
        return anomalyRateModel != null;
    }

    public boolean xgboostLoaded() {
        return totalEventsModel != null;
    }

    public ForecastPrediction forecast(LocalDate referenceDate) {
        if (!forecastProperties.isEnabled()) {
            return ForecastPrediction.builder()
                    .referenceDate(referenceDate == null ? LocalDate.now(ZoneOffset.UTC) : referenceDate)
                    .forecastDate(referenceDate == null ? LocalDate.now(ZoneOffset.UTC) : referenceDate)
                    .warnings(List.of("forecast_disabled"))
                    .build();
        }
        LocalDate date = referenceDate == null ? LocalDate.now(ZoneOffset.UTC) : referenceDate;
        List<String> warnings = new ArrayList<>();
        warnings.addAll(loadWarnings);
        Map<String, Double> totalFeatures = buildTotalEventsFeatures(date);
        Map<String, Double> anomalyFeatures = buildForecastFeatures(date, anomalyRateFeatureOrder());
        Double totalEvents = predictTotalEvents(totalFeatures, warnings);
        Double anomalyRate = predictAnomalyRate(anomalyFeatures, warnings);
        Double expectedAlertVolume = totalEvents == null || anomalyRate == null ? null : totalEvents * anomalyRate;
        return ForecastPrediction.builder()
                .referenceDate(date)
                .forecastDate(date.plusDays(1))
                .totalEventsForecast(totalEvents)
                .anomalyRateForecast(anomalyRate)
                .expectedAlertVolume(expectedAlertVolume)
                .totalEventsModelArtifact(totalEventsModel == null ? "fallback" : "macro_forecaster_total_events_XGBoost.json")
                .totalEventsModelName(totalEventsModel == null ? "fallback" : "XGBoost")
                .anomalyRateModelArtifact(anomalyRateModel == null ? "fallback" : "macro_forecaster_anomaly_rate_Ridge.json")
                .anomalyRateModelName(anomalyRateModel == null ? "fallback" : "Ridge")
                .anomalyRateStrategy(anomalyRateModel == null ? "fallback" : "ridge_json")
                .totalEventsFeatures(totalFeatures)
                .anomalyRateFeatures(anomalyFeatures)
                .warnings(warnings.stream().distinct().toList())
                .build();
    }

    public Map<String, Double> buildTotalEventsFeatures(LocalDate date) {
        return buildForecastFeatures(date, totalEventsFeatureOrder());
    }

    public float[] buildFeatureVector(Map<String, Double> features) {
        List<String> order = totalEventsFeatureOrder();
        float[] vector = new float[order.size()];
        for (int i = 0; i < order.size(); i++) {
            vector[i] = features.getOrDefault(order.get(i), 0.0).floatValue();
        }
        return vector;
    }

    private Double predictTotalEvents(Map<String, Double> features, List<String> warnings) {
        if (totalEventsModel == null) {
            warnings.add("forecast_total_events_model_unavailable");
            return fallbackTotalEvents(features);
        }
        try {
            double[] vector = totalEventsFeatureOrder().stream()
                    .mapToDouble(feature -> features.getOrDefault(feature, 0.0))
                    .toArray();
            return Math.max(0.0, totalEventsModel.predict(vector));
        } catch (Exception ex) {
            warnings.add("forecast_total_events_runtime_failed");
            log.warn("XGBoost total-events forecast failed", ex);
            return fallbackTotalEvents(features);
        }
    }

    private Double predictAnomalyRate(Map<String, Double> features, List<String> warnings) {
        if (anomalyRateModel != null) {
            try {
                return clamp(anomalyRateModel.predict(features), 0.0, 1.0);
            } catch (Exception ex) {
                warnings.add("forecast_anomaly_rate_runtime_failed");
                log.warn("Ridge anomaly-rate forecast failed", ex);
            }
        }
        warnings.add("forecast_anomaly_rate_fallback_used");
        return fallbackAnomalyRate(features);
    }

    private Double fallbackTotalEvents(Map<String, Double> features) {
        Double lag7 = features.get("total_events_lag_7");
        if (lag7 != null && lag7 > 0.0) {
            return lag7;
        }
        return features.getOrDefault("total_events_roll_mean_7", 0.0);
    }

    private Double fallbackAnomalyRate(Map<String, Double> features) {
        Double lag7 = features.get("anomaly_rate_lag_7");
        if (lag7 != null && lag7 > 0.0) {
            return lag7;
        }
        Double rolling = features.get("anomaly_rate_roll_mean_7");
        if (rolling != null && rolling > 0.0) {
            return rolling;
        }
        return 0.0;
    }

    private Map<String, Double> buildForecastFeatures(LocalDate date, List<String> featureOrder) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String feature : featureOrder) {
            values.put(feature, valueForFeature(feature, date));
        }
        return values;
    }

    private List<String> totalEventsFeatureOrder() {
        ForecastConfig.ModelConfig model = artifactService.getForecastConfig().totalEventsModel();
        if (!model.getFeatureOrder().isEmpty()) {
            return model.getFeatureOrder();
        }
        return artifactService.getForecastConfig().getTotalEvents().getFeatureOrder();
    }

    private List<String> anomalyRateFeatureOrder() {
        ForecastConfig.ModelConfig model = artifactService.getForecastConfig().anomalyRateModel();
        if (!model.getFeatureOrder().isEmpty()) {
            return model.getFeatureOrder();
        }
        return anomalyRateModel == null ? totalEventsFeatureOrder() : anomalyRateModel.featureOrder();
    }

    private double valueForFeature(String feature, LocalDate date) {
        if (feature.startsWith("total_events_lag_")) {
            int lag = Integer.parseInt(feature.substring("total_events_lag_".length()));
            return statisticsService.countEventsForDate(date.minusDays(lag));
        }
        if (feature.startsWith("anomaly_rate_lag_")) {
            int lag = Integer.parseInt(feature.substring("anomaly_rate_lag_".length()));
            return anomalyRate(date.minusDays(lag));
        }
        if ("total_events_roll_mean_7".equals(feature)) {
            return rollingEvents(date, true);
        }
        if ("total_events_roll_std_7".equals(feature)) {
            return rollingEvents(date, false);
        }
        if ("anomaly_rate_roll_mean_7".equals(feature)) {
            return rollingAnomalyRate(date, true);
        }
        if ("anomaly_rate_roll_std_7".equals(feature)) {
            return rollingAnomalyRate(date, false);
        }
        return switch (feature) {
            case "dow" -> date.getDayOfWeek().getValue() - 1;
            case "month" -> date.getMonthValue();
            case "week" -> date.get(WeekFields.of(Locale.ROOT).weekOfWeekBasedYear());
            case "day_sin" -> Math.sin(2.0 * Math.PI * date.getDayOfYear() / 365.0);
            case "day_cos" -> Math.cos(2.0 * Math.PI * date.getDayOfYear() / 365.0);
            case "dow_sin" -> Math.sin(2.0 * Math.PI * (date.getDayOfWeek().getValue() - 1) / 7.0);
            case "dow_cos" -> Math.cos(2.0 * Math.PI * (date.getDayOfWeek().getValue() - 1) / 7.0);
            case "is_weekend" -> date.getDayOfWeek().getValue() >= 6 ? 1.0 : 0.0;
            default -> 0.0;
        };
    }

    private double anomalyRate(LocalDate date) {
        long events = statisticsService.countEventsForDate(date);
        if (events == 0L) {
            return 0.0;
        }
        return (double) statisticsService.countAlertsForDate(date) / events;
    }

    private double rollingEvents(LocalDate date, boolean mean) {
        double[] values = new double[7];
        for (int i = 0; i < 7; i++) {
            values[i] = statisticsService.countEventsForDate(date.minusDays(i + 1));
        }
        return mean ? mean(values) : std(values);
    }

    private double rollingAnomalyRate(LocalDate date, boolean mean) {
        double[] values = new double[7];
        for (int i = 0; i < 7; i++) {
            values[i] = anomalyRate(date.minusDays(i + 1));
        }
        return mean ? mean(values) : std(values);
    }

    private double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return values.length == 0 ? 0.0 : sum / values.length;
    }

    private double std(double[] values) {
        double mean = mean(values);
        double sum = 0.0;
        for (double value : values) {
            double diff = value - mean;
            sum += diff * diff;
        }
        return values.length == 0 ? 0.0 : Math.sqrt(sum / values.length);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record RidgeModel(List<String> featureOrder,
                              double[] coef,
                              double intercept,
                              double[] mean,
                              double[] scale) {
        static RidgeModel from(JsonNode root) {
            return new RidgeModel(
                    stringList(root.path("feature_order")),
                    doubleArray(root.path("coef")),
                    root.path("intercept").asDouble(),
                    doubleArray(root.path("scaler").path("mean")),
                    doubleArray(root.path("scaler").path("scale")));
        }

        double predict(Map<String, Double> features) {
            double total = intercept;
            for (int i = 0; i < featureOrder.size() && i < coef.length; i++) {
                double value = features.getOrDefault(featureOrder.get(i), 0.0);
                double center = i < mean.length ? mean[i] : 0.0;
                double divisor = i < scale.length && scale[i] != 0.0 ? scale[i] : 1.0;
                total += coef[i] * ((value - center) / divisor);
            }
            return total;
        }

        private static List<String> stringList(JsonNode node) {
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                for (JsonNode item : node) {
                    values.add(item.asText());
                }
            }
            return values;
        }

        private static double[] doubleArray(JsonNode node) {
            if (node == null || !node.isArray()) {
                return new double[0];
            }
            double[] values = new double[node.size()];
            for (int i = 0; i < node.size(); i++) {
                values[i] = node.get(i).asDouble();
            }
            return values;
        }
    }
}
