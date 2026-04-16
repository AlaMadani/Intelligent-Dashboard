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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final ObjectMapper objectMapper;

    public void recordEvent(AuditTrailEvent event) {
        LocalDateTime time = LocalDateTime.ofInstant(
                event.getCreatedAt() == null ? Instant.now() : event.getCreatedAt(),
                ZoneOffset.UTC);
        String minute = time.format(MINUTE_FORMAT);

        redisCacheService.increment(CacheKeys.eventsMinuteKey(minute), 1, cacheProperties.getLiveStats());
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
    }

    public void recordAnomalyAlert(Instant detectedAt) {
        LocalDateTime time = LocalDateTime.ofInstant(
                detectedAt == null ? Instant.now() : detectedAt,
                ZoneOffset.UTC);
        redisCacheService.increment(
                CacheKeys.alertsMinuteKey(time.format(MINUTE_FORMAT)),
                1,
                cacheProperties.getLiveStats());
    }

    public void updateUserRiskProfile(String insuredId) {
        Instant now = Instant.now();
        Instant since30 = now.minus(Duration.ofDays(30));
        Instant since7 = now.minus(Duration.ofDays(7));

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
        double averageRisk = sessions30.stream()
                .map(SessionAnalysis::getEnsembleRiskScore)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        double maxRisk = sessions30.stream()
                .map(SessionAnalysis::getEnsembleRiskScore)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

        String lastAnomalyType = sessions30.stream()
                .filter(session -> Boolean.TRUE.equals(session.getIsAnomaly()))
                .map(SessionAnalysis::getAnomalyType)
                .filter(type -> type != null && !type.isBlank())
                .findFirst()
                .orElse(null);

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
                .map(SessionAnalysis::getAnomalyType)
                .anyMatch(type -> type != null && isHighRiskType(type));

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
        profile.setRiskTier(resolveRiskTier(anomalyRate, averageRisk, maxRisk, highRiskTypeSeen));
        profile.setAnomalyRate30d(anomalyRate);
        profile.setSessions7d(sessions7Count);
        profile.setSessions30d(sessions30Count);
        profile.setMostFrequentAction30d(mostFrequentAction);
        profile.setAvgSessionDuration30d(avgSessionDuration);
        profile.setConsecutiveCleanSessions(consecutiveClean);
        userRiskProfileRepository.save(profile);

        redisCacheService.setJson(CacheKeys.riskKey(insuredId), profile, cacheProperties.getRisk());
    }

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
        snapshot.put("ko_rate_last_15m", koRate);
        snapshot.put("generated_at", Instant.now().toString());

        redisCacheService.setJson(
                CacheKeys.liveStatsKey(now.toLocalDate().toString()),
                snapshot,
                cacheProperties.getLiveStats());
    }

    public long countEventsLastMinutes(int minutes) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return sumCounters(lastMinuteKeys(now, Math.max(1, minutes)), CacheKeys::eventsMinuteKey);
    }

    private long countActiveSessions() {
        return Optional.ofNullable(redisTemplate.keys("session:*"))
                .map(set -> set.stream().filter(key -> !key.startsWith("session:analysis:") && !key.startsWith("session:insight:")).count())
                .orElse(0L);
    }

    private List<String> lastMinuteKeys(LocalDateTime now, int minutes) {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < minutes; index++) {
            keys.add(now.minusMinutes(index).format(MINUTE_FORMAT));
        }
        return keys;
    }

    private long sumCounters(List<String> minuteKeys, java.util.function.Function<String, String> keyFn) {
        long sum = 0L;
        for (String minute : minuteKeys) {
            String value = redisTemplate.opsForValue().get(keyFn.apply(minute));
            if (value == null) {
                continue;
            }
            try {
                sum += Long.parseLong(value);
            } catch (NumberFormatException ignored) {
            }
        }
        return sum;
    }

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

    private Map<String, Long> topN(Map<String, Long> counts, int limit) {
        Map<String, Long> ordered = new java.util.LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

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

    private boolean isHighRiskType(String type) {
        for (String highRisk : riskProperties.getHighRiskTypes()) {
            if (highRisk.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }
}
