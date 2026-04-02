package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Mirrors the JSON metadata that describes the sequence-model feature layout.
 */
@Data
public class FeatureConfig {
    // Sequence shape expected by the ONNX models.
    @JsonProperty("seq_len")
    private int seqLen;
    @JsonProperty("feature_cols")
    private List<String> featureCols;
    @JsonProperty("n_features")
    private int nFeatures;

    // Vocabulary sizes used when categorical ids are embedded or validated.
    @JsonProperty("action_vocab_size")
    private int actionVocabSize;
    @JsonProperty("device_vocab_size")
    private int deviceVocabSize;
    @JsonProperty("country_vocab_size")
    private int countryVocabSize;
    @JsonProperty("type_vocab_size")
    private int typeVocabSize;
    @JsonProperty("subtype_vocab_size")
    private int subtypeVocabSize;

    // Maximum session length used when normalizing sequence-position features.
    @JsonProperty("max_session_len")
    private int maxSessionLen;
}
