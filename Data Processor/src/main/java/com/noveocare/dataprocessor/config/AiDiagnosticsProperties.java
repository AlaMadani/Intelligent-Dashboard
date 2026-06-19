package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.diagnostics")
public class AiDiagnosticsProperties {
    private boolean traceEventProcessing = false;
    private boolean traceModelScores = false;
    private boolean traceSequenceLive = false;
    private boolean traceRiskBreakdown = true;
    private boolean benchmarkOnnxOnStartup = false;
    private int onnxBenchmarkWarmupRuns = 5;
    private int onnxBenchmarkMeasuredRuns = 100;
}