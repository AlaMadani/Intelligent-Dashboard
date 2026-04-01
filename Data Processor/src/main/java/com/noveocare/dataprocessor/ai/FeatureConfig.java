package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class FeatureConfig {
    @JsonProperty("seq_len")
    private int seqLen;
    @JsonProperty("feature_cols")
    private List<String> featureCols;
    @JsonProperty("n_features")
    private int nFeatures;
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
    @JsonProperty("max_session_len")
    private int maxSessionLen;
}
