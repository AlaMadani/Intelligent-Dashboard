package com.noveocare.dataprocessor.ai.sequence;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.inference.InferenceExecutorManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class SequenceOnnxInferenceService {

    private static final long MAX_SEQUENCE_INFERENCE_MS = 5000;

    private final RuntimeArtifactService artifactService;
    private final AiSequenceProperties sequenceProperties;
    private final InferenceExecutorManager executorManager;

    private OrtEnvironment environment;
    private LoadedSequenceSession transformer;
    private LoadedSequenceSession tcn;

    @PostConstruct
    public void init() {
        environment = OrtEnvironment.getEnvironment();
        transformer = loadIfAvailable(SequenceModelKind.TRANSFORMER, RuntimeArtifactService.TRANSFORMER_MODEL);
        tcn = loadIfAvailable(SequenceModelKind.TCN, RuntimeArtifactService.TCN_MODEL);
    }

    public boolean transformerLoaded() {
        return transformer != null && transformer.available();
    }

    public boolean tcnLoaded() {
        return tcn != null && tcn.available();
    }

    public SequenceInferenceResult inferTransformer(SequenceWindow window) {
        return infer(SequenceModelKind.TRANSFORMER, window);
    }

    public SequenceInferenceResult inferTcn(SequenceWindow window) {
        return infer(SequenceModelKind.TCN, window);
    }

    public SequenceInferenceResult infer(SequenceModelKind modelKind, SequenceWindow window) {
        LoadedSequenceSession loaded = modelKind == SequenceModelKind.TCN ? tcn : transformer;
        if (loaded == null || !loaded.available()) {
            throw new IllegalStateException("Sequence ONNX model unavailable: " + modelKind);
        }
        try {
            return runInference(loaded, window);
        } catch (OrtException e) {
            throw new RuntimeException(e);
        }
    }

    public SequenceInferenceResult inferDirect(SequenceModelKind modelKind, SequenceWindow window) {
        return infer(modelKind, window);
    }

    private SequenceInferenceResult runInference(LoadedSequenceSession loaded, SequenceWindow window) throws OrtException {
        long started = System.nanoTime();
        OnnxTensor xCat = null;
        OnnxTensor xCont = null;
        OnnxTensor mask = null;
        OrtSession.Result result = null;
        try {
            xCat = OnnxTensor.createTensor(environment, window.getXCat());
            xCont = OnnxTensor.createTensor(environment, window.getXCont());
            mask = OnnxTensor.createTensor(environment, window.getMask());
            long tensorCreated = System.nanoTime();
            long tensorCreateMs = (tensorCreated - started) / 1_000_000L;

            result = loaded.session().run(Map.of("x_cat", xCat, "x_cont", xCont, "mask", mask));
            long sessionRan = System.nanoTime();
            long sessionRunMs = (sessionRan - tensorCreated) / 1_000_000L;

            ParsedOutputs parsedOutputs = parseOutputs(result);
            long outputsExtracted = System.nanoTime();
            long outputExtractMs = (outputsExtracted - sessionRan) / 1_000_000L;

            long totalMs = (outputsExtracted - started) / 1_000_000L;

            if (totalMs > 10) {
                log.info("SEQUENCE_RUN_INFERENCE model={} tensorCreateMs={} sessionRunMs={} outputExtractMs={} totalMs={}",
                        loaded.artifactName(), tensorCreateMs, sessionRunMs, outputExtractMs, totalMs);
            }

            return SequenceInferenceResult.builder()
                    .modelKind(loaded.kind())
                    .modelArtifact(loaded.artifactName())
                    .categoricalLogits(parsedOutputs.categoricalLogits())
                    .continuousPrediction(parsedOutputs.continuousPrediction())
                    .latencyMillis(totalMs)
                    .outputMetadata(parsedOutputs.outputMetadata())
                    .build();
        } finally {
            if (result != null) result.close();
            if (xCat != null) xCat.close();
            if (xCont != null) xCont.close();
            if (mask != null) mask.close();
        }
    }

    private LoadedSequenceSession load(SequenceModelKind kind, String artifactName) throws IOException, OrtException {
        Path modelPath = artifactService.materializeModelForRuntime(artifactName);
        OrtSession session = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        validateInputs(session, artifactName);
        logContract(kind, artifactName, session);
        return new LoadedSequenceSession(kind, artifactName, session, true);
    }

    private LoadedSequenceSession loadIfAvailable(SequenceModelKind kind, String artifactName) {
        try {
            LoadedSequenceSession loaded = load(kind, artifactName);
            log.info("{} loaded", artifactName);
            return loaded;
        } catch (IOException | OrtException | RuntimeException ex) {
            log.warn("{} unavailable: {}", artifactName, ex.getMessage());
            return new LoadedSequenceSession(kind, artifactName, null, false);
        }
    }

    private void validateInputs(OrtSession session, String artifactName) throws OrtException {
        if (!session.getInputNames().containsAll(List.of("x_cat", "x_cont", "mask"))) {
            throw new IllegalStateException(artifactName + " must expose x_cat, x_cont and mask inputs");
        }
        int expectedOutputCount = artifactService.getSequenceMetadata().getCatCols().size() + 1;
        if (session.getOutputNames().size() < expectedOutputCount) {
            throw new IllegalStateException(artifactName + " output count is lower than expected sequence heads");
        }
    }

    private void logContract(SequenceModelKind kind, String artifactName, OrtSession session) throws OrtException {
        log.info("{} inputs for {}: {}", kind, artifactName, describe(session.getInputInfo()));
        log.info("{} outputs for {}: {}", kind, artifactName, describe(session.getOutputInfo()));
    }

    private Map<String, Object> describe(Map<String, NodeInfo> info) {
        Map<String, Object> description = new LinkedHashMap<>();
        for (Map.Entry<String, NodeInfo> entry : info.entrySet()) {
            Object value = entry.getValue().getInfo();
            if (value instanceof TensorInfo tensorInfo) {
                description.put(entry.getKey(), Map.of(
                        "type", String.valueOf(tensorInfo.type),
                        "shape", List.of(tensorInfo.getShape())));
            } else {
                description.put(entry.getKey(), String.valueOf(value));
            }
        }
        return description;
    }

    private ParsedOutputs parseOutputs(OrtSession.Result result) throws OrtException {
        List<Integer> vocabSizes = artifactService.getSequenceMetadata().getVocabSizes();
        int contCount = artifactService.getSequenceMetadata().getContCols().size();
        List<float[]> categoricalLogits = new ArrayList<>();
        float[] continuousPrediction = null;
        Map<String, Object> metadata = new LinkedHashMap<>();

        for (Map.Entry<String, ? extends OnnxValue> entry : result) {
            float[] vector = flattenFloat(entry.getValue().getValue());
            metadata.put(entry.getKey(), Map.of("length", vector.length));
            int nextHead = categoricalLogits.size();
            if (nextHead < vocabSizes.size() && vector.length == vocabSizes.get(nextHead)) {
                categoricalLogits.add(vector);
                continue;
            }
            if (vector.length == contCount) {
                continuousPrediction = vector;
            }
        }

        if (categoricalLogits.size() != vocabSizes.size()) {
            throw new IllegalStateException("Could not parse all categorical output heads from ONNX result");
        }
        if (continuousPrediction == null) {
            throw new IllegalStateException("Could not parse continuous prediction vector from ONNX result");
        }
        return new ParsedOutputs(List.copyOf(categoricalLogits), continuousPrediction, metadata);
    }

    private float[] flattenFloat(Object value) {
        if (value instanceof float[] vector) {
            return vector;
        }
        if (value instanceof float[][] matrix && matrix.length > 0) {
            return matrix[0];
        }
        if (value instanceof float[][][] tensor && tensor.length > 0 && tensor[0].length > 0) {
            return tensor[0][0];
        }
        if (value instanceof double[] vector) {
            float[] result = new float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                result[i] = (float) vector[i];
            }
            return result;
        }
        if (value instanceof double[][] matrix && matrix.length > 0) {
            float[] result = new float[matrix[0].length];
            for (int i = 0; i < matrix[0].length; i++) {
                result[i] = (float) matrix[0][i];
            }
            return result;
        }
        return new float[0];
    }

    @PreDestroy
    public void close() throws OrtException {
        if (transformer != null && transformer.session() != null) {
            transformer.session().close();
        }
        if (tcn != null && tcn.session() != null) {
            tcn.session().close();
        }
        if (environment != null) {
            environment.close();
        }
    }

    record LoadedSequenceSession(SequenceModelKind kind, String artifactName, OrtSession session, boolean available) {
    }

    private record ParsedOutputs(List<float[]> categoricalLogits,
                                 float[] continuousPrediction,
                                 Map<String, Object> outputMetadata) {
    }
}
