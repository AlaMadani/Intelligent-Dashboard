package com.neo.dashboard.repository;

import com.neo.dashboard.entity.SecurityAlertSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SecurityAlertSequenceRepository extends JpaRepository<SecurityAlertSequence, Long> {

    /* Find the stored sequence for a specific alert id. */
    Optional<SecurityAlertSequence> findByAlert_Id(Long alertId);

    /* Get the most recent sequence for a given user. */
    Optional<SecurityAlertSequence> findTopByUserKeyOrderByCreatedAtDesc(Integer userKey);
}
