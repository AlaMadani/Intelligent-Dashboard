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

@Service
@RequiredArgsConstructor
public class SessionAnalysisService {

    private final SessionAnalysisRepository repository;

    @Transactional(readOnly = true)
    public Page<SessionAnalysis> search(String insuredId,
                                        Instant fromTime,
                                        Instant toTime,
                                        Boolean isAnomaly,
                                        String actionSignature,
                                        Pageable pageable) {
        return repository.search(insuredId, fromTime, toTime, isAnomaly, actionSignature, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<SessionAnalysis> getById(Long id) {
        return repository.findById(id);
    }
}