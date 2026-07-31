package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central inference configuration holding global toggles, per-model timeouts,
 * circuit-breaker settings, and live fast-mode parameters.
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class InferenceConfigProperties {
    /* --- Global inference toggles --- */
    private boolean inferenceEnabled = true;
    private boolean benchmarkOnStartup = false;

    /* --- Sub-configurations --- */
    private InferenceTimeouts inferenceTimeouts = new InferenceTimeouts();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private LiveFastMode liveFastMode = new LiveFastMode();

    /**
     * Per-model inference timeout values in milliseconds.
     */
    @Data
    public static class InferenceTimeouts {
        private long xgboostMs = 150;
        private long lightgbmMs = 150;
        private long catboostMs = 250;
        private long oneclasssvmMs = 250;
        private long transformerMs = 2000;
        private long tcnMs = 1000;
        private long churnMs = 250;
    }

    /**
     * Simple circuit-breaker that trips after consecutive timeout failures
     * and optionally disables the offending model for the remainder of the run.
     */
    @Data
    public static class CircuitBreaker {
        private boolean enabled = true;
        private int timeoutThreshold = 3;
        private long cooldownMs = 60000;
        private boolean disableModelForRunAfterCircuitOpen = false;
    }

    /**
     * Live fast-mode settings that allow skipping expensive models
     * (Transformer, sequence) when processing must stay within a strict time budget.
     */
    @Data
    public static class LiveFastMode {
        private boolean enabled = true;
        private long maxInferenceMsBudget = 500;
        private boolean preferLightgbmOnly = true;
        private boolean skipSequence = true;
        private boolean skipSequenceUnderLoad = true;
        private boolean skipTransformer = true;
        private boolean allowTcn = true;
    }
}