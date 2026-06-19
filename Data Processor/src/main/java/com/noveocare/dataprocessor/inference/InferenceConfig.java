package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.config.AiChurnProperties;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.config.InferenceConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InferenceConfig {

    private final InferenceConfigProperties props;
    private final AiTabularAnomalyProperties tabularProps;
    private final AiSequenceProperties sequenceProps;
    private final AiChurnProperties churnProps;
    private final AiForecastProperties forecastProps;

    public boolean isInferenceEnabled() {
        return props.isInferenceEnabled();
    }

    public boolean isTabularEnabled() {
        return props.isInferenceEnabled() && tabularProps.isEnabled();
    }

    public boolean isSequenceEnabled() {
        return props.isInferenceEnabled() && sequenceProps.isEnabled();
    }

    public boolean isChurnEnabled() {
        return props.isInferenceEnabled() && churnProps.isEnabled();
    }

    public boolean isForecastEnabled() {
        return props.isInferenceEnabled() && forecastProps.isEnabled();
    }

    public boolean isXgboostEnabled() {
        return isTabularEnabled() && tabularProps.isXgboostEnabled();
    }

    public boolean isLightgbmEnabled() {
        return isTabularEnabled() && tabularProps.isLightgbmEnabled();
    }

    public boolean isCatboostEnabled() {
        return isTabularEnabled() && tabularProps.isCatboostEnabled();
    }

    public boolean isOneclasssvmEnabled() {
        return isTabularEnabled() && tabularProps.isOneclasssvmEnabled();
    }

    public boolean isTransformerEnabled() {
        return isSequenceEnabled() && sequenceProps.isTransformerEnabled();
    }

    public boolean isTcnEnabled() {
        return isSequenceEnabled() && sequenceProps.isTcnEnabled();
    }

    public long getTimeoutMs(String modelName) {
        InferenceConfigProperties.InferenceTimeouts t = props.getInferenceTimeouts();
        return switch (modelName) {
            case "xgboost" -> t.getXgboostMs();
            case "lightgbm" -> t.getLightgbmMs();
            case "catboost" -> t.getCatboostMs();
            case "oneclasssvm" -> t.getOneclasssvmMs();
            case "transformer" -> t.getTransformerMs();
            case "tcn" -> t.getTcnMs();
            case "churn" -> t.getChurnMs();
            default -> 1000;
        };
    }

    public boolean isCircuitBreakerEnabled() {
        return props.getCircuitBreaker().isEnabled();
    }

    public int getCircuitBreakerTimeoutThreshold() {
        return props.getCircuitBreaker().getTimeoutThreshold();
    }

    public long getCircuitBreakerCooldownMs() {
        return props.getCircuitBreaker().getCooldownMs();
    }

    public boolean isLiveFastModeEnabled() {
        return props.getLiveFastMode().isEnabled();
    }

    public long getLiveFastModeBudgetMs() {
        return props.getLiveFastMode().getMaxInferenceMsBudget();
    }

    public boolean isLiveFastModePreferLightgbmOnly() {
        return props.getLiveFastMode().isPreferLightgbmOnly();
    }

    public boolean isLiveFastModeSkipSequenceUnderLoad() {
        return props.getLiveFastMode().isSkipSequenceUnderLoad();
    }

    public boolean isBenchmarkOnStartup() {
        return props.isBenchmarkOnStartup();
    }

    public boolean isLiveFastModeSkipTransformer() {
        return props.getLiveFastMode().isSkipTransformer();
    }

    public boolean isLiveFastModeAllowTcn() {
        return props.getLiveFastMode().isAllowTcn();
    }

    public boolean isLiveFastModeSkipSequence() {
        return props.getLiveFastMode().isSkipSequence();
    }

    public boolean isDisableModelForRunAfterCircuitOpen() {
        return props.getCircuitBreaker().isDisableModelForRunAfterCircuitOpen();
    }

    public boolean isDebugBenchmarkEnabled() {
        return sequenceProps.isDebugBenchmarkEnabled();
    }

    public InferenceConfigProperties getInferenceConfigProperties() {
        return props;
    }
}