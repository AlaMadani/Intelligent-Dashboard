package com.noveocare.dataprocessor.inference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a learned action-to-action transition matrix and flags very unlikely
 * transitions as impossible or suspicious sequence steps.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransitionMatrixService {

    private final AiResourceProperties aiResourceProperties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final RuleProperties ruleProperties;

    private Map<String, Map<String, Double>> transitionMatrix = new HashMap<>();

    @PostConstruct
    public void loadMatrix() {
        try {
            // Load the matrix from resources if it exists; the detector remains optional at runtime.
            String path = aiResourceProperties.getBasePath() + "transition_matrix.json";
            Resource resource = resourceLoader.getResource(path);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    transitionMatrix = objectMapper.readValue(is, new TypeReference<Map<String, Map<String, Double>>>() {});
                    log.info("Loaded transition matrix with {} states", transitionMatrix.size());
                }
            } else {
                log.warn("transition_matrix.json not found at {}. Impossible sequence detection will be gracefully disabled.", path);
            }
        } catch (Exception e) {
            log.error("Failed to load transition matrix", e);
        }
    }

    public boolean isImpossibleTransition(List<AuditTrailEvent> sessionEvents) {
        // Without at least one transition or a loaded matrix, this heuristic cannot evaluate anything.
        if (sessionEvents.size() < 2 || transitionMatrix.isEmpty()) {
            return false;
        }

        for (int i = 1; i < sessionEvents.size(); i++) {
            AuditTrailEvent previousEvent = sessionEvents.get(i - 1);
            AuditTrailEvent currentEvent = sessionEvents.get(i);

            // Compare the current action pair against the learned next-action probabilities.
            String prevAction = previousEvent.getAction();
            String currAction = currentEvent.getAction();

            if (prevAction == null || currAction == null) {
                continue;
            }

            Map<String, Double> nextProbs = transitionMatrix.get(prevAction);
            if (nextProbs == null) {
                // Unknown source actions are ignored because the matrix has no reliable baseline for them.
                continue;
            }

            Double prob = nextProbs.getOrDefault(currAction, 0.0);
            // Flag the step when its learned probability falls below the configured impossibility floor.
            if (prob < ruleProperties.getImpossibleSeq().getMinProbability()) {
                return true;
            }
        }
        return false;
    }
}
