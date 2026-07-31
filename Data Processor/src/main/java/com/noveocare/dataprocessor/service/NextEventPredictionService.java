package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.artifact.CategoricalVocabularies;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.artifact.SequenceMetadata;
import com.noveocare.dataprocessor.ai.sequence.SequenceInferenceResult;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.NextEventPredictionProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.NextEventPredictionDeviation;
import com.noveocare.dataprocessor.dto.NextEventPredictionHeadScore;
import com.noveocare.dataprocessor.dto.NextEventPredictionResult;
import com.noveocare.dataprocessor.entity.NextEventPrediction;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.NextEventPredictionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Predicts the next categorical field values (action, page, etc.) based on sequence
 * model logits. Evaluates deviation of actual events against previous predictions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NextEventPredictionService {

    private static final String SCHEMA_VERSION = "v3.6.1";

    /* Injected dependencies */
    private final NextEventPredictionProperties properties;
    private final RuntimeArtifactService artifactService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties redisCacheProperties;
    private final NextEventPredictionRepository repository;
    private final ObjectMapper objectMapper;

    /* Prediction and deviation metrics */
    private final AtomicLong generatedTotal = new AtomicLong();
    private final AtomicLong skippedInsufficientContextTotal = new AtomicLong();
    private final AtomicLong skippedOutputUnavailableTotal = new AtomicLong();
    private final AtomicLong deviationEvaluatedTotal = new AtomicLong();
    private final AtomicLong deviationMatchedTopKTotal = new AtomicLong();
    private final AtomicLong deviationHighScoreTotal = new AtomicLong();
    private final AtomicLong deviationSkippedNoPreviousPredictionTotal = new AtomicLong();
    private final AtomicLong deviationSkippedSameContextEventIdTotal = new AtomicLong();
    private volatile Instant lastPredictionAt;
    private volatile Instant lastDeviationAt;
    private volatile String lastError;
    private volatile String lastDeviationError;
    private volatile String lastSkippedSameContextEventId;

    /* Head-name to index mapping built from sequence metadata */
    private Map<String, Integer> headNameToIndex;

    /* --- Initialization --- */

    @PostConstruct
    public void init() {
        SequenceMetadata meta = artifactService.getSequenceMetadata();
        List<String> catCols = meta.getCatCols();
        headNameToIndex = new LinkedHashMap<>();
        for (int i = 0; i < catCols.size(); i++) {
            headNameToIndex.put(catCols.get(i), i);
        }
    }

    /* --- Public API --- */

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void predict(SequenceInferenceResult inference, String insuredId, String sessionId,
                        String contextEventId, int contextSize, AuditTrailEvent currentEvent) {
        if (!properties.isEnabled()) {
            return;
        }
        if (inference == null || inference.getCategoricalLogits() == null) {
            skippedOutputUnavailableTotal.incrementAndGet();
            return;
        }
        if (contextSize < properties.getMinContextEvents()) {
            skippedInsufficientContextTotal.incrementAndGet();
            return;
        }

        try {
            doPredict(inference, insuredId, sessionId, contextEventId, contextSize, currentEvent);
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Next-event prediction failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /* --- Internal prediction logic --- */

    private void doPredict(SequenceInferenceResult inference, String insuredId, String sessionId,
                           String contextEventId, int contextSize, AuditTrailEvent currentEvent) {
        List<String> catCols = artifactService.getSequenceMetadata().getCatCols();
        CategoricalVocabularies vocab = artifactService.getCategoricalVocabularies();

        List<String> configuredHeads = properties.getHeads();
        int topK = properties.getTopK();
        Map<String, List<NextEventPredictionHeadScore.PredictedValue>> heads = new LinkedHashMap<>();
        List<NextEventPredictionHeadScore> headScores = new ArrayList<>();

        for (int headIdx = 0; headIdx < inference.getCategoricalLogits().size() && headIdx < catCols.size(); headIdx++) {
            String fieldName = catCols.get(headIdx);
            if (!configuredHeads.contains(fieldName)) {
                continue;
            }
            float[] logits = inference.getCategoricalLogits().get(headIdx);
            if (logits == null || logits.length == 0) {
                continue;
            }
            Map<String, String> reverseMap = vocab.getReverseInputIdMaps().getOrDefault(fieldName, Map.of());
            List<NextEventPredictionHeadScore.PredictedValue> topKList = decodeTopK(logits, reverseMap, topK);
            heads.put(fieldName, topKList);
            headScores.add(new NextEventPredictionHeadScore.HeadScoreBuilder()
                    .headName(fieldName)
                    .topK(topKList)
                    .build());
        }

        if (heads.isEmpty()) {
            skippedOutputUnavailableTotal.incrementAndGet();
            return;
        }

        Instant now = Instant.now();
        Map<String, Object> deviation = null;

        if (properties.isEvaluateDeviation() && currentEvent != null) {
            try {
                deviation = computeDeviation(sessionId, contextEventId, currentEvent);
            } catch (Exception e) {
                lastDeviationError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("Deviation computation failed for session {}: {}", sessionId, e.getMessage());
            }
        }

        NextEventPredictionResult result = new NextEventPredictionResult.ResultBuilder()
                .schemaVersion(SCHEMA_VERSION)
                .insuredId(insuredId)
                .sessionId(sessionId)
                .contextEventId(contextEventId)
                .contextSize(contextSize)
                .model(inference.getModelKind() == null ? "transformer" : inference.getModelKind().name().toLowerCase())
                .modelArtifact(inference.getModelArtifact())
                .heads(heads)
                .headScores(headScores)
                .createdAt(now)
                .deviation(deviation)
                .build();

        persist(result, now);
        generatedTotal.incrementAndGet();
        lastPredictionAt = now;
    }

    /* Computes deviation between the previous prediction and the actual event */
    private Map<String, Object> computeDeviation(String sessionId, String contextEventId,
                                                  AuditTrailEvent currentEvent) {
        NextEventPredictionResult prediction = loadLatestPrediction(sessionId);
        if (prediction == null) {
            deviationSkippedNoPreviousPredictionTotal.incrementAndGet();
            return null;
        }

        if (Objects.equals(prediction.getContextEventId(), contextEventId)) {
            deviationSkippedSameContextEventIdTotal.incrementAndGet();
            lastSkippedSameContextEventId = contextEventId;
            lastDeviationError = "previous_prediction_same_context_event";
            log.warn("Deviation skipped for session {}: previous prediction has same contextEventId={}",
                    sessionId, contextEventId);
            return null;
        }

        Map<String, String> actualValues = buildActualValues(currentEvent);
        if (actualValues.isEmpty()) {
            deviationSkippedNoPreviousPredictionTotal.incrementAndGet();
            return null;
        }

        Map<String, Boolean> predictionMatch = new LinkedHashMap<>();
        Map<String, Double> actualProbabilities = new LinkedHashMap<>();
        double totalDeviation = 0.0;
        int count = 0;
        boolean anyMatched = false;

        for (Map.Entry<String, String> actual : actualValues.entrySet()) {
            String field = actual.getKey();
            String actualValue = actual.getValue();
            if (actualValue == null) {
                continue;
            }
            List<NextEventPredictionHeadScore.PredictedValue> predictedList = prediction.getHeads().get(field);
            if (predictedList == null || predictedList.isEmpty()) {
                continue;
            }
            boolean inTopK = predictedList.stream().anyMatch(pv -> actualValue.equals(pv.getValue()));
            predictionMatch.put(field + "TopK", inTopK);
            if (inTopK) {
                anyMatched = true;
            }

            double actualProb = predictedList.stream()
                    .filter(pv -> actualValue.equals(pv.getValue()))
                    .findFirst()
                    .map(NextEventPredictionHeadScore.PredictedValue::getProbability)
                    .orElse(0.0);
            if (actualProb == 0.0) {
                double minProb = predictedList.stream()
                        .mapToDouble(NextEventPredictionHeadScore.PredictedValue::getProbability)
                        .min().orElse(0.0);
                actualProb = minProb > 0 ? minProb * 0.1 : 0.001;
            }
            actualProbabilities.put(field, actualProb);
            totalDeviation += 1.0 - actualProb;
            count++;
        }

        double deviationScore = count > 0 ? totalDeviation / count : 1.0;

        deviationEvaluatedTotal.incrementAndGet();
        if (anyMatched) {
            deviationMatchedTopKTotal.incrementAndGet();
        }
        if (deviationScore > 0.8) {
            deviationHighScoreTotal.incrementAndGet();
        }
        lastDeviationAt = Instant.now();

        Map<String, Object> previousPrediction = new LinkedHashMap<>();
        previousPrediction.put("contextEventId", prediction.getContextEventId());
        previousPrediction.put("contextSize", prediction.getContextSize());
        previousPrediction.put("model", prediction.getModel());
        previousPrediction.put("heads", prediction.getHeads());
        previousPrediction.put("createdAt", prediction.getCreatedAt() != null ? prediction.getCreatedAt().toString() : null);

        Map<String, Object> deviation = new LinkedHashMap<>();
        deviation.put("previousPrediction", previousPrediction);
        deviation.put("actual", actualValues);
        deviation.put("predictionMatch", predictionMatch);
        deviation.put("actualProbabilities", actualProbabilities);
        deviation.put("deviationScore", deviationScore);
        deviation.put("previousPredictionContextEventId", prediction.getContextEventId());
        deviation.put("evaluatedEventId", contextEventId);
        deviation.put("evaluatedAgainst", prediction.getContextEventId());
        deviation.put("evaluatedAgainstCreatedAt", prediction.getCreatedAt() != null ? prediction.getCreatedAt().toString() : null);
        return deviation;
    }

    /* Evaluates deviation for the current event against the latest prediction */
    public Map<String, Object> evaluateDeviation(AuditTrailEvent currentEvent, String sessionId) {
        if (!properties.isEnabled() || !properties.isEvaluateDeviation()) {
            return null;
        }
        return computeDeviation(sessionId, currentEvent.getId(), currentEvent);
    }

    /* Extracts relevant field values from the event for deviation comparison */
    private Map<String, String> buildActualValues(AuditTrailEvent event) {
        Map<String, String> actual = new LinkedHashMap<>();
        putIfNotNull(actual, "frontend_action_name", event.getFrontendActionName());
        putIfNotNull(actual, "action_value", event.getActionValue());
        putIfNotNull(actual, "page", event.getPage());
        putIfNotNull(actual, "api_template", event.getApiTemplate());
        putIfNotNull(actual, "api_family", event.getApiFamily());
        putIfNotNull(actual, "http_method", event.getHttpMethod());
        putIfNotNull(actual, "status", event.getStatus());
        return actual;
    }

    /* Puts the value into the map only if non-null */
    private void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /* Decodes the top-K predicted values from logits using the reverse vocabulary map */
    private List<NextEventPredictionHeadScore.PredictedValue> decodeTopK(float[] logits, Map<String, String> reverseMap, int topK) {
        int k = Math.min(topK, logits.length);

        List<Integer> sortedIndices = new ArrayList<>();
        for (int i = 0; i < logits.length; i++) {
            sortedIndices.add(i);
        }
        sortedIndices.sort((a, b) -> Float.compare(logits[b], logits[a]));

        double[] probs = softmax(logits);

        List<NextEventPredictionHeadScore.PredictedValue> result = new ArrayList<>(k);
        for (int rank = 0; rank < k && rank < sortedIndices.size(); rank++) {
            int idx = sortedIndices.get(rank);
            String rawKey = String.valueOf(idx);
            String value = reverseMap.getOrDefault(rawKey, "UNK_" + idx);
            result.add(new NextEventPredictionHeadScore.PredictedValue.PredictedValueBuilder()
                    .value(value)
                    .probability(probs[idx])
                    .rank(rank + 1)
                    .build());
        }
        return result;
    }

    /* Computes softmax probabilities from raw logits */
    private double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float v : logits) {
            max = Math.max(max, v);
        }
        double sum = 0.0;
        double[] probs = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probs[i] = Math.exp(logits[i] - max);
            sum += probs[i];
        }
        if (sum > 0) {
            for (int i = 0; i < logits.length; i++) {
                probs[i] /= sum;
            }
        }
        return probs;
    }

    /* Persists prediction to Redis and/or SQL based on configuration */
    private void persist(NextEventPredictionResult result, Instant now) {
        try {
            String json = objectMapper.writeValueAsString(result);

            if (properties.isWriteRedis()) {
                redisCacheService.setJson(
                        CacheKeys.nextEventPredictionSessionKey(result.getSessionId()),
                        json,
                        redisCacheProperties.getSessionInsight());
                redisCacheService.setJson(
                        CacheKeys.nextEventPredictionInsuredKey(result.getInsuredId()),
                        json,
                        redisCacheProperties.getSessionInsight());
            }

            if (properties.isWriteSql()) {
                NextEventPrediction entity = new NextEventPrediction();
                entity.setInsuredId(result.getInsuredId());
                entity.setSessionId(result.getSessionId());
                entity.setContextEventId(result.getContextEventId());
                entity.setContextSize(result.getContextSize());
                entity.setModelName(result.getModel());
                entity.setPredictionsJson(json);
                entity.setCreatedAt(now);
                repository.save(entity);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize next-event prediction for session {}", result.getSessionId(), e);
        }
    }

    /* Loads prediction from Redis cache or SQL fallback */
    public NextEventPredictionResult loadPrediction(String sessionId, String contextEventId) {
        String redisKey = CacheKeys.nextEventPredictionSessionKey(sessionId);
        String cached = redisCacheService.getJson(redisKey, String.class);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, NextEventPredictionResult.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached prediction for session {}", sessionId, e);
            }
        }
        return repository.findBySessionIdAndContextEventId(sessionId, contextEventId)
                .map(entity -> {
                    try {
                        return objectMapper.readValue(entity.getPredictionsJson(), NextEventPredictionResult.class);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize SQL prediction for session {}", sessionId, e);
                        return null;
                    }
                })
                .orElse(null);
    }

    /* Loads the latest prediction for a session from Redis only */
    private NextEventPredictionResult loadLatestPrediction(String sessionId) {
        String redisKey = CacheKeys.nextEventPredictionSessionKey(sessionId);
        String cached = redisCacheService.getJson(redisKey, String.class);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, NextEventPredictionResult.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached prediction for session {}", sessionId, e);
            }
        }
        return null;
    }

    /* --- Diagnostics --- */

    public Map<String, Object> diagnosticsSnapshot() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled", properties.isEnabled());
        d.put("model", properties.getModelPreference());
        d.put("topK", properties.getTopK());
        d.put("minContextEvents", properties.getMinContextEvents());
        d.put("heads", properties.getHeads());
        d.put("affectRiskScore", properties.isAffectRiskScore());
        d.put("generatedTotal", generatedTotal.get());
        d.put("skippedInsufficientContextTotal", skippedInsufficientContextTotal.get());
        d.put("skippedOutputUnavailableTotal", skippedOutputUnavailableTotal.get());
        d.put("deviationEvaluatedTotal", deviationEvaluatedTotal.get());
        d.put("deviationMatchedTopKTotal", deviationMatchedTopKTotal.get());
        d.put("deviationHighScoreTotal", deviationHighScoreTotal.get());
        d.put("deviationSkippedNoPreviousPredictionTotal", deviationSkippedNoPreviousPredictionTotal.get());
        d.put("deviationSkippedSameContextEventIdTotal", deviationSkippedSameContextEventIdTotal.get());
        d.put("lastPredictionAt", lastPredictionAt == null ? null : lastPredictionAt.toString());
        d.put("lastDeviationAt", lastDeviationAt == null ? null : lastDeviationAt.toString());
        d.put("lastError", lastError);
        d.put("lastDeviationError", lastDeviationError);
        d.put("lastSkippedSameContextEventId", lastSkippedSameContextEventId);
        return d;
    }
}
