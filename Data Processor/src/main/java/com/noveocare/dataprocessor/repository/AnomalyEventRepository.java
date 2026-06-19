package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    List<AnomalyEvent> findTop100ByInsuredIdOrderByDetectedAtDesc(String insuredId);

    @org.springframework.data.jpa.repository.Query("SELECT e FROM AnomalyEvent e ORDER BY e.detectedAt DESC")
    List<AnomalyEvent> findRecentAnomalyEvents(org.springframework.data.domain.Pageable pageable);

    boolean existsByEventIdAndV36RuntimeVersion(String eventId, String v36RuntimeVersion);
}