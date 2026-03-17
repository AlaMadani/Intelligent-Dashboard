package com.neo.dashboard.repository;

import com.neo.dashboard.entity.SecurityAlertSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SecurityAlertSequenceRepository extends JpaRepository<SecurityAlertSequence, Long> {
    Optional<SecurityAlertSequence> findByAlert_Id(Long alertId);
    Optional<SecurityAlertSequence> findTopByUserKeyOrderByCreatedAtDesc(Integer userKey);
}
