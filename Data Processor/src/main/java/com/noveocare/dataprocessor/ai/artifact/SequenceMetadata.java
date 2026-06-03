package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SequenceMetadata {
    @JsonProperty("model_name")
    private String modelName;
    @JsonProperty("model_file")
    private String modelFile;
    @JsonProperty("window_size")
    private int windowSize;
    @JsonProperty("cat_cols")
    private List<String> catCols = List.of();
    @JsonProperty("cont_cols")
    private List<String> contCols = List.of();
    @JsonProperty("vocab_sizes")
    private List<Integer> vocabSizes = List.of();
    @JsonProperty("onnx_contract")
    private OnnxContract onnxContract = new OnnxContract();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OnnxContract {
        private boolean available;
        private List<TensorContract> inputs = List.of();
        private List<TensorContract> outputs = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TensorContract {
        private String name;
        @JsonProperty("elem_type")
        private Integer elemType;
        private List<Object> shape = List.of();
        private Map<String, Object> metadata = Map.of();
    }
}
