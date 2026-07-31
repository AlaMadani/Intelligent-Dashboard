package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.LiveStatsProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RiskProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.entity.UserRiskProfile;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import com.noveocare.dataprocessor.repository.UserRiskProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records per-minute and per-day counters for events, alerts, downloads, actions,
 * and countries in Redis. Also computes user risk profiles and live stats snapshots.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StatisticsService {

    /* Time formatting and download detection constants */
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final String[] DOWNLOAD_HINTS = {
            "download", "telecharg", "wallet", "certificate", "refund", "tp-card", "tp card", "document"
    };

    /* Injected dependencies */
    private final RedisCacheService redisCacheService;
    private final StringRedisTemplate redisTemplate;
    private final RedisCacheProperties cacheProperties;
    private final LiveStatsProperties liveStatsProperties;
    private final RiskProperties riskProperties;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final UserRiskProfileRepository userRiskProfileRepository;
    private final ObjectMapper objectMapper;
    private final com.noveocare.dataprocessor.config.RedisPubSubProperties pubSubProperties;

    /* --- Event recording --- */

    public void recordEvent(AuditTrailEvent event) {
        Instant eventTime = event.getCreatedAt() != null ? event.getCreatedAt() : Instant.now();
        boolean useIngestionTime = "ingestion".equalsIgnoreCase(liveStatsProperties.getTimeBasis());
        LocalDateTime time;
        if (useIngestionTime) {
            time = LocalDateTime.now(ZoneOffset.UTC);
        } else {
            time = LocalDateTime.ofInstant(eventTime, ZoneOffset.UTC);
        }
        String minute = time.format(MINUTE_FORMAT);
        String day = time.toLocalDate().toString();

        redisCacheService.increment(CacheKeys.eventsMinuteKey(minute), 1, cacheProperties.getLiveStats());
        redisCacheService.increment(CacheKeys.eventsDayKey(day), 1, cacheProperties.getLiveStats());
        redisCacheService.incrementHash(
                CacheKeys.actionsMinuteKey(minute),
                event.getAction() == null ? "UNKNOWN" : event.getAction(),
                1,
                cacheProperties.getLiveStats());
        redisCacheService.incrementHash(
                CacheKeys.countriesMinuteKey(minute),
                event.getCountryCode() == null ? "UNKNOWN" : event.getCountryCode(),
                1,
                cacheProperties.getLiveStats());
        redisCacheService.incrementHash(CacheKeys.koMinuteKey(minute), "total", 1, cacheProperties.getLiveStats());
        if ("KO".equalsIgnoreCase(event.getStatus())) {
            redisCacheService.incrementHash(CacheKeys.koMinuteKey(minute), "ko", 1, cacheProperties.getLiveStats());
        }
        if (isDownloadEvent(event)) {
            redisCacheService.increment(CacheKeys.downloadsMinuteKey(minute), 1, cacheProperties.getLiveStats());
            redisCacheService.increment(CacheKeys.downloadsDayKey(day), 1, cacheProperties.getLiveStats());
        }
    }

    /* Increments alert counters for the minute and day of detection */
    public void recordAnomalyAlert(Instant detectedAt) {
        LocalDateTime time = LocalDateTime.ofInstant(
                detectedAt == null ? Instant.now() : detectedAt,
                ZoneOffset.UTC);
        redisCacheService.increment(
                CacheKeys.alertsMinuteKey(time.format(MINUTE_FORMAT)),
                1,
                cacheProperties.getLiveStats());
        redisCacheService.increment(
                CacheKeys.alertsDayKey(time.toLocalDate().toString()),
                1,
                cacheProperties.getLiveStats());
    }

    /* --- User risk profile --- */

    public void updateUserRiskProfile(String insuredId) {
        long startMs = System.currentTimeMillis();
        Instant now = Instant.now();
        Instant since30 = now.minus(Duration.ofDays(30));
        Instant since7 = now.minus(Duration.ofDays(7));

        long queryStart = System.currentTimeMillis();
        List<SessionAnalysis> sessions30 = sessionAnalysisRepository
                .findByInsuredIdAndEndTimeAfterOrderByEndTimeDesc(insuredId, since30);
        long queryMs = System.currentTimeMillis() - queryStart;
        if (queryMs > 1000) {
            log.warn("SQL slow: user_risk_profile sessions30 query took {}ms for insuredId={}", queryMs, insuredId);
        }

        int sessions30Count = sessions30.size();
        int sessions7Count = (int) sessions30.stream()
                .filter(session -> session.getEndTime() != null && session.getEndTime().isAfter(since7))
                .count();
        long anomalies30Count = sessions30.stream()
                .filter(session -> session.getFinalRiskScore() != null && session.getFinalRiskScore() >= riskProperties.getMediumThreshold())
                .count();

        double anomalyRate = sessions30Count == 0 ? 0.0 : (double) anomalies30Count / sessions30Count;
        double averageRisk = sessions30.stream()
                .map(SessionAnalysis::getFinalRiskScore)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        double maxRisk = sessions30.stream()
                .map(SessionAnalysis::getFinalRiskScore)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

        String lastAnomalyType = sessions30.stream()
                .filter(session -> session.getFinalRiskScore() != null && session.getFinalRiskScore() >= riskProperties.getMediumThreshold())
                .map(SessionAnalysis::getAnomalyTypeSource)
                .filter(type -> type != null && !type.isBlank())
                .findFirst()
                .orElse(null);

        long consecutiveQueryStart = System.currentTimeMillis();
        int consecutiveClean = 0;
        for (SessionAnalysis session : sessionAnalysisRepository.findTop200ByInsuredIdOrderByEndTimeDesc(insuredId)) {
            if (session.getFinalRiskScore() != null && session.getFinalRiskScore() >= riskProperties.getMediumThreshold()) {
                break;
            }
            consecutiveClean++;
        }
        long consecutiveMs = System.currentTimeMillis() - consecutiveQueryStart;
        if (consecutiveMs > 1000) {
            log.warn("SQL slow: user_risk_profile consecutive query took {}ms for insuredId={}", consecutiveMs, insuredId);
        }

        Map<String, Long> aggregatedActions = new HashMap<>();
        double durationSum = 0.0;
        int durationCount = 0;
        for (SessionAnalysis session : sessions30) {
            durationSum += session.getSessionDurationSeconds() == null ? 0.0 : session.getSessionDurationSeconds();
            durationCount++;
            if (session.getActionCountsJson() != null) {
                try {
                    Map<String, Long> counts = objectMapper.readValue(
                            session.getActionCountsJson(),
                            new TypeReference<Map<String, Long>>() { });
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        aggregatedActions.put(entry.getKey(), aggregatedActions.getOrDefault(entry.getKey(), 0L) + entry.getValue());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse action counts JSON for session {}", session.getId(), e);
                }
            }
        }

        String mostFrequentAction = aggregatedActions.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        double avgSessionDuration = durationCount == 0 ? 0.0 : durationSum / durationCount;

        boolean highRiskTypeSeen = sessions30.stream()
                .map(SessionAnalysis::getAnomalyTypeSource)
                .anyMatch(type -> type != null && isHighRiskType(type));

        long saveStart = System.currentTimeMillis();
        UserRiskProfile profile = userRiskProfileRepository.findByInsuredId(insuredId)
                .orElseGet(UserRiskProfile::new);
        profile.setInsuredId(insuredId);
        profile.setLastUpdated(now);
        profile.setAnomalyCount7d((int) sessions30.stream()
                .filter(session -> session.getEndTime() != null && session.getEndTime().isAfter(since7))
                .filter(session -> session.getFinalRiskScore() != null && session.getFinalRiskScore() >= riskProperties.getMediumThreshold())
                .count());
        profile.setAnomalyCount30d((int) anomalies30Count);
        profile.setLastAnomalyType(lastAnomalyType);
        profile.setRiskTier(resolveRiskTier(anomalyRate, averageRisk, maxRisk, highRiskTypeSeen));
        profile.setAnomalyRate30d(anomalyRate);
        profile.setSessions7d(sessions7Count);
        profile.setSessions30d(sessions30Count);
        profile.setMostFrequentAction30d(mostFrequentAction);
        profile.setAvgSessionDuration30d(avgSessionDuration);
        profile.setConsecutiveCleanSessions(consecutiveClean);
        userRiskProfileRepository.save(profile);
        long saveMs = System.currentTimeMillis() - saveStart;
        if (saveMs > 1000) {
            log.warn("SQL slow: user_risk_profile save took {}ms for insuredId={}", saveMs, insuredId);
        }

        long redisStart = System.currentTimeMillis();
        redisCacheService.setJson(CacheKeys.riskKey(insuredId), profile, cacheProperties.getRisk());
        long redisMs = System.currentTimeMillis() - redisStart;
        if (redisMs > 500) {
            log.warn("Redis slow: user_risk_profile set took {}ms for insuredId={}", redisMs, insuredId);
        }

        long totalMs = System.currentTimeMillis() - startMs;
        if (totalMs > 2000) {
            log.warn("SQL slow: user_risk_profile total took {}ms for insuredId={}", totalMs, insuredId);
        }
    }

    /* --- Live stats snapshot --- */

    public void refreshLiveStatsSnapshot() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<String> eventKeys = lastMinuteKeys(now, Math.max(1, (int) Math.ceil(liveStatsProperties.getEventsWindowSeconds() / 60.0)));
        List<String> actionKeys = lastMinuteKeys(now, liveStatsProperties.getActionsWindowMinutes());
        List<String> countryKeys = lastMinuteKeys(now, liveStatsProperties.getCountriesWindowMinutes());
        List<String> alertKeys = lastMinuteKeys(now, liveStatsProperties.getAnomalyWindowMinutes());
        List<String> koKeys = lastMinuteKeys(now, liveStatsProperties.getKoWindowMinutes());

        long eventsLastWindow = sumCounters(eventKeys, CacheKeys::eventsMinuteKey);
        long eventsLastHour = sumCounters(alertKeys, CacheKeys::eventsMinuteKey);
        long alertsLastWindow = sumCounters(alertKeys, CacheKeys::alertsMinuteKey);
        double anomalyRate = eventsLastHour == 0 ? 0.0 : (double) alertsLastWindow / eventsLastHour;

        Map<String, Long> actionCounts = sumHashes(actionKeys, CacheKeys::actionsMinuteKey);
        Map<String, Long> countryCounts = sumHashes(countryKeys, CacheKeys::countriesMinuteKey);
        Map<String, Long> koCounts = sumHashes(koKeys, CacheKeys::koMinuteKey);

        long koTotal = koCounts.getOrDefault("total", 0L);
        long koValue = koCounts.getOrDefault("ko", 0L);
        double koRate = koTotal == 0 ? 0.0 : (double) koValue / koTotal;

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("active_sessions", countActiveSessions());
        snapshot.put("events_per_minute", eventsLastWindow);
        snapshot.put("top_actions_last_15m", topN(actionCounts, 5));

        snapshot.put("top_countries_right_now", topN(countryCounts, 3));
        snapshot.put("anomaly_alert_rate_last_hour", anomalyRate);
        snapshot.put("current_anomaly_rate", anomalyRate);
        snapshot.put("ko_rate_last_15m", koRate);
        snapshot.put("global_risk_level", resolveGlobalRiskLevel(anomalyRate, koRate, alertsLastWindow));
        snapshot.put("generated_at", Instant.now().toString());

        redisCacheService.setJson(
                CacheKeys.liveStatsKey(now.toLocalDate().toString()),
                snapshot,
                cacheProperties.getLiveStats());

        redisCacheService.publishJson(pubSubProperties.getLiveStatsChannel(), Map.of("refresh", "stats"));
    }

    /* --- Aggregation queries --- */

    public long countEventsLastMinutes(int minutes) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return sumCounters(lastMinuteKeys(now, Math.max(1, minutes)), CacheKeys::eventsMinuteKey);
    }

    public long countEventsForDate(LocalDate date) {
        LocalDate resolvedDate = resolveDate(date);
        Long cached = readCounter(CacheKeys.eventsDayKey(resolvedDate.toString()));
        return cached != null ? cached : sumCounters(fullDayMinuteKeys(resolvedDate), CacheKeys::eventsMinuteKey);
    }

    public long countAlertsForDate(LocalDate date) {
        LocalDate resolvedDate = resolveDate(date);
        Long cached = readCounter(CacheKeys.alertsDayKey(resolvedDate.toString()));
        return cached != null ? cached : sumCounters(fullDayMinuteKeys(resolvedDate), CacheKeys::alertsMinuteKey);
    }

    public long countDownloadsForDate(LocalDate date) {
        LocalDate resolvedDate = resolveDate(date);
        Long cached = readCounter(CacheKeys.downloadsDayKey(resolvedDate.toString()));
        return cached != null ? cached : sumCounters(fullDayMinuteKeys(resolvedDate), CacheKeys::downloadsMinuteKey);
    }

    /* --- Internal helpers --- */

    private long countActiveSessions() {
        Set<String> keys = redisCacheService.getSetMembers(CacheKeys.activeSessionInsightsIndexKey());
        if (keys.isEmpty()) {
            keys = seedInsightIndex();
        }
        if (keys.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        for (String key : keys) {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                count++;
                continue;
            }
            removeStaleInsightIndexEntry(key);
        }
        return count;
    }

    private Set<String> seedInsightIndex() {
        Set<String> scannedKeys = redisTemplate.keys(CacheKeys.sessionInsightPattern());
        if (scannedKeys == null || scannedKeys.isEmpty()) {
            return Set.of();
        }
        for (String key : scannedKeys) {
            redisCacheService.addSetMember(CacheKeys.activeSessionInsightsIndexKey(), key);
            String insuredId = insuredIdFromInsightKey(key);
            if (insuredId != null) {
                redisCacheService.addSetMember(CacheKeys.activeSessionInsightsIndexKey(insuredId), key);
            }
        }
        return scannedKeys;
    }

    /* Generates minute-key suffixes for the last N minutes */
    private List<String> lastMinuteKeys(LocalDateTime now, int minutes) {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < minutes; index++) {
            keys.add(now.minusMinutes(index).format(MINUTE_FORMAT));
        }
        return keys;
    }

    /* Generates all minute-key suffixes for a full day (24*60 keys) */
    private List<String> fullDayMinuteKeys(LocalDate date) {
        List<String> keys = new ArrayList<>(24 * 60);
        LocalDateTime start = resolveDate(date).atStartOfDay();
        for (int minute = 0; minute < 24 * 60; minute++) {
            keys.add(start.plusMinutes(minute).format(MINUTE_FORMAT));
        }
        return keys;
    }

    /* Sums Redis string counters across a list of minute keys */
    private long sumCounters(List<String> minuteKeys, java.util.function.Function<String, String> keyFn) {
        List<String> fullKeys = minuteKeys.stream().map(keyFn).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(fullKeys);
        if (values == null) return 0L;
        long sum = 0L;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                try {
                    sum += Long.parseLong(value);
                } catch (NumberFormatException ignored) { }
            }
        }
        return sum;
    }

    /* Aggregates Redis hash counters across a list of minute keys */
    private Map<String, Long> sumHashes(List<String> minuteKeys, java.util.function.Function<String, String> keyFn) {
        Map<String, Long> aggregated = new HashMap<>();
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        for (String minute : minuteKeys) {
            Map<String, String> entries = hashOps.entries(keyFn.apply(minute));
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                try {
                    aggregated.put(entry.getKey(),
                            aggregated.getOrDefault(entry.getKey(), 0L) + Long.parseLong(entry.getValue()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return aggregated;
    }

    /* Returns the top-N entries from a map sorted by value descending */
    private Map<String, Long> topN(Map<String, Long> counts, int limit) {
        Map<String, Long> ordered = new java.util.LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /* Resolves risk tier (HIGH / MEDIUM / LOW) from profile metrics */
    private String resolveRiskTier(double anomalyRate, double averageRisk, double maxRisk, boolean highRiskTypeSeen) {
        if (highRiskTypeSeen || maxRisk >= 80.0) {
            return "HIGH";
        }
        if (anomalyRate >= riskProperties.getHighThreshold() || averageRisk >= 60.0) {
            return "HIGH";
        }
        if (anomalyRate >= riskProperties.getMediumThreshold() || averageRisk >= 35.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /* Checks whether the anomaly type is in the high-risk list */
    private boolean isHighRiskType(String type) {
        for (String highRisk : riskProperties.getHighRiskTypes()) {
            if (highRisk.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    /* Resolves the global risk level from live snapshot metrics */
    private String resolveGlobalRiskLevel(double anomalyRate, double koRate, long alertsLastWindow) {
        if (alertsLastWindow >= 3 || anomalyRate >= riskProperties.getHighThreshold() || koRate >= 0.20) {
            return "HIGH";
        }
        if (alertsLastWindow >= 1 || anomalyRate >= riskProperties.getMediumThreshold() || koRate >= 0.10) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /* Returns the given date or today if null */
    private LocalDate resolveDate(LocalDate date) {
        return date == null ? LocalDate.now(ZoneOffset.UTC) : date;
    }

    /* Reads a counter from Redis, returning null if missing */
    private Long readCounter(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /* Removes a stale key from the insight index sets */
    private void removeStaleInsightIndexEntry(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        redisCacheService.removeSetMember(CacheKeys.activeSessionInsightsIndexKey(), key);
        String insuredId = insuredIdFromInsightKey(key);
        if (insuredId != null) {
            redisCacheService.removeSetMember(CacheKeys.activeSessionInsightsIndexKey(insuredId), key);
        }
    }

    /* Extracts insuredId from a session insight Redis key */
    private String insuredIdFromInsightKey(String key) {
        String prefix = "session:insight:";
        if (!key.startsWith(prefix)) {
            return null;
        }
        String remainder = key.substring(prefix.length());
        int separator = remainder.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        return remainder.substring(0, separator);
    }

    /* Determines whether the event is a download based on flag or action name hints */
    private boolean isDownloadEvent(AuditTrailEvent event) {
        if (event == null) {
            return false;
        }
        if (event.getIsDownloadAction() != null && event.getIsDownloadAction() == 1) {
            return true;
        }
        if (event.getAction() == null || event.getAction().isBlank()) {
            return false;
        }
        String normalized = event.getAction().toLowerCase(java.util.Locale.ROOT);
        for (String hint : DOWNLOAD_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}