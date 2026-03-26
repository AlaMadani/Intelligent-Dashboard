package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.SecurityAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecurityAlertRepository extends JpaRepository<SecurityAlert, Long> {

    // User-specific alert history (most recent first).
    List<SecurityAlert> findByUserKeyOrderByDetectedAtDesc(Integer userKey);

    // Global dashboard view of most recent alerts.
    List<SecurityAlert> findTop50ByOrderByDetectedAtDesc();
}
