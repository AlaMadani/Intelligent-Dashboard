package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.sequence.SequenceModelKind;
import com.noveocare.dataprocessor.config.AiLoadSheddingProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoadSheddingService {

    private final AiLoadSheddingProperties loadSheddingProperties;
    private final AiSequenceProperties sequenceProperties;

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
