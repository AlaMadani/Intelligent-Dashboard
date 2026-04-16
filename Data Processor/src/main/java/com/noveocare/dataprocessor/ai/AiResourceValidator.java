package com.noveocare.dataprocessor.ai;

import com.noveocare.dataprocessor.config.AiResourceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies at startup that every configured AI artifact exists and is readable.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiResourceValidator {

    private final AiResourceProperties properties;
    private final RuntimeArtifactService runtimeArtifactService;

    @PostConstruct
    public void validate() {
        List<String> missingRequired = new ArrayList<>();
        List<String> missingOptionalModels = new ArrayList<>();
        List<String> required = new ArrayList<>();
        List<String> optionalModels = new ArrayList<>();
        required.add(properties.getManifest());
        required.add(properties.getFeatureBundle());
        required.add(runtimeArtifactService.getDeploymentManifest().getBinaryDetection().getFeatureColumns());
        required.add(runtimeArtifactService.getDeploymentManifest().getBinaryDetection().getNumericMedians());
        required.add(runtimeArtifactService.getDeploymentManifest().getNextAction().getArtifact());
        optionalModels.add(runtimeArtifactService.getDeploymentManifest().getBinaryDetection().getPreferred());
        optionalModels.add(runtimeArtifactService.getDeploymentManifest().getBinaryDetection().getFallback());
        optionalModels.add(runtimeArtifactService.getDeploymentManifest().getAnomalyType().getModel());
        optionalModels.add(runtimeArtifactService.getDeploymentManifest().getChurn().getModel());
        optionalModels.add(runtimeArtifactService.getDeploymentManifest().getClustering().getModel());

        if (runtimeArtifactService.getDeploymentManifest().getAnomalyType().getLabels() != null) {
            required.add(runtimeArtifactService.getDeploymentManifest().getAnomalyType().getLabels());
        }
        if (runtimeArtifactService.getDeploymentManifest().getChurn().getNumericMedians() != null) {
            required.add(runtimeArtifactService.getDeploymentManifest().getChurn().getNumericMedians());
        }
        if (runtimeArtifactService.getDeploymentManifest().getChurn().getFeatureColumns() != null) {
            required.add(runtimeArtifactService.getDeploymentManifest().getChurn().getFeatureColumns());
        }
        if (runtimeArtifactService.getDeploymentManifest().getClustering().getScalerParams() != null) {
            required.add(runtimeArtifactService.getDeploymentManifest().getClustering().getScalerParams());
        }

        for (String name : required) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!runtimeArtifactService.resourceExists(name)) {
                missingRequired.add(name);
            }
        }

        for (String name : optionalModels) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!runtimeArtifactService.resourceExists(name)) {
                missingOptionalModels.add(name);
            }
        }

        if (!missingRequired.isEmpty()) {
            log.error("Missing required AI resources under {}: {}", properties.getBasePath(), missingRequired);
            throw new IllegalStateException("Missing required AI resources: " + missingRequired);
        }
        if (!missingOptionalModels.isEmpty()) {
            log.warn("Optional model artifacts are missing under {} (runtime fallbacks will be used): {}",
                    properties.getBasePath(), missingOptionalModels);
        }

        for (String name : runtimeArtifactService.getDeploymentManifest().getDashboardExports()) {
            if (!runtimeArtifactService.resourceExists(name)) {
                log.warn("Dashboard export {} is not present under {}", name, properties.getBasePath());
            }
        }
        log.info("Validated manifest-driven AI resources under {}", properties.getBasePath());
    }
}
