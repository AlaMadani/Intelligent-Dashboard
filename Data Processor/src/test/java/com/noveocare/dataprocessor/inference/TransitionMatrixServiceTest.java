package com.noveocare.dataprocessor.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.PathDeviationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionMatrixServiceTest {

    private TransitionMatrixService transitionMatrixService;

    @BeforeEach
    void setUp() throws Exception {
        AiResourceProperties properties = new AiResourceProperties();
        properties.setBasePath("classpath:/AI/");
        properties.setManifest("deployment_manifest.json");
        properties.setFeatureBundle("feature_bundle.json");

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        RuntimeArtifactService runtimeArtifactService = new RuntimeArtifactService(properties, new DefaultResourceLoader(), objectMapper);
        runtimeArtifactService.load();

        RuleProperties ruleProperties = new RuleProperties();
        ruleProperties.getPathDeviation().setMinProbability(0.02);
        ruleProperties.getImpossibleSeq().setMinProbability(0.005);

        transitionMatrixService = new TransitionMatrixService(runtimeArtifactService, ruleProperties);
    }

    @Test
    void predictsTopMarkovNextActions() {
        var predictions = transitionMatrixService.predictNextActions("Connexion", 3);

        assertFalse(predictions.isEmpty());
        assertEquals("Validation MFA", predictions.get(0).getAction());
        assertTrue(predictions.get(0).getProbability() > predictions.get(1).getProbability());
    }

    @Test
    void flagsRareTransitionsAsPathDeviation() {
        AuditTrailEvent first = new AuditTrailEvent();
        first.setAction("Connexion");
        first.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        AuditTrailEvent second = new AuditTrailEvent();
        second.setAction("Changer les coordonnÃ©es bancaire, changement de RIB de bÃ©nÃ©ficiaire");
        second.setCreatedAt(Instant.parse("2025-01-01T00:00:05Z"));

        PathDeviationResult deviation = transitionMatrixService.evaluatePathDeviation(List.of(first, second));

        assertTrue(deviation.isDeviated());
        assertTrue(deviation.getTransitionProbability() < 0.02);
    }
}
