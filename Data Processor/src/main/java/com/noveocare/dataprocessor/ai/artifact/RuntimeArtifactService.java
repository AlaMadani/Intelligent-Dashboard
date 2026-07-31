package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Central service for discovering, loading, and providing access to all AI
 * runtime artifacts (models, configs, metadata). Validates the required
 * resource contract at startup.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RuntimeArtifactService {

    /* ---- Artifact path constants ---- */
    public static final String MANIFEST = "MANIFEST_v3_6.json";
    public static final String DEPLOYMENT_MANIFEST = "deployment_manifest_v3_6.json";

    public static final String TRANSFORMER_MODEL = "sequence/transformer_sequence_engine.onnx";
    public static final String TCN_MODEL = "sequence/tcn_sequence_engine.onnx";
    public static final String WINNING_SEQUENCE_MODEL = "sequence/winning_sequence_engine.onnx";

    public static final String TABULAR_XGBOOST_JSON = "tabular_anomaly/anomaly_xgboost.json";
    public static final String TABULAR_XGBOOST_UBJ = "tabular_anomaly/anomaly_xgboost.ubj";
    public static final String TABULAR_LIGHTGBM = "tabular_anomaly/anomaly_lightgbm.txt";
    public static final String TABULAR_CATBOOST = "tabular_anomaly/anomaly_catboost.cbm";
    public static final String TABULAR_ONECLASS_SVM = "tabular_anomaly/anomaly_oneclasssvm.json";
    public static final String TABULAR_RUNTIME_MANIFEST = "tabular_anomaly/tabular_anomaly_runtime_manifest.json";
    public static final String TABULAR_MODEL_SCALER = "tabular_anomaly/tabular_feature_scaler.json";

    public static final String CHURN_MODEL = "churn/churn_profile_only_ExtraTrees.json";
    public static final String FORECAST_ANOMALY_RATE_RIDGE = "forecast/macro_forecaster_anomaly_rate_Ridge.json";
    public static final String FORECAST_TOTAL_EVENTS_XGBOOST_JSON = "forecast/macro_forecaster_total_events_XGBoost.json";
    public static final String FORECAST_TOTAL_EVENTS_XGBOOST_UBJ = "forecast/macro_forecaster_total_events_XGBoost.ubj";
    public static final String FORECAST_TOTAL_EVENTS_CONTRACT = "forecast/macro_forecaster_total_events_XGBoost_feature_contract.json";
    public static final String PERSONA_MODEL = "persona/persona_runtime_best.json";

    /* ---- Expected column contracts for validation ---- */
    private static final List<String> EXPECTED_CAT_COLS = List.of(
            "page", "frontend_action_name", "api_template", "action_value", "action_type",
            "action_subtype", "http_method", "status", "device", "browser", "os", "ip_country",
            "controller", "api_family", "environment_id");

    private static final List<String> EXPECTED_CONT_COLS = List.of(
            "time_since_prev_action_ms", "request_data_size_bytes", "response_data_size_bytes",
            "is_business_hours", "is_weekend", "hour_sin", "hour_cos", "dow_sin", "dow_cos");

    /* ---- Dependencies ---- */
    private final AiResourceProperties properties;
    private final AiPersonaProperties personaProperties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    /* ---- Loaded artifacts (populated by load()) ---- */
    @Getter
    private RuntimeArtifactManifest manifest;
    @Getter
    private SequenceMetadata sequenceMetadata;
    @Getter
    private CategoricalVocabularies categoricalVocabularies;
    @Getter
    private ScalerParams scalerParams;
    @Getter
    private AnomalyScoreConfig anomalyScoreConfig;
    @Getter
    private PersonaRuntimeKmeansConfig personaRuntimeKmeansConfig;
    @Getter
    private ChurnFeatureSchema churnFeatureSchema;
    @Getter
    private ForecastConfig forecastConfig;
    @Getter
    private RuntimeArtifactHealth artifactHealth;

    /* ========== Initialisation ========== */

    /* Scans and validates all required resources; throws on missing mandatory artifacts. */
    @PostConstruct
    public void load() throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        requireResource(MANIFEST, missing);
        requireResource(DEPLOYMENT_MANIFEST, missing);
        manifest = readJson(MANIFEST, RuntimeArtifactManifest.class);

        validateMandatoryConfig("sequence_metadata.json", missing);
        validateMandatoryConfig("categorical_vocabularies.json", missing);
        validateMandatoryConfig("scaler_params.json", missing);
        validateMandatoryConfig("anomaly_score_config.json", missing);
        validateMandatoryConfig("tabular_anomaly_feature_contract.json", missing);
        validateMandatoryConfig("tabular_feature_contract.json", missing);
        validateMandatoryConfig("tabular_feature_scaler.json", missing);
        validateMandatoryConfig("risk_fusion_config.json", missing);
        validateMandatoryConfig("dashboard_payload_contract.json", missing);
        validateMandatoryConfig("llm_explanation_config.json", missing);

        requireModel(TRANSFORMER_MODEL, missing);
        requireModel(TCN_MODEL, missing);
        requireModel(TABULAR_RUNTIME_MANIFEST, missing);
        requireModel(TABULAR_MODEL_SCALER, missing);

        if (!missing.isEmpty()) {
            artifactHealth = buildHealth(missing, warnings);
            throw new IllegalStateException("Missing mandatory V3.6.1 AI artifacts: " + missing);
        }

        sequenceMetadata = readConfig("sequence_metadata.json", SequenceMetadata.class);
        categoricalVocabularies = readConfig("categorical_vocabularies.json", CategoricalVocabularies.class);
        scalerParams = readConfig("scaler_params.json", ScalerParams.class);
        anomalyScoreConfig = readConfig("anomaly_score_config.json", AnomalyScoreConfig.class);
        if (resourceExists(properties.getConfigPath() + "churn_profile_feature_schema.json")) {
            churnFeatureSchema = readConfig("churn_profile_feature_schema.json", ChurnFeatureSchema.class);
        } else {
            churnFeatureSchema = new ChurnFeatureSchema();
            warnings.add("churn_schema_missing");
        }
        if (resourceExists(properties.getConfigPath() + "forecast_config.json")) {
            forecastConfig = readConfig("forecast_config.json", ForecastConfig.class);
        } else {
            forecastConfig = new ForecastConfig();
            warnings.add("forecast_config_missing");
        }

        validateSequenceContract();
        optionalModel(TABULAR_XGBOOST_JSON, "xgboost_anomaly_json_missing", warnings);
        optionalModel(TABULAR_XGBOOST_UBJ, "xgboost_anomaly_ubj_missing", warnings);
        optionalModel(TABULAR_LIGHTGBM, "lightgbm_alert_model_missing", warnings);
        optionalModel(TABULAR_CATBOOST, "catboost_anomaly_model_missing", warnings);
        optionalModel(TABULAR_ONECLASS_SVM, "oneclasssvm_model_missing", warnings);
        optionalConfig("churn_profile_feature_schema.json", "churn_schema_missing", warnings);
        optionalConfig("churn_runtime_config.json", "churn_config_missing", warnings);
        optionalModel(CHURN_MODEL, "churn_extratrees_model_missing", warnings);
        optionalConfig("forecast_config.json", "forecast_config_missing", warnings);
        optionalModel(FORECAST_ANOMALY_RATE_RIDGE, "forecast_ridge_model_missing", warnings);
        optionalModel(FORECAST_TOTAL_EVENTS_XGBOOST_JSON, "forecast_xgboost_json_missing", warnings);
        optionalModel(FORECAST_TOTAL_EVENTS_XGBOOST_UBJ, "forecast_xgboost_ubj_missing", warnings);
        optionalModel(FORECAST_TOTAL_EVENTS_CONTRACT, "forecast_xgboost_feature_contract_missing", warnings);

        personaRuntimeKmeansConfig = null;
        if (modelExists(PERSONA_MODEL)) {
            warnings.add("persona_artifact_present_but_runtime_disabled");
        }
        if (resourceExists("models/persona/behavioral_embeddings.npz")) {
            warnings.add("persona_npz_ignored_not_java_runtime_artifact");
        }

        artifactHealth = buildHealth(List.of(), warnings);

        log.info("Loaded V3.6.1 AI artifacts: sequence_metadata.json catCols={} contCols={} windowSize={}",
                sequenceMetadata.getCatCols().size(),
                sequenceMetadata.getContCols().size(),
                sequenceMetadata.getWindowSize());
        log.info("Runtime artifact health warnings={}", warnings);
    }

    /* ========== Resource access ========== */

    public Resource resource(String relativePath) {
        return resourceLoader.getResource(properties.getBasePath() + relativePath);
    }

    /* Builds a resource handle for a model artifact. */
    public Resource modelResource(String modelName) {
        return resource(properties.getModelsPath() + modelName);
    }

    /* Builds a resource handle for a config artifact. */
    public Resource configResource(String configName) {
        return resource(properties.getConfigPath() + configName);
    }

    /* Checks if a relative resource exists. */
    public boolean resourceExists(String relativePath) {
        return relativePath != null && !relativePath.isBlank() && resource(relativePath).exists();
    }

    /* Checks if a model artifact exists. */
    public boolean modelExists(String modelName) {
        return modelName != null && !modelName.isBlank() && modelResource(modelName).exists();
    }

    /* Reads the entire model file into a byte array. */
    public byte[] readModelBytes(String modelName) throws IOException {
        try (InputStream inputStream = modelResource(modelName).getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    /* Copies a model to a temp file (deleted on JVM exit). */
    public Path copyModelToTempFile(String modelName) throws IOException {
        String suffix = modelName.contains(".") ? modelName.substring(modelName.lastIndexOf('.')) : ".model";
        Path temp = Files.createTempFile("dataprocessor-ai-", suffix);
        try (InputStream inputStream = modelResource(modelName).getInputStream()) {
            Files.copy(inputStream, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        temp.toFile().deleteOnExit();
        return temp;
    }

    /* Materialises a model to a temp directory, copying external .data files too. */
    public Path materializeModelForRuntime(String modelName) throws IOException {
        Resource modelResource = modelResource(modelName);
        if (modelResource.isFile()) {
            return modelResource.getFile().toPath();
        }

        Path tempDirectory = Files.createTempDirectory("dataprocessor-ai-model-");
        tempDirectory.toFile().deleteOnExit();

        String modelFileName = fileName(modelName);
        Path modelPath = tempDirectory.resolve(modelFileName);
        try (InputStream inputStream = modelResource.getInputStream()) {
            Files.copy(inputStream, modelPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        modelPath.toFile().deleteOnExit();

        for (String externalDataName : externalDataCandidates(modelName)) {
            Resource externalDataResource = modelResource(externalDataName);
            if (!externalDataResource.exists()) {
                continue;
            }
            Path externalDataPath = tempDirectory.resolve(fileName(externalDataName));
            try (InputStream inputStream = externalDataResource.getInputStream()) {
                Files.copy(inputStream, externalDataPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            externalDataPath.toFile().deleteOnExit();
        }

        return modelPath;
    }

    /* ========== Private helpers ========== */

    /* Returns candidate external .data files for ONNX models. */
    private Set<String> externalDataCandidates(String modelName) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(modelName + ".data");

        String directory = directoryName(modelName);
        if ("sequence/".equals(directory)) {
            candidates.add(directory + "winning_sequence_engine.onnx.data");
            candidates.add(directory + "transformer_sequence_engine.onnx.data");
            candidates.add(directory + "tcn_sequence_engine.onnx.data");
        }

        return candidates;
    }

    /* Extracts the directory portion of a path. */
    private String directoryName(String path) {
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(0, separator + 1) : "";
    }

    /* Extracts the file name from a path. */
    private String fileName(String path) {
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    /* ========== JSON deserialisation ========== */

    public <T> T readConfig(String name, Class<T> type) throws IOException {
        try (InputStream inputStream = configResource(name).getInputStream()) {
            return objectMapper.readValue(inputStream, type);
        }
    }

    /* Deserialises a model JSON file. */
    public <T> T readModelJson(String modelName, Class<T> type) throws IOException {
        try (InputStream inputStream = modelResource(modelName).getInputStream()) {
            return objectMapper.readValue(inputStream, type);
        }
    }

    /* Deserialises an arbitrary JSON resource. */
    public <T> T readJson(String relativePath, Class<T> type) throws IOException {
        try (InputStream inputStream = resource(relativePath).getInputStream()) {
            return objectMapper.readValue(inputStream, type);
        }
    }

    /* Validates that a mandatory config resource exists. */
    private void validateMandatoryConfig(String configName, List<String> missing) {
        requireResource(properties.getConfigPath() + configName, missing);
    }

    /* Validates that a mandatory model resource exists. */
    private void requireModel(String modelName, List<String> missing) {
        requireResource(properties.getModelsPath() + modelName, missing);
    }

    /* Adds to missing list if resource does not exist. */
    private void requireResource(String relativePath, List<String> missing) {
        if (!resourceExists(relativePath)) {
            missing.add(relativePath);
        }
    }

    /* Records a warning if an optional config is missing. */
    private void optionalConfig(String configName, String warning, List<String> warnings) {
        if (!resourceExists(properties.getConfigPath() + configName)) {
            warnings.add(warning);
        }
    }

    /* Records a warning if an optional model is missing. */
    private void optionalModel(String modelName, String warning, List<String> warnings) {
        if (!modelExists(modelName)) {
            warnings.add(warning);
        }
    }

    /* Builds the health snapshot from present/absent artifacts. */
    private RuntimeArtifactHealth buildHealth(List<String> missing, List<String> warnings) {
        return RuntimeArtifactHealth.builder()
                .runtimeVersion(properties.getRuntimeVersion())
                .artifactBasePath(properties.getBasePath())
                .transformerArtifactPresent(modelExists(TRANSFORMER_MODEL))
                .tcnArtifactPresent(modelExists(TCN_MODEL))
                .xgboostAnomalyArtifactPresent(modelExists(TABULAR_XGBOOST_JSON) || modelExists(TABULAR_XGBOOST_UBJ))
                .lightgbmAlertArtifactPresent(modelExists(TABULAR_LIGHTGBM))
                .catboostAnomalyArtifactPresent(modelExists(TABULAR_CATBOOST))
                .oneClassSvmArtifactPresent(modelExists(TABULAR_ONECLASS_SVM))
                .churnExtraTreesArtifactPresent(modelExists(CHURN_MODEL))
                .forecastRidgeArtifactPresent(modelExists(FORECAST_ANOMALY_RATE_RIDGE))
                .forecastXGBoostArtifactPresent(modelExists(FORECAST_TOTAL_EVENTS_XGBOOST_JSON) || modelExists(FORECAST_TOTAL_EVENTS_XGBOOST_UBJ))
                .personaEnabled(false)
                .personaSkippedReason(personaProperties.getSkippedReason())
                .llmExplanationInDataprocessor(false)
                .llmEvidencePayloadEnabled(true)
                .missingArtifacts(List.copyOf(missing))
                .warnings(List.copyOf(warnings))
                .build();
    }

    /* Validates that the loaded sequence metadata matches the trained contract. */
    private void validateSequenceContract() {
        if (!EXPECTED_CAT_COLS.equals(sequenceMetadata.getCatCols())) {
            throw new IllegalStateException("sequence_metadata.json cat_cols do not match trained V3.6.1 contract");
        }
        if (!EXPECTED_CONT_COLS.equals(sequenceMetadata.getContCols())) {
            throw new IllegalStateException("sequence_metadata.json cont_cols do not match trained V3.6.1 contract");
        }
        if (sequenceMetadata.getWindowSize() <= 0) {
            throw new IllegalStateException("sequence_metadata.json window_size must be positive");
        }
        if (sequenceMetadata.getVocabSizes().size() != sequenceMetadata.getCatCols().size()) {
            throw new IllegalStateException("sequence_metadata.json vocab_sizes must align with cat_cols");
        }
        for (String column : sequenceMetadata.getCatCols()) {
            if (!categoricalVocabularies.getInputIdMaps1Based().containsKey(column)) {
                throw new IllegalStateException("categorical_vocabularies.json missing field: " + column);
            }
        }
    }
}
