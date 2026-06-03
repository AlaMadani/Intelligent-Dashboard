package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36SequenceEvidenceDto {
    private String selectedSequenceModel;
    private String sequenceModelArtifact;
    private Boolean contextAvailable;
    private Integer windowSize;
    private Double sequenceCatScore;
    private Double sequenceContScore;
    private Double sequenceCtxScore;
    private List<Map<String, Object>> topSequenceSurpriseFields;
    private Map<String, Object> raw;
}
