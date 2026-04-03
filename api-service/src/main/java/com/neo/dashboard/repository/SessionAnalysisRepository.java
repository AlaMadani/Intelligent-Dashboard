package com.neo.dashboard.repository;

import com.neo.dashboard.entity.SessionAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for read-only session analysis queries exposed through the API.
 */
@Repository
public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, Long> {

    /* Apply optional filters for insured id, time window, and anomaly flag. */
    @Query("""
            SELECT s
            FROM SessionAnalysis s
            WHERE (:insuredId IS NULL OR s.insuredId = :insuredId)
              AND (:fromTime IS NULL OR s.startTime >= :fromTime)
              AND (:toTime IS NULL OR s.endTime <= :toTime)
              AND (:isAnomaly IS NULL OR s.isAnomaly = :isAnomaly)
            """)
    Page<SessionAnalysis> search(@Param("insuredId") String insuredId,
                                 @Param("fromTime") Instant fromTime,
                                 @Param("toTime") Instant toTime,
                                 @Param("isAnomaly") Boolean isAnomaly,
                                 Pageable pageable);

    /* Load the freshest snapshot for one insured/session pair. */
    Optional<SessionAnalysis> findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(String insuredId, String sessionId);
}
