package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.stats.live")
public class LiveStatsProperties {
    private int eventsWindowSeconds;
    private int actionsWindowMinutes;
    private int countriesWindowMinutes;
    private int anomalyWindowMinutes;
    private int koWindowMinutes;
}
