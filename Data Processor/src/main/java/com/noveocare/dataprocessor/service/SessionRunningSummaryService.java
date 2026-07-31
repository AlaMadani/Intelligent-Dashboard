package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionRunningSummary;
import com.noveocare.dataprocessor.dto.SessionSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintains a running summary of each active session in Redis, tracking first/last
 * event details, action counts, risk scores, and session-level aggregates.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionRunningSummaryService {

    /* Injected dependencies */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisCacheProperties cacheProperties;

    /* Constants for login/logout action matching */
    private static final Set<String> LOGIN_ACTIONS = Set.of("Connexion", "Connexion SSO", "Connexion en tant que");
    private static final Set<String> LOGOUT_ACTIONS = Set.of("Deconnexion", "SSO Disconnect");

    /* --- CRUD --- */

    public SessionRunningSummary loadOrCreate(String sessionId, String insuredId) {
        String key = key(sessionId);
        String json = redisTemplate.opsForValue().get(key);
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, SessionRunningSummary.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize running summary for session {}, creating new", sessionId, e);
            }
        }
        return createEmpty(sessionId, insuredId);
    }

    public void save(String sessionId, SessionRunningSummary summary) {
        String key = key(sessionId);
        try {
            String json = objectMapper.writeValueAsString(summary);
            redisTemplate.opsForValue().set(key, json, cacheProperties.getSessionBuffer());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize running summary for session {}", sessionId, e);
        }
    }

    public void delete(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    /* Creates a new empty running summary with default values */
    public SessionRunningSummary createEmpty(String sessionId, String insuredId) {
        return SessionRunningSummary.builder()
                .sessionId(sessionId)
                .insuredId(insuredId)
                .minInterActionMs(Long.MAX_VALUE)
                .build();
    }

    /* --- Update logic --- */

    public SessionRunningSummary updateWithEvent(SessionRunningSummary summary, AuditTrailEvent event) {
        if (summary.getFirstTimestamp() == null
                || isEarlier(event, summary.getFirstTimestamp(), summary.getFirstSequenceInSession())) {
            summary.setFirstTimestamp(event.getCreatedAt());
            summary.setFirstSequenceInSession(event.getSequenceInSession());
            summary.setFirstAction(safe(event.getAction()));
            summary.setFirstPage(safe(event.getPage()));
            summary.setFirstIp(safe(event.getIp()));
            summary.setFirstDevice(safe(event.getDevice()));
            summary.setFirstCountry(firstNonBlank(event.getIpCountry(), event.getCountryCode()));
            summary.setFirstStatus(safe(event.getStatus()));
        }

        if (summary.getLastTimestamp() == null
                || isLater(event, summary.getLastTimestamp(), summary.getLastSequenceInSession())) {
            summary.setLastTimestamp(event.getCreatedAt());
            summary.setLastSequenceInSession(event.getSequenceInSession());
            summary.setLastAction(safe(event.getAction()));
            summary.setLastPage(safe(event.getPage()));
            summary.setLastStatus(safe(event.getStatus()));
            summary.setLastIp(safe(event.getIp()));
            summary.setLastDevice(safe(event.getDevice()));
            summary.setLastCountry(firstNonBlank(event.getIpCountry(), event.getCountryCode()));
        }

        summary.setEventCount(summary.getEventCount() + 1);

        if ("OK".equalsIgnoreCase(event.getStatus())) {
            summary.setSuccessCount(summary.getSuccessCount() + 1);
            summary.setConsecutiveFailureCount(0);
        } else if ("KO".equalsIgnoreCase(event.getStatus())) {
            summary.setFailureCount(summary.getFailureCount() + 1);
            summary.setConsecutiveFailureCount(summary.getConsecutiveFailureCount() + 1);
            summary.setMaxConsecutiveFailureCount(Math.max(
                    summary.getMaxConsecutiveFailureCount(), summary.getConsecutiveFailureCount()));
        } else {
            summary.setConsecutiveFailureCount(0);
        }

        if (notBlank(event.getIp())) {
            summary.setUniqueIpsCount(Math.max(summary.getUniqueIpsCount(), defaultInt(event.getUniqueIpsInSession())));
        }
        if (notBlank(event.getDevice())) {
            summary.setUniqueDevicesCount(Math.max(summary.getUniqueDevicesCount(), defaultInt(event.getUniqueDevicesInSession())));
        }

        if (event.getRequestDataSizeBytes() != null) {
            summary.setTotalRequestBytes(summary.getTotalRequestBytes() + event.getRequestDataSizeBytes());
        }
        if (event.getResponseDataSizeBytes() != null) {
            summary.setTotalResponseBytes(summary.getTotalResponseBytes() + event.getResponseDataSizeBytes());
        }

        if (event.getTimeDeltaSinceLastAction() != null) {
            long delta = event.getTimeDeltaSinceLastAction();
            summary.setTotalInterActionSeconds(summary.getTotalInterActionSeconds() + delta);
            summary.setInterActionCount(summary.getInterActionCount() + 1);
        }

        if (event.getHourOfDay() != null) {
            int hour = event.getHourOfDay();
            if (hour >= 9 && hour <= 18) {
                summary.setBusinessHoursCount(summary.getBusinessHoursCount() + 1);
            }
        }
        if (defaultInt(event.getIsWeekend()) == 1) {
            summary.setWeekendCount(summary.getWeekendCount() + 1);
        }

        if (defaultInt(event.getIsIpChanged()) == 1) {
            summary.setIpChangedDetected(1);
        }
        if (defaultInt(event.getIsDeviceChanged()) == 1) {
            summary.setDeviceChangedDetected(1);
        }

        summary.setTotalDownloadActions(summary.getTotalDownloadActions() + defaultInt(event.getIsDownloadAction()));
        summary.setMaxDownloadsIn2Minutes(Math.max(summary.getMaxDownloadsIn2Minutes(), defaultInt(event.getDownloadsLast2Minutes())));
        summary.setPingPongCount(Math.max(summary.getPingPongCount(), defaultInt(event.getPingPongCount())));

        if (LOGIN_ACTIONS.contains(safe(event.getAction()))) {
            summary.setHasLogin(1);
            summary.setHasLoggedIn(1);
        }
        if (LOGOUT_ACTIONS.contains(safe(event.getAction()))) {
            summary.setHasLogout(1);
        }

        summary.setCumulativeKOs(Math.max(summary.getCumulativeKOs(), defaultInt(event.getCumulativeKOs())));
        summary.setCurrentKoStreak(defaultInt(event.getLongestKoStreak()));
        summary.setLongestKoStreak(Math.max(summary.getLongestKoStreak(), defaultInt(event.getLongestKoStreak())));

        if (event.getSessionRiskScore() != null) {
            summary.setRiskScoreSum(summary.getRiskScoreSum() + event.getSessionRiskScore());
            summary.setRiskScoreCount(summary.getRiskScoreCount() + 1);
            summary.setRiskScoreMax(Math.max(summary.getRiskScoreMax(), event.getSessionRiskScore()));
        }

        if (defaultInt(event.getIsAnomaly()) == 1) {
            summary.setAnomalyEventCount(summary.getAnomalyEventCount() + 1);
        }

        return summary;
    }

    /* Converts the running summary + recent events into a full SessionSummary DTO */
    public SessionSummary toLiveSessionSummary(SessionRunningSummary running, List<AuditTrailEvent> recentEvents) {
        if (running == null || running.getFirstTimestamp() == null) {
            return SessionSummary.builder()
                    .anomalyTypes(List.of())
                    .campaignIds(List.of())
                    .actionSequence(List.of())
                    .routeSequence(List.of())
                    .actionCounts(Map.of())
                    .primaryAnomalyType("normal")
                    .build();
        }

        if (recentEvents == null || recentEvents.isEmpty()) {
            return buildSummaryFromRunningOnly(running);
        }

        AuditTrailEvent last = recentEvents.get(recentEvents.size() - 1);
        long durationSeconds = running.getFirstTimestamp() != null && running.getLastTimestamp() != null
                ? Math.max(0, Duration.between(running.getFirstTimestamp(), running.getLastTimestamp()).getSeconds())
                : 0;

        String primaryAnomalyType = "normal";
        List<String> anomalyTypes = recentEvents.stream()
                .map(AuditTrailEvent::getAnomalyType)
                .filter(t -> notBlank(t) && !"normal".equalsIgnoreCase(t))
                .distinct()
                .toList();
        if (!anomalyTypes.isEmpty()) {
            primaryAnomalyType = anomalyTypes.get(0);
        }

        Set<String> uniqueActions = new LinkedHashSet<>();
        Set<String> uniqueRoutes = new LinkedHashSet<>();
        List<String> actionSequence = new java.util.ArrayList<>();
        Map<String, Long> actionCounts = new LinkedHashMap<>();
        for (AuditTrailEvent e : recentEvents) {
            String action = safe(e.getAction());
            uniqueActions.add(action);
            actionSequence.add(action);
            actionCounts.put(action, actionCounts.getOrDefault(action, 0L) + 1);
            if (notBlank(e.getRoute())) {
                uniqueRoutes.add(e.getRoute());
            }
        }

        double avgInterAction = running.getInterActionCount() > 0
                ? (double) running.getTotalInterActionSeconds() / running.getInterActionCount()
                : 0.0;

        return SessionSummary.builder()
                .sessionId(running.getSessionId())
                .insuredId(running.getInsuredId())
                .countryCode(running.getFirstCountry())
                .sessionStart(running.getFirstTimestamp())
                .sessionEnd(running.getLastTimestamp())
                .startHour(running.getFirstTimestamp() == null ? 0 : running.getFirstTimestamp().atZone(ZoneOffset.UTC).getHour())
                .endHour(running.getLastTimestamp() == null ? 0 : running.getLastTimestamp().atZone(ZoneOffset.UTC).getHour())
                .dayOfWeek(running.getFirstTimestamp() == null ? 0 : running.getFirstTimestamp().atZone(ZoneOffset.UTC).getDayOfWeek().getValue() - 1)
                .isWeekend(running.getWeekendCount() > 0 ? 1 : 0)
                .firstAction(running.getFirstAction())
                .lastAction(running.getLastAction())
                .firstRoute(safe(firstMatching(recentEvents, AuditTrailEvent::getRoute)))
                .lastRoute(safe(lastMatching(recentEvents, AuditTrailEvent::getRoute)))
                .totalEvents(running.getEventCount())
                .totalDurationSeconds(durationSeconds)
                .avgInterActionSeconds(avgInterAction)
                .minInterActionSeconds(running.getMinInterActionMs() == Long.MAX_VALUE ? 0.0 : (double) running.getMinInterActionMs())
                .maxInterActionSeconds((double) running.getMaxInterActionMs())
                .uniqueActions(uniqueActions.size())
                .uniqueRoutes(uniqueRoutes.size())
                .uniqueIpsUsed(running.getUniqueIpsCount())
                .uniqueDevicesUsed(running.getUniqueDevicesCount())
                .totalKOs(running.getFailureCount())
                .totalOKs(running.getSuccessCount())
                .longestKoStreak(running.getLongestKoStreak())
                .hasLogin(running.getHasLogin())
                .hasLogout(running.getHasLogout())
                .ipChanged(running.getIpChangedDetected())
                .deviceChanged(running.getDeviceChangedDetected())
                .totalDownloadActions(running.getTotalDownloadActions())
                .maxDownloadsIn2Minutes(running.getMaxDownloadsIn2Minutes())
                .pingPongCount(running.getPingPongCount())
                .riskScoreMax(running.getRiskScoreMax())
                .riskScoreAvg(running.getRiskScoreCount() > 0 ? running.getRiskScoreSum() / running.getRiskScoreCount() : 0.0)
                .endedAbruptly(running.getHasLogout() == 1 ? 0 : (running.getEventCount() >= 3 ? 1 : 0))
                .anomalyEventCount(running.getAnomalyEventCount())
                .primaryAnomalyType(primaryAnomalyType)
                .anomalyTypes(anomalyTypes)
                .campaignIds(List.of())
                .actionSequence(actionSequence)
                .routeSequence(new java.util.ArrayList<>(uniqueRoutes))
                .actionSequenceSignature(String.join(" > ", actionSequence))
                .routeSequenceSignature(String.join(" > ", uniqueRoutes))
                .actionCounts(actionCounts)
                .build();
    }

    /* Builds a summary from the running data alone when no recent events are available */
    private SessionSummary buildSummaryFromRunningOnly(SessionRunningSummary running) {
        long durationSeconds = running.getFirstTimestamp() != null && running.getLastTimestamp() != null
                ? Math.max(0, Duration.between(running.getFirstTimestamp(), running.getLastTimestamp()).getSeconds())
                : 0;
        double avgInterAction = running.getInterActionCount() > 0
                ? (double) running.getTotalInterActionSeconds() / running.getInterActionCount()
                : 0.0;
        List<String> actionSeq = List.of(running.getFirstAction(), running.getLastAction());

        return SessionSummary.builder()
                .sessionId(running.getSessionId())
                .insuredId(running.getInsuredId())
                .countryCode(running.getFirstCountry())
                .sessionStart(running.getFirstTimestamp())
                .sessionEnd(running.getLastTimestamp())
                .startHour(running.getFirstTimestamp() == null ? 0 : running.getFirstTimestamp().atZone(ZoneOffset.UTC).getHour())
                .endHour(running.getLastTimestamp() == null ? 0 : running.getLastTimestamp().atZone(ZoneOffset.UTC).getHour())
                .dayOfWeek(running.getFirstTimestamp() == null ? 0 : running.getFirstTimestamp().atZone(ZoneOffset.UTC).getDayOfWeek().getValue() - 1)
                .isWeekend(running.getWeekendCount() > 0 ? 1 : 0)
                .firstAction(running.getFirstAction())
                .lastAction(running.getLastAction())
                .totalEvents(running.getEventCount())
                .totalDurationSeconds(durationSeconds)
                .avgInterActionSeconds(avgInterAction)
                .minInterActionSeconds(running.getMinInterActionMs() == Long.MAX_VALUE ? 0.0 : (double) running.getMinInterActionMs())
                .maxInterActionSeconds((double) running.getMaxInterActionMs())
                .uniqueActions(Math.max(1, running.getUniqueActionsCount()))
                .uniqueRoutes(Math.max(1, running.getUniqueRoutesCount()))
                .uniqueIpsUsed(Math.max(1, running.getUniqueIpsCount()))
                .uniqueDevicesUsed(Math.max(1, running.getUniqueDevicesCount()))
                .totalKOs(running.getFailureCount())
                .totalOKs(running.getSuccessCount())
                .longestKoStreak(running.getLongestKoStreak())
                .hasLogin(running.getHasLogin())
                .hasLogout(running.getHasLogout())
                .ipChanged(running.getIpChangedDetected())
                .deviceChanged(running.getDeviceChangedDetected())
                .totalDownloadActions(running.getTotalDownloadActions())
                .maxDownloadsIn2Minutes(running.getMaxDownloadsIn2Minutes())
                .pingPongCount(running.getPingPongCount())
                .riskScoreMax(running.getRiskScoreMax())
                .riskScoreAvg(running.getRiskScoreCount() > 0 ? running.getRiskScoreSum() / running.getRiskScoreCount() : 0.0)
                .endedAbruptly(running.getHasLogout() == 1 ? 0 : (running.getEventCount() >= 3 ? 1 : 0))
                .anomalyEventCount(running.getAnomalyEventCount())
                .primaryAnomalyType("normal")
                .anomalyTypes(List.of())
                .campaignIds(List.of())
                .actionSequence(actionSeq)
                .routeSequence(List.of())
                .actionSequenceSignature(String.join(" > ", actionSeq))
                .routeSequenceSignature("")
                .actionCounts(Map.of(running.getFirstAction(), 1L))
                .build();
    }

    /* --- Comparison helpers --- */

    private boolean isEarlier(AuditTrailEvent event, Instant currentFirst, Integer currentSeq) {
        if (event.getCreatedAt() == null) return false;
        if (currentFirst == null) return true;
        int cmp = event.getCreatedAt().compareTo(currentFirst);
        if (cmp < 0) return true;
        if (cmp == 0 && event.getSequenceInSession() != null && currentSeq != null) {
            return event.getSequenceInSession() < currentSeq;
        }
        return false;
    }

    private boolean isLater(AuditTrailEvent event, Instant currentLast, Integer currentSeq) {
        if (event.getCreatedAt() == null) return false;
        if (currentLast == null) return true;
        int cmp = event.getCreatedAt().compareTo(currentLast);
        if (cmp > 0) return true;
        if (cmp == 0 && event.getSequenceInSession() != null && currentSeq != null) {
            return event.getSequenceInSession() > currentSeq;
        }
        return false;
    }

    private String firstNonBlank(String first, String second) {
        return notBlank(first) ? first : second;
    }

    /* Returns empty string for null values */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /* Returns true if the string is non-null and non-blank */
    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /* Returns 0 for null Integer values */
    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    /* Returns the first non-blank value extracted from events */
    private <T> String firstMatching(List<AuditTrailEvent> events, java.util.function.Function<AuditTrailEvent, String> extractor) {
        for (AuditTrailEvent e : events) {
            String val = extractor.apply(e);
            if (notBlank(val)) return val;
        }
        return null;
    }

    /* Returns the last non-blank value extracted from events */
    private <T> String lastMatching(List<AuditTrailEvent> events, java.util.function.Function<AuditTrailEvent, String> extractor) {
        String last = null;
        for (AuditTrailEvent e : events) {
            String val = extractor.apply(e);
            if (notBlank(val)) last = val;
        }
        return last;
    }

    /* Builds the Redis key for a session's running summary */
    private String key(String sessionId) {
        return CacheKeys.sessionRunningSummaryKey(sessionId);
    }
}