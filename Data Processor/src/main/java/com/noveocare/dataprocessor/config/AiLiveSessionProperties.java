package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.session")
public class AiLiveSessionProperties {
    private int recentEventsLimit = 50;
    private int maxSessionEventsRetained = 500;
    private int sequenceWindowSize = 10;
}