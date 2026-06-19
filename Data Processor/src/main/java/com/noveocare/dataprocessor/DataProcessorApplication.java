package com.noveocare.dataprocessor;

import com.noveocare.dataprocessor.config.AiChurnProperties;
import com.noveocare.dataprocessor.config.AiDiagnosticsProperties;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.AiLiveSessionProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.config.InferenceConfigProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the offline data-processing worker.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class DataProcessorApplication {

    private final InferenceConfigProperties inferenceConfigProperties;
    private final AiTabularAnomalyProperties tabularProperties;
    private final AiSequenceProperties sequenceProperties;
    private final AiChurnProperties churnProperties;
    private final AiForecastProperties forecastProperties;
    private final AiDiagnosticsProperties diagnosticsProperties;
    private final AiLiveSessionProperties liveSessionProperties;

    public static void main(String[] args) {
        SpringApplication.run(DataProcessorApplication.class, args);
    }

    @PostConstruct
    public void logStartupConfig() {
        String javaVersion = System.getProperty("java.version", "unknown");
        log.info("AI inference enabled = {}", inferenceConfigProperties.isInferenceEnabled());
        log.info("Tabular inference enabled = {}", tabularProperties.isEnabled());
        log.info("Sequence inference enabled = {}", sequenceProperties.isEnabled());
        log.info("Transformer inference enabled = {}", sequenceProperties.isTransformerEnabled());
        log.info("TCN inference enabled = {}", sequenceProperties.isTcnEnabled());
        log.info("Churn inference enabled = {}", churnProperties.isEnabled());
        log.info("Forecast inference enabled = {}", forecastProperties.isEnabled());
        log.info("Live fast mode enabled = {}", inferenceConfigProperties.getLiveFastMode().isEnabled());
        log.info("Live fast mode skip transformer = {}", inferenceConfigProperties.getLiveFastMode().isSkipTransformer());
        log.info("Circuit breaker disable model for run after open = {}",
                inferenceConfigProperties.getCircuitBreaker().isDisableModelForRunAfterCircuitOpen());
        log.info("AI_DIAGNOSTICS_CONFIG benchmarkOnnxOnStartup={} warmup={} runs={}",
                diagnosticsProperties.isBenchmarkOnnxOnStartup(),
                diagnosticsProperties.getOnnxBenchmarkWarmupRuns(),
                diagnosticsProperties.getOnnxBenchmarkMeasuredRuns());
        log.info("AI_SEQUENCE_CONFIG enabled={} transformerEnabled={} tcnEnabled={} transformerTimeoutMs={} debugDirectRun={}",
                sequenceProperties.isEnabled(),
                sequenceProperties.isTransformerEnabled(),
                sequenceProperties.isTcnEnabled(),
                inferenceConfigProperties.getInferenceTimeouts().getTransformerMs(),
                sequenceProperties.isDebugDirectRun());
        log.info("Session recent events limit = {}", liveSessionProperties.getRecentEventsLimit());
        log.info("Session max retained events = {}", liveSessionProperties.getMaxSessionEventsRetained());
        log.info("Session sequence window size = {}", liveSessionProperties.getSequenceWindowSize());
        log.info("Java version: {}", javaVersion);
        if (javaVersion.startsWith("25") || javaVersion.startsWith("24") || javaVersion.startsWith("23")
                || javaVersion.startsWith("22")) {
            log.warn("Java {} detected. ONNX runtime may produce restricted native access warnings. Recommended: Java 21 LTS.", javaVersion);
        }
    }

}
