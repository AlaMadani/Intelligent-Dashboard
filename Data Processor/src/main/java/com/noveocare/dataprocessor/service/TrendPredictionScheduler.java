package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.ai.FeatureConfigLoader;
import com.noveocare.dataprocessor.ai.TrendFeatureColsLoader;
import com.noveocare.dataprocessor.ai.VocabService;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.TrendProperties;
import com.noveocare.dataprocessor.entity.ActionStatsDaily;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.ActionStatsDailyRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Periodically predicts next-day action volumes and flags actions that look
 * like significant spikes versus recent history.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TrendPredictionScheduler {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final TrendFeatureColsLoader trendFeatureColsLoader;
    private final FeatureConfigLoader featureConfigLoader;
    private final ActionStatsDailyRepository actionStatsDailyRepository;
    private final VocabService vocabService;
    private final StringRedisTemplate redisTemplate;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final TrendProperties trendProperties;

    private Booster booster;

    @PostConstruct
    public void loadModel() throws IOException, XGBoostError {
        // Load the serialized XGBoost booster once during startup.
        String path = properties.getBasePath() + properties.getModels().getTrendXgboost();
        Resource resource = resourceLoader.getResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            booster = XGBoost.loadModel(inputStream);
        }
        log.info("Loaded XGBoost model for trend prediction");
    }

    @Scheduled(cron = "${app.scheduling.trend-cron}")
    public void runDailyPrediction() {
        // Yesterday provides the last complete actual counts; tomorrow is the prediction target.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = today.minusDays(1);
        LocalDate predictionDate = today.plusDays(1);

        try {
            // Persist yesterday's final counts before producing any future forecast.
            upsertActualCounts(yesterday);
            Map<String, Object> trendSnapshot = new HashMap<>();

            int actionCount = featureConfigLoader.getFeatureConfig().getActionVocabSize();
            for (int actionId = 0; actionId < actionCount; actionId++) {
                // Generate the model prediction and compare it to a recent rolling baseline.
                double prediction = predictForAction(actionId, predictionDate);
                double rollingMean = rollingMean(actionId, predictionDate, 7);
                double rollingStd = rollingStd(actionId, predictionDate, 7);
                boolean spike = prediction > rollingMean + trendProperties.getSpikeSigma() * rollingStd;

                ActionStatsDaily record = actionStatsDailyRepository
                        .findByActionIdAndStatDate(actionId, predictionDate)
                        .orElseGet(ActionStatsDaily::new);
                record.setStatDate(predictionDate);
                record.setActionId(actionId);
                record.setActionLabel(vocabService.actionLabel(actionId));
                record.setPredictedCount(prediction);
                record.setRollingMean7(rollingMean);
                record.setRollingStd7(rollingStd);
                record.setSpikeAlert(spike);
                record.setCreatedAt(Instant.now());
                actionStatsDailyRepository.save(record);

                // Keep a compact Redis snapshot for external consumers that do not query SQL.
                Map<String, Object> entry = new HashMap<>();
                entry.put("predicted", prediction);
                entry.put("spike", spike);
                trendSnapshot.put(String.valueOf(actionId), entry);
            }

            redisCacheService.setJson(CacheKeys.trendStatsKey(predictionDate.toString()),
                    trendSnapshot, cacheProperties.getLiveStats());
        } catch (Exception e) {
            log.error("Trend prediction job failed", e);
        }
    }

    private void upsertActualCounts(LocalDate date) {
        // Copy the aggregated Redis counters into SQL so the trend model has durable history.
        String key = CacheKeys.dailyActionCountsKey(date.toString());
        Map<Object, Object> counts = redisTemplate.opsForHash().entries(key);
        if (counts == null || counts.isEmpty()) {
            return;
        }
        for (Map.Entry<Object, Object> entry : counts.entrySet()) {
            int actionId = Integer.parseInt(entry.getKey().toString());
            long count = Long.parseLong(entry.getValue().toString());
            ActionStatsDaily record = actionStatsDailyRepository
                    .findByActionIdAndStatDate(actionId, date)
                    .orElseGet(ActionStatsDaily::new);
            record.setStatDate(date);
            record.setActionId(actionId);
            record.setActionLabel(vocabService.actionLabel(actionId));
            record.setActualCount(count);
            record.setCreatedAt(Instant.now());
            actionStatsDailyRepository.save(record);
        }
    }

    private double predictForAction(int actionId, LocalDate predictionDate) throws XGBoostError {
        // XGBoost expects a single-row matrix containing all engineered trend features.
        float[] features = buildTrendFeatures(actionId, predictionDate);
        DMatrix matrix = new DMatrix(features, 1, features.length, Float.NaN);
        float[][] prediction = booster.predict(matrix);
        return prediction[0][0];
    }

    private float[] buildTrendFeatures(int actionId, LocalDate predictionDate) {
        // Build the exact ordered feature vector used during trend-model training.
        List<String> cols = trendFeatureColsLoader.getFeatureColumns();
        Map<String, Float> values = new HashMap<>();

        int dayOfWeek = (predictionDate.getDayOfWeek().getValue() + 6) % 7;
        values.put("action_id", (float) actionId);
        values.put("dayofweek", (float) dayOfWeek);
        values.put("month_num", (float) predictionDate.getMonthValue());
        values.put("day", (float) predictionDate.getDayOfMonth());
        values.put("is_weekend", dayOfWeek >= 5 ? 1.0f : 0.0f);

        values.put("lag_1", (float) lagValue(actionId, predictionDate, 1));
        values.put("lag_2", (float) lagValue(actionId, predictionDate, 2));
        values.put("lag_3", (float) lagValue(actionId, predictionDate, 3));
        values.put("lag_7", (float) lagValue(actionId, predictionDate, 7));
        values.put("lag_14", (float) lagValue(actionId, predictionDate, 14));
        values.put("lag_30", (float) lagValue(actionId, predictionDate, 30));
        values.put("rolling_mean_7", (float) rollingMean(actionId, predictionDate, 7));
        values.put("rolling_std_7", (float) rollingStd(actionId, predictionDate, 7));
        values.put("rolling_mean_30", (float) rollingMean(actionId, predictionDate, 30));

        // Project the named feature map onto the model's required column order.
        float[] features = new float[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            features[i] = values.getOrDefault(cols.get(i), 0.0f);
        }
        return features;
    }

    private double lagValue(int actionId, LocalDate predictionDate, int lag) {
        // Missing history is treated as zero volume to keep inference resilient.
        LocalDate target = predictionDate.minusDays(lag);
        Optional<ActionStatsDaily> record = actionStatsDailyRepository.findByActionIdAndStatDate(actionId, target);
        return record.map(ActionStatsDaily::getActualCount).orElse(0L);
    }

    private double rollingMean(int actionId, LocalDate predictionDate, int days) {
        // Use only fully observed days before the prediction target.
        LocalDate start = predictionDate.minusDays(days);
        LocalDate end = predictionDate.minusDays(1);
        List<ActionStatsDaily> history = actionStatsDailyRepository
                .findByActionIdAndStatDateBetweenOrderByStatDateAsc(actionId, start, end);
        if (history.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (ActionStatsDaily record : history) {
            sum += record.getActualCount() == null ? 0.0 : record.getActualCount();
        }
        return sum / history.size();
    }

    private double rollingStd(int actionId, LocalDate predictionDate, int days) {
        // Compute volatility over the same history window used by the rolling mean.
        LocalDate start = predictionDate.minusDays(days);
        LocalDate end = predictionDate.minusDays(1);
        List<ActionStatsDaily> history = actionStatsDailyRepository
                .findByActionIdAndStatDateBetweenOrderByStatDateAsc(actionId, start, end);
        if (history.isEmpty()) {
            return 0.0;
        }

        // Derive the mean from the already loaded history to avoid a second repository round-trip.
        double sum = 0.0;
        for (ActionStatsDaily record : history) {
            sum += record.getActualCount() == null ? 0.0 : record.getActualCount();
        }
        double mean = sum / history.size();
        
        double sumSq = 0.0;
        for (ActionStatsDaily record : history) {
            double value = record.getActualCount() == null ? 0.0 : record.getActualCount();
            double diff = value - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / history.size());
    }
}
