package com.noveocare.dataprocessor.ai.artifact;

import com.noveocare.dataprocessor.inference.ModelHealthService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Startup validator that checks for mandatory V3.6.1 AI artifacts and
 * publishes model health status.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiResourceValidator {

    /* ---- Dependencies ---- */
    private final RuntimeArtifactService artifactService;
    private final ModelHealthService modelHealthService;

    /* Validates all required artifacts exist and publishes health. */
    @PostConstruct
    public void validate() {
        RuntimeArtifactHealth health = artifactService.getArtifactHealth();
        if (health != null && !health.getMissingArtifacts().isEmpty()) {
            throw new IllegalStateException("Missing mandatory V3.6.1 AI artifacts: " + health.getMissingArtifacts());
        }
        modelHealthService.publish();
        log.info("Validated V3.6.1 AI resources");
    }
}
