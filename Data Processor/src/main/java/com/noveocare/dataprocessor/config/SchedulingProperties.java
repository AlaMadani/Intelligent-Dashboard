package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.scheduling")
public class SchedulingProperties {
    private long liveStatsFixedRateMs;
    private String trendCron;
}
