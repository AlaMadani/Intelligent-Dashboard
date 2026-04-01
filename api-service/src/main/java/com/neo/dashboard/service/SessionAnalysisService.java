package com.neo.dashboard.service;

import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionAnalysisService {

    private final SessionAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<SessionAnalysisDto> search(String insuredId,
                                           Instant fromTime,
                                           Instant toTime,
                                           Boolean isAnomaly,
                                           Pageable pageable) {
        return repository.search(insuredId, fromTime, toTime, isAnomaly, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<SessionAnalysisDto> getById(Long id) {
        return repository.findById(id).map(this::toDto);
    }

    SessionAnalysisDto toDto(SessionAnalysis entity) {
        return new SessionAnalysisDto(
                entity.getId(),
                entity.getInsuredId(),
                entity.getSessionId(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getSessionLength(),
                entity.getSessionDurationSeconds(),
                entity.getUniqueActionCount(),
                entity.getKoRate(),
                entity.getMeanDeltaSeconds(),
                entity.getActionDiversity(),
                parseActionCounts(entity.getActionCountsJson()),
                entity.getAeScore(),
                entity.getIsAnomaly(),
                entity.getAnomalyType(),
                entity.getTypeConfidence(),
                parseTop3Actions(entity.getTop3NextActions()),
                entity.getRuleTriggered(),
                entity.getRuleType(),
                entity.getCreatedAt()
        );
    }

    private Map<String, Long> parseActionCounts(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {});
        } catch (Exception first) {
            try {
                Map<String, Number> raw = objectMapper.readValue(json, new TypeReference<Map<String, Number>>() {});
                if (raw == null || raw.isEmpty()) {
                    return Collections.emptyMap();
                }
                return raw.entrySet().stream()
                        .filter(entry -> entry.getValue() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().longValue()
                        ));
            } catch (Exception second) {
                log.warn("Failed to parse action_counts_json", second);
                return Collections.emptyMap();
            }
        }
    }

    private List<String> parseTop3Actions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse top3_next_actions JSON", e);
            return Collections.emptyList();
        }
    }
}
