package com.neo.dashboard.service;

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
 * Serves anomaly events to the API.
 */
@Service
@RequiredArgsConstructor
public class AnomalyEventService {

    /* Read-only repository for anomaly event queries. */
    private final AnomalyEventRepository repository;

    /* Execute the filtered page query used by the anomaly event list endpoint. */
    @Transactional(readOnly = true)
    public Page<AnomalyEvent> search(String insuredId,
                                     Instant fromTime,
                                     Instant toTime,
                                     String tier,
                                     String type,
                                     Pageable pageable) {
        return repository.search(insuredId, fromTime, toTime, tier, type, pageable);
    }

    /* Fetch one anomaly event by id. */
    @Transactional(readOnly = true)
    public Optional<AnomalyEvent> getById(Long id) {
        return repository.findById(id);
    }
}
