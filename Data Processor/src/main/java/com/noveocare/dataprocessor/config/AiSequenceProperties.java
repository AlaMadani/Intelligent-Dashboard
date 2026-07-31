package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for sequence-based AI models (Transformer and TCN).
 * Controls model enablement, fallback behavior, schema strictness, and timeout.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.sequence")
public class AiSequenceProperties {
    /* --- Feature toggles --- */
    private boolean enabled = false;
    private boolean transformerEnabled = false;
    private boolean tcnEnabled = false;
    /* --- Debug and development flags --- */
    private boolean debugBenchmarkEnabled = false;
    private boolean debugDirectRun = false;
    /* --- Schema and fallback --- */
    private boolean strictSchema = true;
    private boolean allowLegacyFallback = false;
    /* --- Model selection and execution --- */
    private int minContextEvents = 3;
    private String primaryModel = "transformer";
    private String fastModel = "tcn";
    private String loadSheddingModel = "tcn";
    private boolean runBoth = false;
    private long timeoutMs = 2000;
}