package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.churn")
public class AiChurnProperties {
    private boolean enabled = true;
    private String preferredModel = "profile_only_ExtraTrees";
    private double highThreshold = 0.70;
    private double mediumThreshold = 0.30;
}
