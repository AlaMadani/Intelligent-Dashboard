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

@Repository
public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {

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

    Optional<AnomalyEvent> findTopByInsuredIdOrderByDetectedAtDesc(String insuredId);

    Optional<AnomalyEvent> findTopByEventIdOrderByDetectedAtDesc(String eventId);

    Optional<AnomalyEvent> findTopByInsuredIdAndSessionIdAndEventIdOrderByDetectedAtDesc(
            String insuredId, String sessionId, String eventId);

    List<AnomalyEvent> findTop10ByInsuredIdOrderByEventTimeDesc(String insuredId);

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

    @Query("SELECT e.anomalyType, COUNT(e) FROM AnomalyEvent e WHERE e.anomalyType IS NOT NULL GROUP BY e.anomalyType")
    List<Object[]> countByAnomalyType();
}
