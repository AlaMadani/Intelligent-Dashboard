package com.neo.dashboard.service;

import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Reads session analysis rows with filtering and pagination.
 */
@Service
@RequiredArgsConstructor
public class SessionAnalysisService {

    /* Read-only repository used by session endpoints. */
    private final SessionAnalysisRepository repository;

    /* Execute the paginated search. */
    @Transactional(readOnly = true)
    public Page<SessionAnalysis> search(String insuredId,
                                        Instant fromTime,
                                        Instant toTime,
                                        Boolean isAnomaly,
                                        Pageable pageable) {
        return repository.search(insuredId, fromTime, toTime, isAnomaly, pageable);
    }

    /* Fetch one session analysis record by primary key. */
    @Transactional(readOnly = true)
    public Optional<SessionAnalysis> getById(Long id) {
        return repository.findById(id);
    }
}
