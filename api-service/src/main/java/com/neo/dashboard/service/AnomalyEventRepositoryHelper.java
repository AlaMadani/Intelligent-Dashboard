package com.neo.dashboard.service;

import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.repository.AnomalyEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnomalyEventRepositoryHelper {

    private final AnomalyEventRepository repository;

    @Transactional(readOnly = true)
    public List<AnomalyEvent> findTop10ByInsuredId(String insuredId) {
        return repository.findTop10ByInsuredIdOrderByEventTimeDesc(insuredId);
    }
}