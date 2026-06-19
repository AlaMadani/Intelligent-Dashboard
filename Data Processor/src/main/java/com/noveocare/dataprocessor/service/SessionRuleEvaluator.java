package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.inference.GeoJumpDetector;
import com.noveocare.dataprocessor.inference.VelocityDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionRuleEvaluator {

    private final RuleProperties ruleProperties;
    private final VelocityDetector velocityDetector;
    private final GeoJumpDetector geoJumpDetector;

    public List<String> evaluateSessionRules(List<AuditTrailEvent> sessionEvents) {
        List<AuditTrailEvent> ordered = new ArrayList<>(sessionEvents);
        ordered.sort(Comparator
                .comparing(AuditTrailEvent::getSequenceInSession, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AuditTrailEvent::getCreatedAt, Comparator.nullsLast(Instant::compareTo)));

        List<String> rules = new ArrayList<>();
        if (ordered.stream().anyMatch(this::isUnusualHour)) {
            rules.add("unusual_hour");
        }
        if (!ordered.isEmpty() && isSkipLogin(ordered.get(0))) {
            rules.add("skip_login");
        }
        if (hasRepeatedFail(ordered)) {
            rules.add("repeated_fail");
        }
        if (velocityDetector.isRapidFire(ordered)) {
            rules.add("rapid_fire");
        }
        if (geoJumpDetector.isGeoJump(ordered)) {
            rules.add("geo_jump");
        }
        if (ordered.stream().anyMatch(event -> defaultInt(event.getIsDeviceChanged()) == 1)) {
            rules.add("device_switch");
        }
        if (ordered.stream().anyMatch(event -> defaultInt(event.getDownloadsLast2Minutes()) >= 10)) {
            rules.add("download_spike");
        }
        if (ordered.stream().anyMatch(event -> defaultInt(event.getPingPongCount()) >= 2)) {
            rules.add("api_scraping");
        }
        if (velocityDetector.isSessionTimeout(ordered)) {
            rules.add("session_timeout");
        }
        return rules;
    }

    private boolean isUnusualHour(AuditTrailEvent event) {
        if (event.getCreatedAt() == null) {
            return false;
        }
        ZonedDateTime time = ZonedDateTime.ofInstant(event.getCreatedAt(), ZoneOffset.UTC);
        int hour = time.getHour();
        return hour >= ruleProperties.getUnusualHour().getStart()
                && hour <= ruleProperties.getUnusualHour().getEnd();
    }

    private boolean isSkipLogin(AuditTrailEvent firstEvent) {
        if (firstEvent == null || firstEvent.getAction() == null) {
            return false;
        }
        return ruleProperties.getSkipLogin().getAllowedActions().stream()
                .noneMatch(action -> com.noveocare.dataprocessor.ai.TextNormalization.equalsNormalized(action, firstEvent.getAction()));
    }

    private boolean hasRepeatedFail(List<AuditTrailEvent> ordered) {
        int consecutive = 0;
        for (AuditTrailEvent event : ordered) {
            if ("KO".equalsIgnoreCase(event.getStatus()) && isRepeatedFailType(event.getType())) {
                consecutive++;
                if (consecutive >= ruleProperties.getRepeatedFail().getConsecutive()) {
                    return true;
                }
            } else {
                consecutive = 0;
            }
        }
        return false;
    }

    private boolean isRepeatedFailType(String type) {
        if (type == null) {
            return false;
        }
        return ruleProperties.getRepeatedFail().getTypes().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(type));
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}