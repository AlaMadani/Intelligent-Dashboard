package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO holding evidence produced by sequential/behavioral models.
 * <p>
 * Identifies which sequence model was used, whether context was
 * available, the window size, categorical/continuous/context scores,
 * and the top fields contributing to the sequence surprise.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36SequenceEvidenceDto {
    /** Name of the sequence model selected for this evaluation. */
    private String selectedSequenceModel;
    /** Artifact identifier or version of the deployed sequence model. */
    private String sequenceModelArtifact;
    /** Whether the session context window was available for analysis. */
    private Boolean contextAvailable;
    /** Size of the context window used by the sequence model. */
    private Integer windowSize;
    /** Surprise score from the categorical sequence component. */
    private Double sequenceCatScore;
    /** Surprise score from the continuous sequence component. */
    private Double sequenceContScore;
    /** Surprise score from the context-aware sequence component. */
    private Double sequenceCtxScore;
    /** Top fields ranked by their contribution to sequence surprise. */
    private List<Map<String, Object>> topSequenceSurpriseFields;
    /** Raw/unprocessed sequence model output data. */
    private Map<String, Object> raw;
}
