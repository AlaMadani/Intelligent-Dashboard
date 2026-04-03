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

/**
 * Reads session analysis rows and converts JSON-heavy database columns into
 * DTO fields that are easier for the API client to consume.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionAnalysisService {

    /* Repository access plus JSON parsing support for denormalized columns. */
    private final SessionAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    /* Execute the paginated search and map each entity into its API projection. */
    @Transactional(readOnly = true)
    public Page<SessionAnalysisDto> search(String insuredId,
                                           Instant fromTime,
                                           Instant toTime,
                                           Boolean isAnomaly,
                                           Pageable pageable) {
        return repository.search(insuredId, fromTime, toTime, isAnomaly, pageable)
                .map(this::toDto);
    }

    /* Fetch one session analysis record by primary key. */
    @Transactional(readOnly = true)
    public Optional<SessionAnalysisDto> getById(Long id) {
        return repository.findById(id).map(this::toDto);
    }

    /* Convert one database row into the dashboard DTO, parsing embedded JSON fields on the way. */
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

    /* Parse action counts defensively because historical rows may store integer-like values in different numeric shapes. */
    private Map<String, Long> parseActionCounts(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            // Fast path for the expected "string -> long" JSON shape.
            return objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {});
        } catch (Exception first) {
            try {
                // Fallback for payloads that deserialize as generic numbers instead of longs.
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

    /* Parse the predicted next actions list, returning an empty list on malformed input. */
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
