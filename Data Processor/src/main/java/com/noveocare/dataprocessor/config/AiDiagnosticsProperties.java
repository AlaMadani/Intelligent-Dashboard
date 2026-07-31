package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Diagnostic and tracing flags for the AI inference pipeline.
 * Enables detailed logging of events, model scores, and ONNX benchmark settings.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.diagnostics")
public class AiDiagnosticsProperties {
    /* --- Tracing flags --- */
    private boolean traceEventProcessing = false;
    private boolean traceModelScores = false;
    private boolean traceSequenceLive = false;
    private boolean traceRiskBreakdown = true;
    /* --- ONNX benchmark settings --- */
    private boolean benchmarkOnnxOnStartup = false;
    private int onnxBenchmarkWarmupRuns = 5;
    private int onnxBenchmarkMeasuredRuns = 100;
}