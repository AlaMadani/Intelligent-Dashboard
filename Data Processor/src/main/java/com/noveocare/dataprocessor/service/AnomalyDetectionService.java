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
    private List<String> featureCols; // Remplace actionMap

    private String inputName;

    // 🚨 Le seuil calculé par l'entraînement Python
    private static final double ANOMALY_THRESHOLD = 0.02779;
    private static final int SEQUENCE_LENGTH = 5;
    private int NUM_FEATURES;

    // ⚠️ VALEURS DU SCALER PYTHON (À REMPLACER si besoin)
    // Pour reproduire exactement le MinMaxScaler de Python, il nous faut le Min et le Max
    // de la colonne time_delta. Généralement, min = 0.0
    private static final float TIME_SCALER_MIN = 0.0f;
    private static final float TIME_SCALER_MAX = 6657466.0f; // Max exact du notebook

    private static final Pattern HTTP_CODE_PATTERN = Pattern.compile("(?:Status:\\s*|HTTP\\s*)(\\d{3})");

    @PostConstruct
    public void init() throws Exception {
        // 1. Charger l'ordre strict des features généré par Pandas (One-Hot)
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream mapStream = new ClassPathResource("features_list.json").getInputStream()) {
            featureCols = mapper.readValue(mapStream, new TypeReference<List<String>>() {});
            NUM_FEATURES = featureCols.size(); // Devrait être 19
        }
        log.info("Loaded {} features from features_list.json", NUM_FEATURES);

        // 2. Initialiser l'environnement ONNX
        env = OrtEnvironment.getEnvironment();

        try (InputStream modelStream = new ClassPathResource("lstm_autoencoder_model.onnx").getInputStream()) {
            byte[] modelBytes = modelStream.readAllBytes();
            session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            inputName = session.getInputNames().iterator().next();
            log.info("✅ Modèle ONNX chargé. Entrée: {}, Shape attendu: [None, {}, {}]", inputName, SEQUENCE_LENGTH, NUM_FEATURES);
        }
    }

    /**
     * Analyse une séquence de 5 événements complets.
     */
    public AnomalyResult analyzeSequence(List<AuditTrailEvent> events) throws OrtException {
        if (events.size() != SEQUENCE_LENGTH) {
            log.warn("Sequence size mismatch! Expected {}, got {}", SEQUENCE_LENGTH, events.size());
            return new AnomalyResult(0.0, false, ANOMALY_THRESHOLD);
        }

        // 1. Préparer le tenseur 3D : [Batch=1][Temps=5][Features=19]
        float[][][] inputArray = new float[1][SEQUENCE_LENGTH][NUM_FEATURES];

        // 2. Preprocessing : Remplissage du tenseur
        for (int i = 0; i < SEQUENCE_LENGTH; i++) {
            AuditTrailEvent event = events.get(i);

            // A. Calcul et normalisation du time_delta
            float timeDeltaSeconds = 0.0f;
            if (i > 0) {
                timeDeltaSeconds = (float) Duration.between(events.get(i - 1).getCreatedAt(), event.getCreatedAt()).toMillis() / 1000.0f;
            }
            float normalizedTime = (timeDeltaSeconds - TIME_SCALER_MIN) / (TIME_SCALER_MAX - TIME_SCALER_MIN);
            normalizedTime = Math.max(0.0f, Math.min(1.0f, normalizedTime)); // Capping entre 0 et 1

            // B. Calcul du has_content
            float hasContent = (event.getContent() != null && !event.getContent().isNull()) ? 1.0f : 0.0f;

            // C. Calcul du has_object_id
            float hasObjectId = (event.getObjectId() != null && !event.getObjectId().isBlank()) ? 1.0f : 0.0f;

            // D. Reconstruction de la chaîne "action_normalized" (ex: LOGIN_SUCCESS)
            String action = event.getAction() != null ? event.getAction().toUpperCase() : "UNKNOWN";
            String status = Boolean.TRUE.equals(event.getSuccess()) ? "SUCCESS" : "FAILED";
            String currentActionNormalized = action + "_" + status; // Doit matcher ce que Pandas a généré !

            // E. Calcul du http_code (même logique que le notebook)
            String httpCode = extractHttpCode(event.getDetails());

            // F. Remplissage des 19 colonnes pour l'étape 'i'
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
                    // Si la colonne correspond à l'action en cours, on allume le bit (One-Hot)
                    inputArray[0][i][j] = 1.0f;
                } else {
                    // Sinon, c'est 0.0
                    inputArray[0][i][j] = 0.0f;
                }
            }
        }

        // 3. Inférence ONNX
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, inputArray)) {
            Map<String, OnnxTensor> inputs = Collections.singletonMap(inputName, tensor);

            try (OrtSession.Result results = session.run(inputs)) {
                float[][][] outputArray = (float[][][]) results.get(0).getValue();

                // 4. Calcul de l'erreur MSE sur toute la matrice (5x19)
                double mse = calculateMSE(inputArray[0], outputArray[0]);
                boolean isAnomaly = mse > ANOMALY_THRESHOLD;

                log.info("Inference complete. MSE = {}, isAnomaly = {}", mse, isAnomaly);
                return new AnomalyResult(mse, isAnomaly, ANOMALY_THRESHOLD);
            }
        }
    }

    private double calculateMSE(float[][] input, float[][] output) {
        double sumError = 0.0;
        // On compare chaque feature de chaque étape temporelle
        for (int i = 0; i < SEQUENCE_LENGTH; i++) {
            for (int j = 0; j < NUM_FEATURES; j++) {
                double diff = input[i][j] - output[i][j];
                sumError += diff * diff;
            }
        }
        // Moyenne totale : division par (5 * 19 = 95)
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

    // Classe interne pour structurer la réponse avec les getters pour le Kafka Consumer
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
