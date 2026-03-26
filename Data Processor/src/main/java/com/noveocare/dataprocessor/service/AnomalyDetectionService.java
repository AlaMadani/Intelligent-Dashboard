package com.noveocare.dataprocessor.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AnomalyDetectionService {

    private OrtEnvironment env;
    private OrtSession session;
    // Feature column order from the training pipeline.
    private List<String> featureCols;

    private String inputName;

    // Threshold computed during the Python training phase.
    private static final double ANOMALY_THRESHOLD = 0.02779;
    private static final int SEQUENCE_LENGTH = 5;
    private int NUM_FEATURES;

    // MinMaxScaler bounds for the time_delta feature.
    private static final float TIME_SCALER_MIN = 0.0f;
    private static final float TIME_SCALER_MAX = 6657466.0f; // Max observed during training.

    private static final Pattern HTTP_CODE_PATTERN = Pattern.compile("(?:Status:\\s*|HTTP\\s*)(\\d{3})");

    @PostConstruct
    public void init() throws Exception {
        // Load the feature order produced by the training pipeline.
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream mapStream = new ClassPathResource("features_list.json").getInputStream()) {
            featureCols = mapper.readValue(mapStream, new TypeReference<List<String>>() {});
            NUM_FEATURES = featureCols.size(); // Expected feature count from training.
        }
        log.info("Loaded {} features from features_list.json", NUM_FEATURES);

        // Initialize the ONNX runtime.
        env = OrtEnvironment.getEnvironment();

        try (InputStream modelStream = new ClassPathResource("lstm_autoencoder_model.onnx").getInputStream()) {
            byte[] modelBytes = modelStream.readAllBytes();
            session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            inputName = session.getInputNames().iterator().next();
            log.info("ONNX model loaded. Input: {}, expected shape: [None, {}, {}]", inputName, SEQUENCE_LENGTH, NUM_FEATURES);
        }
    }

    /**
     * Analyze a fixed-length sequence of audit events.
     */
    public AnomalyResult analyzeSequence(List<AuditTrailEvent> events) throws OrtException {
        if (events.size() != SEQUENCE_LENGTH) {
            log.warn("Sequence size mismatch! Expected {}, got {}", SEQUENCE_LENGTH, events.size());
            return new AnomalyResult(0.0, false, ANOMALY_THRESHOLD);
        }

        // Build the 3D input tensor: [batch=1][time=5][features=N].
        float[][][] inputArray = new float[1][SEQUENCE_LENGTH][NUM_FEATURES];

        // Preprocess each event into the feature vector.
        for (int i = 0; i < SEQUENCE_LENGTH; i++) {
            AuditTrailEvent event = events.get(i);

            // A) Compute normalized time_delta.
            float timeDeltaSeconds = 0.0f;
            if (i > 0) {
                timeDeltaSeconds = (float) Duration.between(events.get(i - 1).getCreatedAt(), event.getCreatedAt()).toMillis() / 1000.0f;
            }
            float normalizedTime = (timeDeltaSeconds - TIME_SCALER_MIN) / (TIME_SCALER_MAX - TIME_SCALER_MIN);
            normalizedTime = Math.max(0.0f, Math.min(1.0f, normalizedTime)); // Clamp to [0, 1].

            // B) Feature flags derived from event content.
            float hasContent = (event.getContent() != null && !event.getContent().isNull()) ? 1.0f : 0.0f;

            float hasObjectId = (event.getObjectId() != null && !event.getObjectId().isBlank()) ? 1.0f : 0.0f;

            // C) Compose the normalized action key (e.g. LOGIN_SUCCESS).
            String action = event.getAction() != null ? event.getAction().toUpperCase() : "UNKNOWN";
            String status = Boolean.TRUE.equals(event.getSuccess()) ? "SUCCESS" : "FAILED";
            String currentActionNormalized = action + "_" + status;

            // D) Extract HTTP code feature.
            String httpCode = extractHttpCode(event.getDetails());

            // E) Fill the one-hot feature vector for this time step.
            for (int j = 0; j < NUM_FEATURES; j++) {
                String featureName = featureCols.get(j);

                if ("time_delta".equals(featureName)) {
                    inputArray[0][i][j] = normalizedTime;
                } else if ("has_object_id".equals(featureName)) {
                    inputArray[0][i][j] = hasObjectId;
                } else if ("has_content".equals(featureName)) {
                    inputArray[0][i][j] = hasContent;
                } else if (featureName.startsWith("http_code_") && featureName.endsWith(httpCode)) {
                    inputArray[0][i][j] = 1.0f;
                } else if (featureName.endsWith(currentActionNormalized)) {
                    inputArray[0][i][j] = 1.0f;
                } else {
                    inputArray[0][i][j] = 0.0f;
                }
            }
        }

        // Run ONNX inference and compute reconstruction error.
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, inputArray)) {
            Map<String, OnnxTensor> inputs = Collections.singletonMap(inputName, tensor);

            try (OrtSession.Result results = session.run(inputs)) {
                float[][][] outputArray = (float[][][]) results.get(0).getValue();

                // Compute MSE across the full time-feature matrix.
                double mse = calculateMSE(inputArray[0], outputArray[0]);
                boolean isAnomaly = mse > ANOMALY_THRESHOLD;

                log.info("Inference complete. MSE = {}, isAnomaly = {}", mse, isAnomaly);
                return new AnomalyResult(mse, isAnomaly, ANOMALY_THRESHOLD);
            }
        }
    }

    private double calculateMSE(float[][] input, float[][] output) {
        double sumError = 0.0;
        // Compare each feature across each time step.
        for (int i = 0; i < SEQUENCE_LENGTH; i++) {
            for (int j = 0; j < NUM_FEATURES; j++) {
                double diff = input[i][j] - output[i][j];
                sumError += diff * diff;
            }
        }
        // Mean over all entries.
        return sumError / (SEQUENCE_LENGTH * NUM_FEATURES);
    }

    private static String extractHttpCode(String details) {
        if (details == null || "UNKNOWN".equals(details)) {
            return "UNKNOWN";
        }
        Matcher matcher = HTTP_CODE_PATTERN.matcher(details);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (details.toLowerCase().contains("success")) {
            return "200";
        }
        return "OTHER";
    }

    @PreDestroy
    public void close() throws OrtException {
        if (session != null) session.close();
        if (env != null) env.close();
    }

    // Result object consumed by the Kafka pipeline.
    public static class AnomalyResult {
        private final double mseScore;
        private final boolean isAnomaly;
        private final double thresholdUsed;

        public AnomalyResult(double mseScore, boolean isAnomaly, double thresholdUsed) {
            this.mseScore = mseScore;
            this.isAnomaly = isAnomaly;
            this.thresholdUsed = thresholdUsed;
        }

        public double getMseScore() { return mseScore; }
        public boolean isAnomaly() { return isAnomaly; }
        public double getThresholdUsed() { return thresholdUsed; }
    }
}
