package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for persisting and querying anomaly alert events.
 */
public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    /* Retrieve the 100 most recent alerts for a given insured user. */
    List<AnomalyEvent> findTop100ByInsuredIdOrderByDetectedAtDesc(String insuredId);

    /* Pageable query returning alerts ordered by detection time. */
    @Query("SELECT e FROM AnomalyEvent e ORDER BY e.detectedAt DESC")
    List<AnomalyEvent> findRecentAnomalyEvents(org.springframework.data.domain.Pageable pageable);

    /* Look up alerts within a specific detection-time window. */
    @Query("SELECT e FROM AnomalyEvent e WHERE e.detectedAt >= :from AND e.detectedAt < :to")
    List<AnomalyEvent> findByDetectedAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    /* Deduplication check used before inserting a new alert. */
    boolean existsByEventIdAndV36RuntimeVersion(String eventId, String v36RuntimeVersion);
}