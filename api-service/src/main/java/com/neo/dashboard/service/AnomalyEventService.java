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

@Service
@RequiredArgsConstructor
public class AnomalyEventService {

    private final AnomalyEventRepository repository;

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

    @Transactional(readOnly = true)
    public Optional<AnomalyEventDto> getById(Long id) {
        return repository.findById(id).map(this::toDto);
    }

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
