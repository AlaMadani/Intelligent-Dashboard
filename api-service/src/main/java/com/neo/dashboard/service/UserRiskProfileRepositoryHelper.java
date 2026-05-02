package com.neo.dashboard.service;

import com.neo.dashboard.entity.UserRiskProfile;
import com.neo.dashboard.repository.UserRiskProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserRiskProfileRepositoryHelper {

    private final UserRiskProfileRepository repository;

    @Transactional(readOnly = true)
    public Page<UserRiskProfile> findAll(Pageable pageable) {
        return repository.findAllByOrderByAnomalyRate30dDesc(pageable);
    }
}