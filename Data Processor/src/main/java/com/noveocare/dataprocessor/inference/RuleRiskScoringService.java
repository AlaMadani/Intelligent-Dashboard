package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuleRiskScoringService {

    public double score(SessionSummary summary, List<AuditTrailEvent> events, List<String> triggeredRules) {
        return evaluate(summary, events, triggeredRules).getRuleRiskScore();
    }

    public RuleRiskResult evaluate(SessionSummary summary, List<AuditTrailEvent> events, List<String> triggeredRules) {
        List<RuleContribution> contributions = new ArrayList<>();
        List<String> canonicalRules = new ArrayList<>();
        if (triggeredRules != null) {
            for (String rule : triggeredRules) {
                String code = canonicalRuleCode(rule);
                canonicalRules.add(code);
                contributions.add(contribution(code, contributionScore(code), evidenceFor(code, summary, latest(events))));
            }
        }
        if (summary != null) {
            if (defaultInt(summary.getDeviceChanged()) == 1) {
                addIfMissing(canonicalRules, contributions, "DEVICE_SWITCH", 25.0, evidenceFor("DEVICE_SWITCH", summary, latest(events)));
            }
            if (defaultInt(summary.getIpChanged()) == 1) {
                addIfMissing(canonicalRules, contributions, "COUNTRY_SWITCH", 25.0, evidenceFor("COUNTRY_SWITCH", summary, latest(events)));
            }
            if (defaultInt(summary.getTotalKOs()) >= 3) {
                addIfMissing(canonicalRules, contributions, "STATUS_CODE_BURST", 20.0, evidenceFor("STATUS_CODE_BURST", summary, latest(events)));
            }
            if (defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10) {
                addIfMissing(canonicalRules, contributions, "LARGE_DOWNLOAD", 30.0, evidenceFor("LARGE_DOWNLOAD", summary, latest(events)));
            }
            if (defaultInt(summary.getPingPongCount()) >= 2) {
                addIfMissing(canonicalRules, contributions, "API_SCRAPING_PATTERN", 20.0, evidenceFor("API_SCRAPING_PATTERN", summary, latest(events)));
            }
        }
        double score = contributions.stream().mapToDouble(RuleContribution::getScoreContribution).sum();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sessionId", summary == null ? null : summary.getSessionId());
        evidence.put("totalEvents", summary == null ? null : summary.getTotalEvents());
        evidence.put("totalKOs", summary == null ? null : summary.getTotalKOs());
        evidence.put("maxDownloadsIn2Minutes", summary == null ? null : summary.getMaxDownloadsIn2Minutes());
        evidence.put("pingPongCount", summary == null ? null : summary.getPingPongCount());
        evidence.put("deviceChanged", summary == null ? null : summary.getDeviceChanged());
        evidence.put("ipChanged", summary == null ? null : summary.getIpChanged());
        return RuleRiskResult.builder()
                .ruleRiskScore(Math.min(100.0, score))
                .triggeredRules(canonicalRules.stream().distinct().toList())
                .ruleContributions(contributions)
                .ruleEvidence(evidence)
                .businessContextScore(businessContextScore(summary, events, canonicalRules))
                .build();
    }

    public String canonicalRuleCode(String rule) {
        if (rule == null || rule.isBlank()) {
            return "UNKNOWN_RULE";
        }
        return switch (rule.toLowerCase(Locale.ROOT)) {
            case "unusual_hour" -> "OFF_HOURS_ACCESS";
            case "repeated_fail" -> "STATUS_CODE_BURST";
            case "rapid_fire" -> "RAPID_FIRE_EVENTS";
            case "geo_jump", "country_switch" -> "COUNTRY_SWITCH";
            case "device_switch" -> "DEVICE_SWITCH";
            case "download_spike" -> "LARGE_DOWNLOAD";
            case "api_scraping" -> "API_SCRAPING_PATTERN";
            case "skip_login" -> "SKIP_LOGIN";
            case "session_timeout" -> "SESSION_TIMEOUT";
            case "data_exfiltration" -> "DATA_EXTRACTION_PATTERN";
            default -> rule.toUpperCase(Locale.ROOT);
        };
    }

    private void addIfMissing(List<String> canonicalRules,
                              List<RuleContribution> contributions,
                              String code,
                              double score,
                              Map<String, Object> evidence) {
        if (!canonicalRules.contains(code)) {
            canonicalRules.add(code);
            contributions.add(contribution(code, score, evidence));
        }
    }

    private RuleContribution contribution(String code, double score, Map<String, Object> evidence) {
        return RuleContribution.builder()
                .ruleCode(code)
                .severity(severity(score))
                .scoreContribution(score)
                .humanMessage(message(code))
                .evidenceJson(evidence)
                .build();
    }

    private double contributionScore(String code) {
        return switch (code) {
            case "OFF_HOURS_ACCESS", "SENSITIVE_API_OFF_HOURS" -> 25.0;
            case "STATUS_CODE_BURST", "UNUSUAL_DEVICE", "DEVICE_SWITCH", "API_SCRAPING_PATTERN" -> 35.0;
            case "COUNTRY_SWITCH", "UNUSUAL_COUNTRY", "LARGE_DOWNLOAD", "DATA_EXTRACTION_PATTERN" -> 40.0;
            case "RAPID_FIRE_EVENTS", "SKIP_LOGIN", "IMPOSSIBLE_ENDPOINT_TRANSITION" -> 30.0;
            case "SESSION_TIMEOUT" -> 20.0;
            case "SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT" -> 30.0;
            default -> 10.0;
        };
    }

    private String severity(double score) {
        if (score >= 40.0) {
            return "HIGH";
        }
        if (score >= 25.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String message(String code) {
        return switch (code) {
            case "OFF_HOURS_ACCESS" -> "Event occurred outside normal business hours.";
            case "SENSITIVE_API_OFF_HOURS" -> "Sensitive API was accessed outside business hours.";
            case "UNUSUAL_COUNTRY" -> "Event country differs from expected user context.";
            case "UNUSUAL_DEVICE" -> "Event device differs from expected user context.";
            case "COUNTRY_SWITCH" -> "Session switched country or IP context.";
            case "DEVICE_SWITCH" -> "Session switched device context.";
            case "STATUS_CODE_BURST" -> "Session contains repeated failed or unusual status codes.";
            case "RAPID_FIRE_EVENTS" -> "Session contains rapid-fire events.";
            case "API_SCRAPING_PATTERN" -> "Session shows repeated endpoint traversal patterns.";
            case "LARGE_DOWNLOAD" -> "Session contains large or frequent downloads.";
            case "DATA_EXTRACTION_PATTERN" -> "Session resembles data extraction behavior.";
            case "IMPOSSIBLE_ENDPOINT_TRANSITION" -> "Session contains an unlikely endpoint transition.";
            case "SKIP_LOGIN" -> "Session appears to bypass a normal login action.";
            case "SESSION_TIMEOUT" -> "Session has a timeout-like gap.";
            case "SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT" -> "Sensitive API is outside the expected business context.";
            default -> "Deterministic rule triggered.";
        };
    }

    private Map<String, Object> evidenceFor(String code, SessionSummary summary, AuditTrailEvent event) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleCode", code);
        evidence.put("eventId", event == null ? null : event.getId());
        evidence.put("apiTemplate", event == null ? null : event.getApiTemplate());
        evidence.put("apiFamily", event == null ? null : event.getApiFamily());
        evidence.put("status", event == null ? null : event.getStatus());
        evidence.put("eventHour", event == null || event.getCreatedAt() == null ? null : event.getCreatedAt().atZone(ZoneOffset.UTC).getHour());
        evidence.put("isBusinessHours", event == null ? null : event.getIsBusinessHours());
        evidence.put("country", event == null ? null : firstNonBlank(event.getIpCountry(), event.getCountryCode()));
        evidence.put("device", event == null ? null : event.getDevice());
        evidence.put("totalKOs", summary == null ? null : summary.getTotalKOs());
        evidence.put("maxDownloadsIn2Minutes", summary == null ? null : summary.getMaxDownloadsIn2Minutes());
        evidence.put("pingPongCount", summary == null ? null : summary.getPingPongCount());
        return evidence;
    }

    private double businessContextScore(SessionSummary summary, List<AuditTrailEvent> events, List<String> rules) {
        double score = 0.0;
        AuditTrailEvent latest = latest(events);
        if (latest != null && latest.getIsBusinessHours() != null && latest.getIsBusinessHours() == 0) {
            score += 10.0;
        }
        if (rules.contains("SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT")) {
            score += 15.0;
        }
        if (summary != null && defaultInt(summary.getEndedAbruptly()) == 1) {
            score += 5.0;
        }
        return Math.min(30.0, score);
    }

    private AuditTrailEvent latest(List<AuditTrailEvent> events) {
        return events == null || events.isEmpty() ? null : events.get(events.size() - 1);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
