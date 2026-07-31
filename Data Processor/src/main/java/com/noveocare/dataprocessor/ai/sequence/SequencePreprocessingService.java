package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.artifact.CategoricalVocabularies;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.artifact.ScalerParams;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Preprocesses an AuditTrailEvent into an EncodedSequenceEvent by mapping
 * fields, looking up vocabulary IDs, scaling continuous values, and recording
 * coverage data.
 */
@Service
@RequiredArgsConstructor
public class SequencePreprocessingService {

    /* ---- Dependencies ---- */
    private final RuntimeArtifactService artifactService;
    private final SequenceEventMapper eventMapper;
    private final SequenceValueNormalizer valueNormalizer;
    private final SequenceFieldCoverageMonitor coverageMonitor;

    /* ========== Public API ========== */

    /* Encodes a raw event into an EncodedSequenceEvent with vocab IDs and scaled values. */
    public EncodedSequenceEvent encode(AuditTrailEvent event, Instant previousTimestamp) {
        SequenceEventValues values = eventMapper.map(event, previousTimestamp);
        CategoricalVocabularies vocabularies = artifactService.getCategoricalVocabularies();
        List<String> warnings = new ArrayList<>(values.getWarnings());

        long[] categoricalIds = new long[values.getCategoricalColumns().size()];
        for (int i = 0; i < values.getCategoricalColumns().size(); i++) {
            String field = values.getCategoricalColumns().get(i);
            Map<String, Integer> vocabulary = vocabularies.getInputIdMaps1Based().getOrDefault(field, Map.of());
            String raw = values.getCategoricalValues().get(field);
            String normalized = valueNormalizer.normalizeForVocabulary(field, raw, vocabulary);
            Integer id = normalized == null ? null : vocabulary.get(normalized);
            categoricalIds[i] = id == null ? 0L : id.longValue();
            if (categoricalIds[i] == 0L && raw != null && !raw.isBlank()) {
                warnings.add("unknown_category_" + field);
            }
        }

        double[] rawContinuous = new double[values.getContinuousColumns().size()];
        float[] continuous = new float[values.getContinuousColumns().size()];
        for (int i = 0; i < values.getContinuousColumns().size(); i++) {
            String field = values.getContinuousColumns().get(i);
            double raw = values.getContinuousValuesRaw().getOrDefault(field, 0.0);
            rawContinuous[i] = raw;
            continuous[i] = (float) scaleIfNeeded(field, raw);
        }

        EncodedSequenceEvent encoded = EncodedSequenceEvent.builder()
                .insuredId(values.getInsuredId())
                .sessionId(values.getSessionId())
                .eventId(values.getEventId())
                .timestamp(values.getTimestamp())
                .categoricalIds(categoricalIds)
                .continuousValues(continuous)
                .rawContinuousValues(rawContinuous)
                .rawCategoricalValues(values.getCategoricalValues())
                .warnings(List.copyOf(warnings))
                .schemaValid(values.isSchemaValid())
                .build();
        coverageMonitor.record(values, encoded);
        return encoded;
    }

    /* ========== Private helpers ========== */

    /* Applies standardisation scaling (center/scale) with optional clipping. */
    private double scaleIfNeeded(String field, double raw) {
        ScalerParams params = artifactService.getScalerParams();
        int index = params.getScaledColumns().indexOf(field);
        if (index < 0) {
            return raw;
        }
        double clipped = raw;
        ScalerParams.ClipBounds clipBounds = params.getClipBounds();
        if (clipBounds != null && clipBounds.isAvailable()) {
            if (clipBounds.getClipLow() != null) {
                clipped = Math.max(clipped, clipBounds.getClipLow());
            }
            if (clipBounds.getClipHigh() != null) {
                clipped = Math.min(clipped, clipBounds.getClipHigh());
            }
        }
        double center = index < params.getCenter().size() ? params.getCenter().get(index) : 0.0;
        double scale = index < params.getScale().size() ? params.getScale().get(index) : 1.0;
        if (scale == 0.0) {
            scale = 1.0;
        }
        return (clipped - center) / scale;
    }
}
