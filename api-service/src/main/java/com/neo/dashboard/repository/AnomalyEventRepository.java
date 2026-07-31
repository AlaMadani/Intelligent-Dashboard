package com.neo.dashboard.repository;

import com.neo.dashboard.entity.AnomalyEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for accessing and querying {@link AnomalyEvent} entities.
 * Provides filtered search, recent-lookup, and aggregation methods for anomaly alerts
 * generated during insurance-session monitoring.
 */
@Repository
public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {

    /**
     * Multi-criteria paginated search of anomaly events.
     * Every filter parameter is optional — when {@code null} the corresponding
     * WHERE clause is omitted, allowing callers to search with any combination
     * of insured, time range, tier, and type.
     *
     * @param insuredId optional identifier of the insured person
     * @param fromTime  optional start of the event time window (inclusive)
     * @param toTime    optional end of the event time window (inclusive)
     * @param tier      optional anomaly severity tier
     * @param type      optional anomaly type classification
     * @param pageable  pagination and sorting specification
     * @return a page of matching anomaly events
     */
    @Query(value = """
            SELECT e
            FROM AnomalyEvent e
            WHERE (:insuredId IS NULL OR e.insuredId = :insuredId)
              AND (:fromTime IS NULL OR e.eventTime >= :fromTime)
              AND (:toTime IS NULL OR e.eventTime <= :toTime)
              AND (:tier IS NULL OR e.anomalyTier = :tier)
              AND (:type IS NULL OR e.anomalyType = :type)
            """,
           countQuery = """
            SELECT COUNT_BIG(e.id)
            FROM AnomalyEvent e
            WHERE (:insuredId IS NULL OR e.insuredId = :insuredId)
              AND (:fromTime IS NULL OR e.eventTime >= :fromTime)
              AND (:toTime IS NULL OR e.eventTime <= :toTime)
              AND (:tier IS NULL OR e.anomalyTier = :tier)
              AND (:type IS NULL OR e.anomalyType = :type)
            """)
    Page<AnomalyEvent> search(@Param("insuredId") String insuredId,
                              @Param("fromTime") Instant fromTime,
                              @Param("toTime") Instant toTime,
                              @Param("tier") String tier,
                              @Param("type") String type,
                              Pageable pageable);

    /** Returns the most recently detected anomaly for a given insured person. */
    Optional<AnomalyEvent> findTopByInsuredIdOrderByDetectedAtDesc(String insuredId);

    /** Returns the most recently detected anomaly for a given external event ID. */
    Optional<AnomalyEvent> findTopByEventIdOrderByDetectedAtDesc(String eventId);

    /**
     * Returns the most recently detected anomaly matching the unique combination
     * of insured, session, and event identifiers — used to avoid duplicate processing.
     */
    Optional<AnomalyEvent> findTopByInsuredIdAndSessionIdAndEventIdOrderByDetectedAtDesc(
            String insuredId, String sessionId, String eventId);

    /** Returns the ten most recent anomalies for a given insured, ordered by event time descending. */
    List<AnomalyEvent> findTop10ByInsuredIdOrderByEventTimeDesc(String insuredId);

    /**
     * Paginated search mirroring the V36 alerts dashboard filtering.
     * Supports combined filtering by risk level (matching {@code riskLevel} or
     * {@code anomalyTier}), anomaly type, insured, session, and a time window.
     *
     * @param riskLevel   optional risk-level or anomaly-tier value to match
     * @param anomalyType optional anomaly type classification
     * @param insuredId   optional insured identifier
     * @param sessionId   optional session identifier
     * @param fromTime    optional start of the event time window
     * @param toTime      optional end of the event time window
     * @param pageable    pagination and sorting specification
     * @return a page of matching anomaly events
     */
    @Query(value = """
            SELECT e
            FROM AnomalyEvent e
            WHERE (:riskLevel IS NULL OR e.riskLevel = :riskLevel OR e.anomalyTier = :riskLevel)
              AND (:anomalyType IS NULL OR e.anomalyType = :anomalyType)
              AND (:insuredId IS NULL OR e.insuredId = :insuredId)
              AND (:sessionId IS NULL OR e.sessionId = :sessionId)
              AND (:fromTime IS NULL OR e.eventTime >= :fromTime)
              AND (:toTime IS NULL OR e.eventTime <= :toTime)
            """,
           countQuery = """
            SELECT COUNT_BIG(e.id)
            FROM AnomalyEvent e
            WHERE (:riskLevel IS NULL OR e.riskLevel = :riskLevel OR e.anomalyTier = :riskLevel)
              AND (:anomalyType IS NULL OR e.anomalyType = :anomalyType)
              AND (:insuredId IS NULL OR e.insuredId = :insuredId)
              AND (:sessionId IS NULL OR e.sessionId = :sessionId)
              AND (:fromTime IS NULL OR e.eventTime >= :fromTime)
              AND (:toTime IS NULL OR e.eventTime <= :toTime)
            """)
    Page<AnomalyEvent> searchV36Alerts(@Param("riskLevel") String riskLevel,
                                       @Param("anomalyType") String anomalyType,
                                       @Param("insuredId") String insuredId,
                                       @Param("sessionId") String sessionId,
                                       @Param("fromTime") Instant fromTime,
                                       @Param("toTime") Instant toTime,
                                       Pageable pageable);

    /** Bulk-fetches anomaly events by a list of external event IDs. */
    @Query("SELECT e FROM AnomalyEvent e WHERE e.eventId IN :eventIds")
    List<AnomalyEvent> findByEventIdIn(@Param("eventIds") List<String> eventIds);

    /** Returns a count of anomaly events grouped by their type — used for pie-chart / distribution widgets. */
    @Query("SELECT e.anomalyType, COUNT(e) FROM AnomalyEvent e WHERE e.anomalyType IS NOT NULL GROUP BY e.anomalyType")
    List<Object[]> countByAnomalyType();

    /** Same as {@link #countByAnomalyType()} but only considers events on or after the given timestamp. */
    @Query("SELECT e.anomalyType, COUNT(e) FROM AnomalyEvent e WHERE e.anomalyType IS NOT NULL AND e.eventTime >= :fromTime GROUP BY e.anomalyType")
    List<Object[]> countByAnomalyTypeSince(@Param("fromTime") Instant fromTime);
}
