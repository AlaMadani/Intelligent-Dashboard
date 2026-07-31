package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Time basis and sliding-window durations for live statistics aggregation.
 */
@Data
@ConfigurationProperties(prefix = "app.stats.live")
public class LiveStatsProperties {
    /* --- Time reference --- */
    private String timeBasis = "ingestion";
    /* --- Aggregation windows --- */
    private int eventsWindowSeconds;
    private int actionsWindowMinutes;
    private int countriesWindowMinutes;
    private int anomalyWindowMinutes;
    private int koWindowMinutes;
}