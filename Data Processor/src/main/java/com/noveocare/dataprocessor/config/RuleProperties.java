package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.rules")
public class RuleProperties {
    private UnusualHour unusualHour = new UnusualHour();
    private SkipLogin skipLogin = new SkipLogin();
    private RepeatedFail repeatedFail = new RepeatedFail();
    private List<String> sessionEndActions = new ArrayList<>();
    private int tier2EveryEvents = 5;

    @Data
    public static class UnusualHour {
        private int start;
        private int end;
    }

    @Data
    public static class SkipLogin {
        private List<String> allowedActions = new ArrayList<>();
    }

    @Data
    public static class RepeatedFail {
        private List<String> types = new ArrayList<>();
        private int consecutive;
    }
}
