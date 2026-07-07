package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    List<AnomalyEvent> findTop100ByInsuredIdOrderByDetectedAtDesc(String insuredId);

    @Query("SELECT e FROM AnomalyEvent e ORDER BY e.detectedAt DESC")
    List<AnomalyEvent> findRecentAnomalyEvents(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT e FROM AnomalyEvent e WHERE e.detectedAt >= :from AND e.detectedAt < :to")
    List<AnomalyEvent> findByDetectedAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    boolean existsByEventIdAndV36RuntimeVersion(String eventId, String v36RuntimeVersion);
}