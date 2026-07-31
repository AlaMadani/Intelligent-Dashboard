package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.sequence.SequenceModelKind;
import com.noveocare.dataprocessor.config.AiLoadSheddingProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Dynamically selects which sequence model (Transformer / TCN / rules-only) to
 * run based on the current Kafka consumer lag, so that the system degrades
 * gracefully under high load.
 */
@Service
@RequiredArgsConstructor
public class LoadSheddingService {

    /* ---- Dependencies ---- */
    private final AiLoadSheddingProperties loadSheddingProperties;
    private final AiSequenceProperties sequenceProperties;

    /* ---- Public API ---- */

    /**
     * Returns the most expensive model kind that the current lag allows.
     */
    public SequenceModelKind selectModel(long kafkaLag) {
        if (kafkaLag >= loadSheddingProperties.getRulesOnlyLagThreshold()) {
            return SequenceModelKind.RULES_ONLY;
        }
        if (kafkaLag >= loadSheddingProperties.getTcnLagThreshold()) {
            return SequenceModelKind.from(sequenceProperties.getLoadSheddingModel(), SequenceModelKind.TCN);
        }
        return SequenceModelKind.from(sequenceProperties.getPrimaryModel(), SequenceModelKind.TRANSFORMER);
    }
}
