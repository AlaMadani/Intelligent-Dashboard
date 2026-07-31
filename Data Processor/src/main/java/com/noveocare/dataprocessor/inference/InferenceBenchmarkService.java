package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyInferenceService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs lightweight availability benchmarks for each configured model on
 * startup or on demand, recording per-model latency and any warnings.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InferenceBenchmarkService {

    /* ---- Dependencies ---- */
    private final TabularAnomalyInferenceService tabularAnomalyInferenceService;
    private final InferenceConfig inferenceConfig;

    /* ---- Benchmark state ---- */
    private final AtomicReference<Instant> lastBenchmarkRunAt = new AtomicReference<>();
    private final Map<String, Long> benchmarkResults = new LinkedHashMap<>();
    private final List<String> benchmarkWarnings = new ArrayList<>();

    /* ---- Lifecycle ---- */

    @PostConstruct
    public void init() {
        boolean doRegular = inferenceConfig.isBenchmarkOnStartup() || inferenceConfig.isDebugBenchmarkEnabled();

        if (doRegular) {
            runBenchmark();
        }
    }

    /* ---- Benchmark orchestration ---- */

    public void runBenchmark() {
        benchmarkResults.clear();
        benchmarkWarnings.clear();
        lastBenchmarkRunAt.set(Instant.now());
        log.info("=== Inference Benchmark Starting ===");

        if (inferenceConfig.isTabularEnabled()) {
            benchmarkModel("xgboost", this::benchmarkXgboost);
            benchmarkModel("lightgbm", this::benchmarkLightgbm);
            benchmarkModel("catboost", this::benchmarkCatboost);
            benchmarkModel("oneclasssvm", this::benchmarkOneClassSvm);
        } else {
            log.info("Benchmark: tabular inference disabled, skipping");
        }

        if (inferenceConfig.isChurnEnabled()) {
            benchmarkModel("churn", this::benchmarkChurn);
        } else {
            log.info("Benchmark: churn inference disabled, skipping");
        }

        benchmarkResults.forEach((model, ms) ->
                log.info("MODEL_BENCHMARK model={} elapsedMs={}", model, ms));
        log.info("=== Inference Benchmark Complete ===");
    }

    /* ---- Internal benchmark runners ---- */

    private void benchmarkModel(String name, Runnable task) {
        try {
            long start = System.nanoTime();
            task.run();
            long ms = (System.nanoTime() - start) / 1_000_000L;
            benchmarkResults.put(name, ms);
        } catch (Exception e) {
            benchmarkResults.put(name, -1L);
            benchmarkWarnings.add(name + "_benchmark_failed: " + e.getMessage());
            log.warn("Benchmark failed for model {}", name, e);
        }
    }

    private void benchmarkXgboost() {
        tabularAnomalyInferenceService.xgboostAvailable();
    }

    private void benchmarkLightgbm() {
        tabularAnomalyInferenceService.lightgbmAvailable();
    }

    private void benchmarkCatboost() {
        tabularAnomalyInferenceService.catboostAvailable();
    }

    private void benchmarkOneClassSvm() {
        tabularAnomalyInferenceService.oneClassSvmAvailable();
    }

    private void benchmarkChurn() {
        if (inferenceConfig.isChurnEnabled()) {
            log.info("Benchmark: churn benchmark placeholder");
        }
    }

    /* ---- Diagnostics snapshot ---- */

    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("lastRunAt", lastBenchmarkRunAt.get() != null ? lastBenchmarkRunAt.get().toString() : null);
        snap.putAll(benchmarkResults);
        snap.put("warnings", List.copyOf(benchmarkWarnings));
        return snap;
    }
}