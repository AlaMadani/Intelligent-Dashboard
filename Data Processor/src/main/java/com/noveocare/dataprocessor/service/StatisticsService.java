package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.VocabService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.LiveStatsProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RiskProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionStats;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maintains live counters, session-level aggregates, and user-level risk
 * profiles derived from processed audit sessions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StatisticsService {

    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final RedisCacheService redisCacheService;
    private final StringRedisTemplate redisTemplate;
    private final RedisCacheProperties cacheProperties;
    private final LiveStatsProperties liveStatsProperties;
    private final RiskProperties riskProperties;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final UserRiskProfileRepository userRiskProfileRepository;
    private final VocabService vocabService;
    private final ObjectMapper objectMapper;

    public void recordEvent(AuditTrailEvent event) {
        // Bucket each event by minute so Redis can serve rolling dashboard windows cheaply.
        LocalDateTime now = LocalDateTime.ofInstant(
                event.getCreatedAt() == null ? Instant.now() : event.getCreatedAt(),
                ZoneOffset.UTC);
        String minute = now.format(MINUTE_FORMAT);
        String date = now.toLocalDate().toString();

        Duration ttl = cacheProperties.getLiveStats();
        redisCacheService.increment(CacheKeys.eventsMinuteKey(minute), 1, ttl);

        // Keep per-minute distributions that will later be merged into live snapshots.
        String actionLabel = event.getAction() == null ? "UNKNOWN" : event.getAction();
        redisCacheService.incrementHash(CacheKeys.actionsMinuteKey(minute), actionLabel, 1, ttl);

        String country = event.getCountryCode() == null ? "UNKNOWN" : event.getCountryCode();
        redisCacheService.incrementHash(CacheKeys.countriesMinuteKey(minute), country, 1, ttl);

        redisCacheService.incrementHash(CacheKeys.koMinuteKey(minute), "total", 1, ttl);
        if ("KO".equalsIgnoreCase(event.getStatus())) {
            redisCacheService.incrementHash(CacheKeys.koMinuteKey(minute), "ko", 1, ttl);
        }

        int actionId = vocabService.actionId(event.getAction());
        redisCacheService.incrementHash(CacheKeys.dailyActionCountsKey(date), String.valueOf(actionId), 1, ttl);
    }

    public void recordAnomalyAlert(Instant detectedAt) {
        // Alerts use the same minute bucketing strategy as raw events.
        LocalDateTime time = LocalDateTime.ofInstant(detectedAt, ZoneOffset.UTC);
        String minute = time.format(MINUTE_FORMAT);
        redisCacheService.increment(CacheKeys.alertsMinuteKey(minute), 1, cacheProperties.getLiveStats());
    }

    public SessionStats computeSessionStats(List<AuditTrailEvent> events) {
        // Return an all-zero aggregate for empty sessions so downstream persistence stays simple.
        if (events == null || events.isEmpty()) {
            return SessionStats.builder()
                    .sessionDurationSeconds(0)
                    .sessionLength(0)
                    .uniqueActionCount(0)
                    .koRate(0.0)
                    .meanDeltaSeconds(0.0)
                    .actionDiversity(0.0)
                    .actionCounts(Map.of())
                    .build();
        }

        // Rebuild the chronological session order before computing durations and transitions.
        List<AuditTrailEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator
                .comparing(AuditTrailEvent::getSequenceInSession, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AuditTrailEvent::getCreatedAt, Comparator.nullsLast(Instant::compareTo)));

        Instant start = ordered.get(0).getCreatedAt();
        Instant end = ordered.get(ordered.size() - 1).getCreatedAt();
        long durationSeconds = start != null && end != null
                ? Math.max(0, Duration.between(start, end).toSeconds())
                : 0;

        int total = ordered.size();
        Map<String, Long> actionCounts = new HashMap<>();
        int koCount = 0;
        double deltaSum = 0.0;
        int deltaCount = 0;
        Instant prevTime = null;

        // Count actions, KO statuses, and inter-event delays in a single pass.
        for (AuditTrailEvent event : ordered) {
            String action = event.getAction() == null ? "UNKNOWN" : event.getAction();
            actionCounts.put(action, actionCounts.getOrDefault(action, 0L) + 1);
            if ("KO".equalsIgnoreCase(event.getStatus())) {
                koCount++;
            }
            if (prevTime != null && event.getCreatedAt() != null) {
                double delta = Duration.between(prevTime, event.getCreatedAt()).toMillis() / 1000.0;
                if (delta >= 0) {
                    deltaSum += delta;
                    deltaCount++;
                }
            }
            prevTime = event.getCreatedAt();
        }

        int uniqueActions = actionCounts.size();
        double koRate = total == 0 ? 0.0 : (double) koCount / total;
        double meanDelta = deltaCount == 0 ? 0.0 : deltaSum / deltaCount;
        double diversity = shannonEntropy(actionCounts, total);

        // Package all derived metrics into the DTO stored with the session analysis.
        return SessionStats.builder()
                .sessionDurationSeconds(durationSeconds)
                .sessionLength(total)
                .uniqueActionCount(uniqueActions)
                .koRate(koRate)
                .meanDeltaSeconds(meanDelta)
                .actionDiversity(diversity)
                .actionCounts(actionCounts)
                .build();
    }

    public void updateUserRiskProfile(String insuredId) {
        // Recompute the whole profile from recent persisted sessions to avoid cache drift.
        Instant now = Instant.now();
        Instant since30 = now.minus(Duration.ofDays(30));
        Instant since7 = now.minus(Duration.ofDays(7));

        // Load the recent history once, then derive the rolling counts and anomaly rates from it.
        List<SessionAnalysis> sessions30 = sessionAnalysisRepository
                .findByInsuredIdAndEndTimeAfterOrderByEndTimeDesc(insuredId, since30);
        int sessions30Count = sessions30.size();
        int sessions7Count = (int) sessions30.stream()
                .filter(session -> session.getEndTime() != null && session.getEndTime().isAfter(since7))
                .count();
        long anomalies30Count = sessions30.stream()
                .filter(session -> Boolean.TRUE.equals(session.getIsAnomaly()))
                .count();

        double anomalyRate = sessions30Count == 0 ? 0.0 : (double) anomalies30Count / sessions30Count;

        String lastAnomalyType = sessions30.stream()
                .filter(session -> Boolean.TRUE.equals(session.getIsAnomaly()))
                .map(SessionAnalysis::getAnomalyType)
                .filter(type -> type != null && !type.isBlank())
                .findFirst()
                .orElse(null);

        // Count the clean streak from most recent to oldest until an anomaly breaks it.
        int consecutiveClean = 0;
        for (SessionAnalysis session : sessionAnalysisRepository.findTop200ByInsuredIdOrderByEndTimeDesc(insuredId)) {
            if (Boolean.TRUE.equals(session.getIsAnomaly())) {
                break;
            }
            consecutiveClean++;
        }

        Map<String, Long> aggregatedActions = new HashMap<>();
        double durationSum = 0.0;
        int durationCount = 0;
        // Rehydrate persisted action-count JSON to identify dominant user behavior over 30 days.
        for (SessionAnalysis session : sessions30) {
            durationSum += session.getSessionDurationSeconds() == null ? 0.0 : session.getSessionDurationSeconds();
            durationCount++;
            if (session.getActionCountsJson() != null) {
                try {
                    Map<String, Long> counts = objectMapper.readValue(
                            session.getActionCountsJson(), new TypeReference<Map<String, Long>>() {});
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        aggregatedActions.put(entry.getKey(),
                                aggregatedActions.getOrDefault(entry.getKey(), 0L) + entry.getValue());
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
                .map(SessionAnalysis::getAnomalyType)
                .anyMatch(type -> type != null && isHighRiskType(type));

        // Translate the rolling metrics into the coarse risk tier used by downstream consumers.
        String riskTier = resolveRiskTier(anomalyRate, highRiskTypeSeen);

        UserRiskProfile profile = userRiskProfileRepository.findByInsuredId(insuredId)
                .orElseGet(UserRiskProfile::new);
        profile.setInsuredId(insuredId);
        profile.setLastUpdated(now);
        profile.setAnomalyCount7d((int) sessions30.stream()
                .filter(session -> session.getEndTime() != null && session.getEndTime().isAfter(since7))
                .filter(session -> Boolean.TRUE.equals(session.getIsAnomaly()))
                .count());
        profile.setAnomalyCount30d((int) anomalies30Count);
        profile.setLastAnomalyType(lastAnomalyType);
        profile.setRiskTier(riskTier);
        profile.setAnomalyRate30d(anomalyRate);
        profile.setSessions7d(sessions7Count);
        profile.setSessions30d(sessions30Count);
        profile.setMostFrequentAction30d(mostFrequentAction);
        profile.setAvgSessionDuration30d(avgSessionDuration);
        profile.setConsecutiveCleanSessions(consecutiveClean);

        userRiskProfileRepository.save(profile);

        // Cache the latest profile so read-heavy consumers avoid hitting SQL for every lookup.
        redisCacheService.setJson(CacheKeys.riskKey(insuredId), profile, cacheProperties.getRisk());
    }

    public void refreshLiveStatsSnapshot() {
        // Build the rolling minute windows required by the live dashboard.
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

        // Merge per-minute hashes into a single snapshot view.
        Map<String, Long> actionCounts = sumHashes(actionKeys, CacheKeys::actionsMinuteKey);
        Map<String, Long> countryCounts = sumHashes(countryKeys, CacheKeys::countriesMinuteKey);
        Map<String, Long> koCounts = sumHashes(koKeys, CacheKeys::koMinuteKey);

        long koTotal = koCounts.getOrDefault("total", 0L);
        long koValue = koCounts.getOrDefault("ko", 0L);
        double koRate = koTotal == 0 ? 0.0 : (double) koValue / koTotal;

        // Publish the small dashboard payload that external clients will poll from Redis.
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("active_sessions", countActiveSessions());
        snapshot.put("events_per_minute", eventsLastWindow);
        snapshot.put("top_actions_last_15m", topN(actionCounts, 5));
        snapshot.put("top_countries_right_now", topN(countryCounts, 3));
        snapshot.put("anomaly_alert_rate_last_hour", anomalyRate);
        snapshot.put("ko_rate_last_15m", koRate);
        snapshot.put("generated_at", Instant.now().toString());

        String dateKey = now.toLocalDate().toString();
        redisCacheService.setJson(CacheKeys.liveStatsKey(dateKey), snapshot, cacheProperties.getLiveStats());
    }

    private long countActiveSessions() {
        // Session buffers use the "session:*" pattern, so counting keys gives a cheap active estimate.
        return Optional.ofNullable(redisTemplate.keys("session:*"))
                .map(set -> (long) set.size())
                .orElse(0L);
    }

    private List<String> lastMinuteKeys(LocalDateTime now, int minutes) {
        // Generate minute suffixes from newest to oldest for window aggregation.
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < minutes; i++) {
            LocalDateTime time = now.minusMinutes(i);
            keys.add(time.format(MINUTE_FORMAT));
        }
        return keys;
    }

    private long sumCounters(List<String> minuteKeys, java.util.function.Function<String, String> keyFn) {
        // Ignore malformed counter values instead of failing the whole stats refresh.
        long sum = 0;
        for (String minute : minuteKeys) {
            String value = redisTemplate.opsForValue().get(keyFn.apply(minute));
            if (value != null) {
                try {
                    sum += Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return sum;
    }

    private Map<String, Long> sumHashes(List<String> minuteKeys, java.util.function.Function<String, String> keyFn) {
        // Combine hash buckets from several minute windows into one aggregated map.
        Map<String, Long> aggregated = new HashMap<>();
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        for (String minute : minuteKeys) {
            Map<String, String> entries = hashOps.entries(keyFn.apply(minute));
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                try {
                    long value = Long.parseLong(entry.getValue());
                    aggregated.put(entry.getKey(), aggregated.getOrDefault(entry.getKey(), 0L) + value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return aggregated;
    }

    private Map<String, Long> topN(Map<String, Long> counts, int limit) {
        // Preserve descending order in the returned map so consumers can render directly.
        Map<String, Long> ordered = new java.util.LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    private double shannonEntropy(Map<String, Long> counts, int total) {
        // Entropy gives a simple measure of how diverse the action mix was inside the session.
        if (total == 0) {
            return 0.0;
        }
        double entropy = 0.0;
        for (long count : counts.values()) {
            double p = (double) count / total;
            entropy -= p * Math.log(p);
        }
        return entropy;
    }

    private String resolveRiskTier(double anomalyRate, boolean highRiskTypeSeen) {
        // High-risk anomaly types override the pure rate-based thresholds.
        if (highRiskTypeSeen) {
            return "HIGH";
        }
        if (anomalyRate > riskProperties.getHighThreshold()) {
            return "HIGH";
        }
        if (anomalyRate >= riskProperties.getMediumThreshold()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean isHighRiskType(String type) {
        // Keep comparison case-insensitive because classifier labels may vary in casing.
        for (String highRisk : riskProperties.getHighRiskTypes()) {
            if (highRisk.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }
}
