package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.performance")
public class PerformanceProperties {
    private DashboardRefresh dashboardRefresh = new DashboardRefresh();
    private Evidence evidence = new Evidence();
    private HotPath hotPath = new HotPath();
    private long summaryLogIntervalMs = 30000;
    private boolean traceEventProcessing = false;

    @Data
    public static class DashboardRefresh {
        private long securityOverviewMinIntervalMs = 5000;
        private long alertsMinIntervalMs = 2000;
        private long riskySessionsMinIntervalMs = 10000;
        private long modelHealthMinIntervalMs = 10000;
        private long dashboardRefreshIntervalMs = 15000;
        private long dashboardRefreshViewTimeoutMs = 10000;
        private long churnIntervalMs = 60000;
        private long forecastIntervalMs = 60000;
        private int maxAlertItems = 100;
        private int maxRiskySessionItems = 50;
    }

    @Data
    public static class Evidence {
        private double fullPayloadMinRiskScore = 35.0;
        private boolean writeLowRiskEvidence = false;
    }

    @Data
    public static class HotPath {
        private boolean enableRedisPipelining = true;
        private boolean skipDashboardRefreshInListener = true;
        private boolean skipHeavySqlInListener = true;
    }
}