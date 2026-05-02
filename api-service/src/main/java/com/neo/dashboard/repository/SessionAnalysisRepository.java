package com.neo.dashboard.repository;

import com.neo.dashboard.entity.SessionAnalysis;
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
public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, Long> {

    @Query(value = """
            SELECT s
            FROM SessionAnalysis s
            WHERE (:insuredId IS NULL OR s.insuredId = :insuredId)
              AND (:fromTime IS NULL OR s.startTime >= :fromTime)
              AND (:toTime IS NULL OR s.endTime <= :toTime)
              AND (:isAnomaly IS NULL OR s.isAnomaly = :isAnomaly)
              AND (:actionSignature IS NULL OR s.actionSequenceSignature = :actionSignature)
            """,
           countQuery = """
            SELECT COUNT_BIG(s.id)
            FROM SessionAnalysis s
            WHERE (:insuredId IS NULL OR s.insuredId = :insuredId)
              AND (:fromTime IS NULL OR s.startTime >= :fromTime)
              AND (:toTime IS NULL OR s.endTime <= :toTime)
              AND (:isAnomaly IS NULL OR s.isAnomaly = :isAnomaly)
              AND (:actionSignature IS NULL OR s.actionSequenceSignature = :actionSignature)
            """)
    Page<SessionAnalysis> search(@Param("insuredId") String insuredId,
                                 @Param("fromTime") Instant fromTime,
                                 @Param("toTime") Instant toTime,
                                 @Param("isAnomaly") Boolean isAnomaly,
                                 @Param("actionSignature") String actionSignature,
                                 Pageable pageable);

    Optional<SessionAnalysis> findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(String insuredId, String sessionId);

    List<SessionAnalysis> findTop10ByInsuredIdOrderByEndTimeDesc(String insuredId);

    long countByIsAnomalyTrue();

    @Query("SELECT s.anomalyType, COUNT(s) FROM SessionAnalysis s WHERE s.anomalyType IS NOT NULL GROUP BY s.anomalyType")
    List<Object[]> countByAnomalyType();

    @Query("SELECT s.personaCluster, COUNT(s) FROM SessionAnalysis s WHERE s.personaCluster IS NOT NULL GROUP BY s.personaCluster")
    List<Object[]> countByPersonaCluster();
}