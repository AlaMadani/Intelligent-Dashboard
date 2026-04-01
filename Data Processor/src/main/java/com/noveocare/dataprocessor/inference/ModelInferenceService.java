package com.noveocare.dataprocessor.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.noveocare.dataprocessor.ai.FeatureConfigLoader;
import com.noveocare.dataprocessor.ai.LabelMapService;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import com.noveocare.dataprocessor.dto.AnomalyTypeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModelInferenceService {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final FeatureConfigLoader featureConfigLoader;
    private final LabelMapService labelMapService;

    private OrtEnvironment environment;
    private OrtSession aeSession;
    private OrtSession anomalyTypeSession;
    private OrtSession nextActionSession;

    private String aeInputName;
    private String typeInputName;
    private String nextInputName;

    @PostConstruct
    public void init() throws IOException, OrtException {
        environment = OrtEnvironment.getEnvironment();
        aeSession = createSession(properties.getModels().getAnomalyAutoencoder());
        anomalyTypeSession = createSession(properties.getModels().getAnomalyTypeClassifier());
        nextActionSession = createSession(properties.getModels().getNextActionGru());

        aeInputName = aeSession.getInputNames().iterator().next();
        typeInputName = anomalyTypeSession.getInputNames().iterator().next();
        nextInputName = nextActionSession.getInputNames().iterator().next();
        log.info("ONNX sessions loaded (AE input={}, Type input={}, Next input={})",
                aeInputName, typeInputName, nextInputName);
    }

    public double scoreAnomaly(float[][] matrix) throws OrtException {
        float[][][] input = wrap(matrix);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input)) {
            try (OrtSession.Result result = aeSession.run(Map.of(aeInputName, tensor))) {
                Object value = result.get(0).getValue();
                float[][][] output = (float[][][]) value;
                double score = maxStepMse(input[0], output[0]);
                log.info("AE inference complete maxStepMse={}", score);
                return score;
            }
        }
    }

    public AnomalyTypeResult classifyType(float[][] matrix) throws OrtException {
        float[][][] input = wrap(matrix);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input)) {
            try (OrtSession.Result result = anomalyTypeSession.run(Map.of(typeInputName, tensor))) {
                float[] scores = extractVector(result.get(0).getValue());
                int bestIndex = argMax(scores);
                double confidence = scores[bestIndex];
                AnomalyTypeResult resultDto = AnomalyTypeResult.builder()
                        .type(labelMapService.anomalyTypeLabel(bestIndex))
                        .confidence(confidence)
                        .build();
                log.info("Type inference complete type={} confidence={}",
                        resultDto.getType(), resultDto.getConfidence());
                return resultDto;
            }
        }
    }

    public List<String> predictNextActions(float[][] matrix, int topK) throws OrtException {
        float[][][] input = wrap(matrix);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input)) {
            try (OrtSession.Result result = nextActionSession.run(Map.of(nextInputName, tensor))) {
                float[] scores = extractVector(result.get(0).getValue());
                List<Integer> indices = topKIndices(scores, topK);
                List<String> labels = new ArrayList<>();
                for (int idx : indices) {
                    labels.add(labelMapService.nextActionLabel(idx));
                }
                log.info("Next-action inference complete topK={} labels={}", topK, labels);
                return labels;
            }
        }
    }

    private OrtSession createSession(String fileName) throws IOException, OrtException {
        String path = properties.getBasePath() + fileName;
        Resource resource = resourceLoader.getResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            return environment.createSession(bytes, new OrtSession.SessionOptions());
        }
    }

    private float[][][] wrap(float[][] matrix) {
        int seqLen = featureConfigLoader.getFeatureConfig().getSeqLen();
        int nFeatures = featureConfigLoader.getFeatureConfig().getNFeatures();
        float[][][] input = new float[1][seqLen][nFeatures];
        for (int i = 0; i < Math.min(seqLen, matrix.length); i++) {
            System.arraycopy(matrix[i], 0, input[0][i], 0, Math.min(nFeatures, matrix[i].length));
        }
        return input;
    }

    private double maxStepMse(float[][] input, float[][] output) {
        double max = 0.0;
        for (int t = 0; t < input.length; t++) {
            double sum = 0.0;
            for (int f = 0; f < input[t].length; f++) {
                double diff = input[t][f] - output[t][f];
                sum += diff * diff;
            }
            double mse = input[t].length == 0 ? 0.0 : sum / input[t].length;
            if (mse > max) {
                max = mse;
            }
        }
        return max;
    }

    private float[] extractVector(Object output) {
        if (output instanceof float[][] matrix) {
            return matrix[0];
        }
        return (float[]) output;
    }

    private int argMax(float[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private List<Integer> topKIndices(float[] scores, int topK) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            indices.add(i);
        }
        indices.sort(Comparator.comparingDouble((Integer idx) -> scores[idx]).reversed());
        return indices.subList(0, Math.min(topK, indices.size()));
    }

    @PreDestroy
    public void close() throws OrtException {
        if (aeSession != null) {
            aeSession.close();
        }
        if (anomalyTypeSession != null) {
            anomalyTypeSession.close();
        }
        if (nextActionSession != null) {
            nextActionSession.close();
        }
        if (environment != null) {
            environment.close();
        }
    }
}
