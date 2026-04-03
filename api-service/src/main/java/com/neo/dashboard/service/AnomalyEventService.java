package com.neo.dashboard.service;

import com.neo.dashboard.dto.AnomalyEventDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.repository.AnomalyEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Serves anomaly events to the API and keeps the DTO mapping isolated from
 * controller code.
 */
@Service
@RequiredArgsConstructor
public class AnomalyEventService {

    /* Read-only repository for anomaly event queries. */
    private final AnomalyEventRepository repository;

    /* Execute the filtered page query used by the anomaly event list endpoint. */
    @Transactional(readOnly = true)
    public Page<AnomalyEventDto> search(String insuredId,
                                        Instant fromTime,
                                        Instant toTime,
                                        String tier,
                                        String type,
                                        Pageable pageable) {
        return repository.search(insuredId, fromTime, toTime, tier, type, pageable)
                .map(this::toDto);
    }

    /* Fetch one anomaly event by id. */
    @Transactional(readOnly = true)
    public Optional<AnomalyEventDto> getById(Long id) {
        return repository.findById(id).map(this::toDto);
    }

    /* Flatten the entity into the DTO expected by API responses and SSE clients. */
    AnomalyEventDto toDto(AnomalyEvent entity) {
        return new AnomalyEventDto(
                entity.getId(),
                entity.getInsuredId(),
                entity.getSessionId(),
                entity.getEventId(),
                entity.getEventTime(),
                entity.getAnomalyTier(),
                entity.getAnomalyType(),
                entity.getAnomalyScore(),
                entity.getTypeConfidence(),
                entity.getRuleType(),
                entity.getEventJson(),
                entity.getDetectedAt()
        );
    }
}
