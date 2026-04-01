package com.noveocare.dataprocessor.ai;

import com.noveocare.dataprocessor.config.AiResourceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiResourceValidator {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;

    @PostConstruct
    public void validate() {
        List<String> missing = new ArrayList<>();
        for (String name : properties.getRequiredResources()) {
            Resource resource = resourceLoader.getResource(properties.getBasePath() + name);
            if (!resource.exists()) {
                missing.add(name);
                continue;
            }
            try (java.io.InputStream inputStream = resource.getInputStream()) {
                if (inputStream.read() < 0) {
                    log.warn("AI resource is empty: {}", name);
                }
            } catch (Exception e) {
                log.error("Failed to open AI resource {}", name, e);
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            log.error("Missing AI resources under {}: {}", properties.getBasePath(), missing);
            throw new IllegalStateException("Missing AI resources: " + missing);
        }
        log.info("Validated {} AI resources under {}", properties.getRequiredResources().size(), properties.getBasePath());
    }
}
