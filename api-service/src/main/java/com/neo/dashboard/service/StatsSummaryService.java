package com.neo.dashboard.service;

import com.neo.dashboard.dto.StatsSummaryDto;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import com.neo.dashboard.repository.UserRiskProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsSummaryService {

    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final UserRiskProfileRepository userRiskProfileRepository;
    private final StringRedisTemplate redisTemplate;
    private final ActiveSessionService activeSessionService;

    @Transactional(readOnly = true)
    public StatsSummaryDto getSummary() {
        long totalSessions = sessionAnalysisRepository.count();
        long totalAnomalies = anomalyEventRepository.count();
        long anomalousSessions = sessionAnalysisRepository.countByIsAnomalyTrue();
        double anomalyRate = totalSessions == 0 ? 0.0 : (double) anomalousSessions / totalSessions;

        long activeSessionsNow = activeSessionService.countActive();
        long eventsToday = countEventsToday();

        Map<String, Long> anomByType = new LinkedHashMap<>();
        for (Object[] row : anomalyEventRepository.countByAnomalyType()) {
            anomByType.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> usersByTier = new LinkedHashMap<>();
        for (Object[] row : userRiskProfileRepository.countByRiskTier()) {
            usersByTier.put((String) row[0], (Long) row[1]);
        }

        Map<Integer, Long> sessionsByCluster = new LinkedHashMap<>();
        for (Object[] row : sessionAnalysisRepository.countByPersonaCluster()) {
            sessionsByCluster.put((Integer) row[0], (Long) row[1]);
        }

        return new StatsSummaryDto(
                totalSessions, totalAnomalies, anomalousSessions,
                activeSessionsNow, eventsToday, anomalyRate,
                anomByType, usersByTier, sessionsByCluster, Instant.now()
        );
    }

    private long countEventsToday() {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        String val = redisTemplate.opsForValue().get("stats:events:day:" + day);
        if (val == null || val.isBlank()) return 0;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return 0; }
    }
}