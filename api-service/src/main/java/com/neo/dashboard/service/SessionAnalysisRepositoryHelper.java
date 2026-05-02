package com.neo.dashboard.service;

import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionAnalysisRepositoryHelper {

    private final SessionAnalysisRepository repository;

    @Transactional(readOnly = true)
    public List<SessionAnalysis> findTop10ByInsuredId(String insuredId) {
        return repository.findTop10ByInsuredIdOrderByEndTimeDesc(insuredId);
    }
}