package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.MarkovTransition;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.NextActionScore;
import com.noveocare.dataprocessor.dto.PathDeviationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TransitionMatrixService {

    private final RuntimeArtifactService runtimeArtifactService;
    private final RuleProperties ruleProperties;

    public List<NextActionScore> predictNextActions(String currentAction, int limit) {
        if (currentAction == null || currentAction.isBlank()) {
            return List.of();
        }
        List<MarkovTransition> transitions = runtimeArtifactService.getMarkovLookup().get(currentAction);
        if (transitions == null || transitions.isEmpty()) {
            return List.of();
        }
        return transitions.stream()
                .sorted(Comparator.comparing(MarkovTransition::getProbability, Comparator.nullsLast(Double::compareTo)).reversed())
                .limit(Math.max(1, limit))
                .map(transition -> NextActionScore.builder()
                        .action(transition.getToAction())
                        .probability(transition.getProbability() == null ? 0.0 : transition.getProbability())
                        .build())
                .toList();
    }

    public PathDeviationResult evaluatePathDeviation(List<AuditTrailEvent> sessionEvents) {
        if (sessionEvents == null || sessionEvents.size() < 2) {
            return PathDeviationResult.builder().deviated(false).build();
        }
        AuditTrailEvent previous = sessionEvents.get(sessionEvents.size() - 2);
        AuditTrailEvent current = sessionEvents.get(sessionEvents.size() - 1);
        String fromAction = previous.getAction();
        String toAction = current.getAction();
        double probability = transitionProbability(fromAction, toAction);
        return PathDeviationResult.builder()
                .deviated(probability < ruleProperties.getPathDeviation().getMinProbability())
                .fromAction(fromAction)
                .toAction(toAction)
                .transitionProbability(probability)
                .build();
    }

    public double transitionProbability(String fromAction, String toAction) {
        if (fromAction == null || fromAction.isBlank() || toAction == null || toAction.isBlank()) {
            return 0.0;
        }
        Map<String, List<MarkovTransition>> lookup = runtimeArtifactService.getMarkovLookup();
        List<MarkovTransition> transitions = lookup.get(fromAction);
        if (transitions == null || transitions.isEmpty()) {
            return 0.0;
        }
        return transitions.stream()
                .filter(transition -> toAction.equals(transition.getToAction()))
                .map(MarkovTransition::getProbability)
                .filter(value -> value != null)
                .findFirst()
                .orElse(0.0);
    }

    public boolean isImpossibleTransition(List<AuditTrailEvent> sessionEvents) {
        if (sessionEvents == null || sessionEvents.size() < 2) {
            return false;
        }
        for (int index = 1; index < sessionEvents.size(); index++) {
            AuditTrailEvent previous = sessionEvents.get(index - 1);
            AuditTrailEvent current = sessionEvents.get(index);
            if (transitionProbability(previous.getAction(), current.getAction())
                    < ruleProperties.getImpossibleSeq().getMinProbability()) {
                return true;
            }
        }
        return false;
    }

    public List<PathDeviationResult> rareTransitions(List<AuditTrailEvent> sessionEvents) {
        if (sessionEvents == null || sessionEvents.size() < 2) {
            return List.of();
        }
        List<PathDeviationResult> deviations = new ArrayList<>();
        for (int index = 1; index < sessionEvents.size(); index++) {
            AuditTrailEvent previous = sessionEvents.get(index - 1);
            AuditTrailEvent current = sessionEvents.get(index);
            double probability = transitionProbability(previous.getAction(), current.getAction());
            if (probability < ruleProperties.getPathDeviation().getMinProbability()) {
                deviations.add(PathDeviationResult.builder()
                        .deviated(true)
                        .fromAction(previous.getAction())
                        .toAction(current.getAction())
                        .transitionProbability(probability)
                        .build());
            }
        }
        return deviations;
    }
}
