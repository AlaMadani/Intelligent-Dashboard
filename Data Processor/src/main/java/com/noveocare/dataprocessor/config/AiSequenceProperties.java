package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.sequence")
public class AiSequenceProperties {
    private boolean enabled = false;
    private boolean transformerEnabled = false;
    private boolean tcnEnabled = false;
    private boolean debugBenchmarkEnabled = false;
    private boolean debugDirectRun = false;
    private boolean strictSchema = true;
    private boolean allowLegacyFallback = false;
    private int minContextEvents = 3;
    private String primaryModel = "transformer";
    private String fastModel = "tcn";
    private String loadSheddingModel = "tcn";
    private boolean runBoth = false;
    private long timeoutMs = 2000;
}