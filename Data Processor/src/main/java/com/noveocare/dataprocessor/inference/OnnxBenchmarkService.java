package com.noveocare.dataprocessor.inference;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiDiagnosticsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class OnnxBenchmarkService {

    private final RuntimeArtifactService artifactService;
    private final AiDiagnosticsProperties diagnosticsProperties;

    private OrtEnvironment environment;

    record BenchmarkTiming(double tensorCreateMs, double sessionRunMs, double outputExtractMs, double totalMs) {}

    @PostConstruct
    public void init() {
        if (diagnosticsProperties.isBenchmarkOnnxOnStartup()) {
            runBenchmark();
        }
    }

    public void runBenchmark() {
        environment = OrtEnvironment.getEnvironment();
        int warmup = diagnosticsProperties.getOnnxBenchmarkWarmupRuns();
        int measured = diagnosticsProperties.getOnnxBenchmarkMeasuredRuns();

        log.info("=== ONNX Fake Benchmark Starting ===");

        String[][] modelDefs = {
                {"transformer_sequence_engine", RuntimeArtifactService.TRANSFORMER_MODEL},
                {"winning_sequence_engine", RuntimeArtifactService.WINNING_SEQUENCE_MODEL},
                {"tcn_sequence_engine", RuntimeArtifactService.TCN_MODEL}
        };

        for (String[] def : modelDefs) {
            String modelName = def[0];
            String artifactPath = def[1];
            benchmarkSingleModel(modelName, artifactPath, warmup, measured);
        }

        log.info("=== ONNX Fake Benchmark Complete ===");
    }

    private void benchmarkSingleModel(String modelName, String artifactPath, int warmup, int measured) {
        OrtSession session = null;

        try {
            if (!artifactService.modelExists(artifactPath)) {
                log.warn("ONNX_BENCH_SKIP model={} reason=model_file_missing path={}", modelName, artifactPath);
                return;
            }

            Path modelPath = artifactService.materializeModelForRuntime(artifactPath);

            try {
                session = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
            } catch (Exception e) {
                log.error("ONNX_BENCH_ERROR model={} category=SESSION_LOAD_FAILED error=\"{}\"", modelName, e.getMessage());
                return;
            }

            logInputOutputMetadata(modelName, artifactPath, session);

            if (!validateInputs(modelName, session)) {
                return;
            }

            long[][][] xCatData = new long[1][10][15];
            float[][][] xContData = new float[1][10][9];
            boolean[][] maskData = new boolean[1][10];

            for (int t = 0; t < 10; t++) {
                for (int c = 0; c < 15; c++) {
                    xCatData[0][t][c] = c % 3;
                }
                for (int c = 0; c < 9; c++) {
                    xContData[0][t][c] = c * 0.1f;
                }
                maskData[0][t] = true;
            }

            runBenchmarkWithTensorCreation(modelName, session, warmup, measured, xCatData, xContData, maskData);

            runBenchmarkReusedTensors(modelName, session, warmup, measured, xCatData, xContData, maskData);

        } catch (Exception e) {
            log.error("ONNX_BENCH_ERROR model={} category=BENCHMARK_FAILED error=\"{}\"", modelName, e.getMessage());
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void logInputOutputMetadata(String modelName, String artifactPath, OrtSession session) throws OrtException {
        log.info("ONNX_MODEL_INFO model={} path={}", modelName, artifactPath);
        for (Map.Entry<String, NodeInfo> entry : session.getInputInfo().entrySet()) {
            TensorInfo info = (TensorInfo) entry.getValue().getInfo();
            log.info("ONNX_MODEL_INPUT model={} name={} shape={} type={}",
                    modelName, entry.getKey(), Arrays.toString(info.getShape()), info.type);
        }
        for (Map.Entry<String, NodeInfo> entry : session.getOutputInfo().entrySet()) {
            TensorInfo info = (TensorInfo) entry.getValue().getInfo();
            log.info("ONNX_MODEL_OUTPUT model={} name={} shape={} type={}",
                    modelName, entry.getKey(), Arrays.toString(info.getShape()), info.type);
        }
    }

    private boolean validateInputs(String modelName, OrtSession session) throws OrtException {
        var inputNames = session.getInputNames();
        if (!inputNames.containsAll(List.of("x_cat", "x_cont", "mask"))) {
            log.error("ONNX_BENCH_ERROR model={} category=INPUT_NAME_MISMATCH expected=x_cat,x_cont,mask actual={}",
                    modelName, inputNames);
            return false;
        }
        return true;
    }

    private void runBenchmarkWithTensorCreation(String modelName, OrtSession session, int warmup, int measured,
                                                 long[][][] xCat, float[][][] xCont, boolean[][] mask) {
        log.info("ONNX_BENCH_MODE model={} mode=withTensorCreation warmup={} measured={}", modelName, warmup, measured);

        try {
            for (int i = 0; i < warmup; i++) {
                runSingleMeasured(session, xCat, xCont, mask);
            }
        } catch (Exception e) {
            log.error("ONNX_BENCH_ERROR model={} category=WARMUP_FAILED error=\"{}\"", modelName, e.getMessage());
            return;
        }

        List<BenchmarkTiming> timings = new ArrayList<>(measured);
        for (int i = 0; i < measured; i++) {
            try {
                timings.add(runSingleMeasured(session, xCat, xCont, mask));
            } catch (Exception e) {
                log.error("ONNX_BENCH_ERROR model={} category=SESSION_RUN_FAILED error=\"{}\"", modelName, e.getMessage());
                return;
            }
        }

        logSummary(modelName, "withTensorCreation", measured, warmup, timings);
    }

    private void runBenchmarkReusedTensors(String modelName, OrtSession session, int warmup, int measured,
                                            long[][][] xCat, float[][][] xCont, boolean[][] mask) {
        log.info("ONNX_BENCH_MODE model={} mode=reusedTensors warmup={} measured={}", modelName, warmup, measured);

        try (OnnxTensor xCatTensor = OnnxTensor.createTensor(environment, xCat);
             OnnxTensor xContTensor = OnnxTensor.createTensor(environment, xCont);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask)) {

            for (int i = 0; i < warmup; i++) {
                try (OrtSession.Result result = session.run(Map.of(
                        "x_cat", xCatTensor, "x_cont", xContTensor, "mask", maskTensor))) {
                    extractOutputsMinimal(result);
                }
            }

            List<BenchmarkTiming> timings = new ArrayList<>(measured);
            for (int i = 0; i < measured; i++) {
                long t0 = System.nanoTime();

                long t1 = t0;
                OrtSession.Result result;
                try {
                    result = session.run(Map.of(
                            "x_cat", xCatTensor, "x_cont", xContTensor, "mask", maskTensor));
                } catch (OrtException e) {
                    log.error("ONNX_BENCH_ERROR model={} category=SESSION_RUN_FAILED error=\"{}\"",
                            modelName, e.getMessage());
                    return;
                }
                long t2 = System.nanoTime();

                try {
                    extractOutputsMinimal(result);
                } catch (Exception e) {
                    log.error("ONNX_BENCH_ERROR model={} category=OUTPUT_EXTRACT_FAILED error=\"{}\"",
                            modelName, e.getMessage());
                    return;
                } finally {
                    result.close();
                }
                long t3 = System.nanoTime();

                double sessionRunMs = (t2 - t1) / 1_000_000.0;
                double outputExtractMs = (t3 - t2) / 1_000_000.0;
                double totalMs = (t3 - t0) / 1_000_000.0;
                timings.add(new BenchmarkTiming(0, sessionRunMs, outputExtractMs, totalMs));
            }

            logSummary(modelName, "reusedTensors", measured, warmup, timings);

        } catch (Exception e) {
            log.error("ONNX_BENCH_ERROR model={} category=TENSOR_CREATION_FAILED error=\"{}\"", modelName, e.getMessage());
        }
    }

    private BenchmarkTiming runSingleMeasured(OrtSession session, long[][][] xCat, float[][][] xCont, boolean[][] mask)
            throws OrtException {
        long t0 = System.nanoTime();

        OnnxTensor xCatTensor = null;
        OnnxTensor xContTensor = null;
        OnnxTensor maskTensor = null;
        OrtSession.Result result = null;
        try {
            xCatTensor = OnnxTensor.createTensor(environment, xCat);
            xContTensor = OnnxTensor.createTensor(environment, xCont);
            maskTensor = OnnxTensor.createTensor(environment, mask);
            long t1 = System.nanoTime();

            result = session.run(Map.of("x_cat", xCatTensor, "x_cont", xContTensor, "mask", maskTensor));
            long t2 = System.nanoTime();

            extractOutputsMinimal(result);
            long t3 = System.nanoTime();

            double tensorCreateMs = (t1 - t0) / 1_000_000.0;
            double sessionRunMs = (t2 - t1) / 1_000_000.0;
            double outputExtractMs = (t3 - t2) / 1_000_000.0;
            double totalMs = (t3 - t0) / 1_000_000.0;

            return new BenchmarkTiming(tensorCreateMs, sessionRunMs, outputExtractMs, totalMs);

        } finally {
            if (result != null) result.close();
            if (xCatTensor != null) xCatTensor.close();
            if (xContTensor != null) xContTensor.close();
            if (maskTensor != null) maskTensor.close();
        }
    }

    private void extractOutputsMinimal(OrtSession.Result result) throws OrtException {
        for (Map.Entry<String, ? extends OnnxValue> entry : result) {
            OnnxValue value = entry.getValue();
            if (value instanceof OnnxTensor tensor) {
                tensor.getValue();
            }
        }
    }

    private void logSummary(String modelName, String mode, int measured, int warmup, List<BenchmarkTiming> timings) {
        double[] tensorCreateArr = timings.stream().mapToDouble(BenchmarkTiming::tensorCreateMs).toArray();
        double[] sessionRunArr = timings.stream().mapToDouble(BenchmarkTiming::sessionRunMs).toArray();
        double[] outputExtractArr = timings.stream().mapToDouble(BenchmarkTiming::outputExtractMs).toArray();
        double[] totalArr = timings.stream().mapToDouble(BenchmarkTiming::totalMs).toArray();

        Arrays.sort(tensorCreateArr);
        Arrays.sort(sessionRunArr);
        Arrays.sort(outputExtractArr);
        Arrays.sort(totalArr);

        double tensorCreateMin = tensorCreateArr[0];
        double tensorCreateP50 = percentileDouble(tensorCreateArr, 50);
        double tensorCreateP95 = percentileDouble(tensorCreateArr, 95);
        double tensorCreateMax = tensorCreateArr[tensorCreateArr.length - 1];
        double tensorCreateAvg = Arrays.stream(tensorCreateArr).average().orElse(0);

        double sessionRunMin = sessionRunArr[0];
        double sessionRunP50 = percentileDouble(sessionRunArr, 50);
        double sessionRunP95 = percentileDouble(sessionRunArr, 95);
        double sessionRunMax = sessionRunArr[sessionRunArr.length - 1];
        double sessionRunAvg = Arrays.stream(sessionRunArr).average().orElse(0);

        double outputExtractMin = outputExtractArr[0];
        double outputExtractP50 = percentileDouble(outputExtractArr, 50);
        double outputExtractP95 = percentileDouble(outputExtractArr, 95);
        double outputExtractMax = outputExtractArr[outputExtractArr.length - 1];
        double outputExtractAvg = Arrays.stream(outputExtractArr).average().orElse(0);

        double totalMin = totalArr[0];
        double totalP50 = percentileDouble(totalArr, 50);
        double totalP95 = percentileDouble(totalArr, 95);
        double totalMax = totalArr[totalArr.length - 1];
        double totalAvg = Arrays.stream(totalArr).average().orElse(0);

        log.info("ONNX_BENCH model={} mode={} runs={} warmup={}", modelName, mode, measured, warmup);
        log.info("tensorCreate.minMs={}", String.format("%.3f", tensorCreateMin));
        log.info("tensorCreate.p50Ms={}", String.format("%.3f", tensorCreateP50));
        log.info("tensorCreate.p95Ms={}", String.format("%.3f", tensorCreateP95));
        log.info("tensorCreate.maxMs={}", String.format("%.3f", tensorCreateMax));
        log.info("tensorCreate.avgMs={}", String.format("%.3f", tensorCreateAvg));
        log.info("sessionRun.minMs={}", String.format("%.3f", sessionRunMin));
        log.info("sessionRun.p50Ms={}", String.format("%.3f", sessionRunP50));
        log.info("sessionRun.p95Ms={}", String.format("%.3f", sessionRunP95));
        log.info("sessionRun.maxMs={}", String.format("%.3f", sessionRunMax));
        log.info("sessionRun.avgMs={}", String.format("%.3f", sessionRunAvg));
        log.info("outputExtract.minMs={}", String.format("%.3f", outputExtractMin));
        log.info("outputExtract.p50Ms={}", String.format("%.3f", outputExtractP50));
        log.info("outputExtract.p95Ms={}", String.format("%.3f", outputExtractP95));
        log.info("outputExtract.maxMs={}", String.format("%.3f", outputExtractMax));
        log.info("outputExtract.avgMs={}", String.format("%.3f", outputExtractAvg));
        log.info("total.minMs={}", String.format("%.3f", totalMin));
        log.info("total.p50Ms={}", String.format("%.3f", totalP50));
        log.info("total.p95Ms={}", String.format("%.3f", totalP95));
        log.info("total.maxMs={}", String.format("%.3f", totalMax));
        log.info("total.avgMs={}", String.format("%.3f", totalAvg));

        String verdict;
        String status;
        if (totalP95 < 50) {
            verdict = "FAST";
            status = "p95Ms=" + String.format("%.3f", totalP95) + " (<50ms)";
        } else if (totalP95 < 200) {
            verdict = "ACCEPTABLE";
            status = "p95Ms=" + String.format("%.3f", totalP95) + " (<200ms)";
        } else if (totalP95 < 1000) {
            verdict = "SLOW";
            status = "p95Ms=" + String.format("%.3f", totalP95) + " (>=200ms)";
        } else {
            verdict = "CRITICAL";
            status = "p95Ms=" + String.format("%.3f", totalP95) + " (>=1000ms)";
        }
        log.info("ONNX_BENCH_VERDICT model={} mode={} status={} {}", modelName, mode, verdict, status);
    }

    private static double percentileDouble(double[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}