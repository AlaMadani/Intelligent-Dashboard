package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Access layer for persisted anomaly-event history.
 */
public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    // Return the most recent anomaly events for one insured user.
    List<AnomalyEvent> findTop100ByInsuredIdOrderByDetectedAtDesc(String insuredId);
    List<AnomalyEvent> findTop100ByOrderByDetectedAtDesc();
}
