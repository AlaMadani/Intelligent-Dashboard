package com.neo.dashboard.repository;

import com.neo.dashboard.entity.SessionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link SessionAnalysis} entities.
 * Session analyses aggregate anomaly-detection results and computed risk
 * indicators for an entire insurance-submission session, including churn
 * prediction and persona classification.
 */
@Repository
public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, Long> {

    /** Returns the latest analysis for a specific insured-session pair. */
    Optional<SessionAnalysis> findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(String insuredId, String sessionId);

    /** Returns the latest analysis for a given session regardless of insured. */
    Optional<SessionAnalysis> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** Returns the ten most recent analyses for a given insured, ordered by session end time. */
    List<SessionAnalysis> findTop10ByInsuredIdOrderByEndTimeDesc(String insuredId);

    /** Returns the fifty most recent analyses across all sessions — used for the global dashboard feed. */
    List<SessionAnalysis> findTop50ByOrderByCreatedAtDesc();

    /** Returns the fifty most recent analyses filtered to a specific churn-risk level. */
    List<SessionAnalysis> findTop50ByChurnRiskLevelOrderByCreatedAtDesc(String churnRiskLevel);

    /** Aggregates session counts per persona cluster — used for persona-distribution charts. */
    @Query("SELECT s.personaCluster, COUNT(s) FROM SessionAnalysis s WHERE s.personaCluster IS NOT NULL GROUP BY s.personaCluster")
    List<Object[]> countByPersonaCluster();

    /** Aggregates session counts per persona label — more granular than cluster counts. */
    @Query("SELECT s.personaLabel, COUNT(s) FROM SessionAnalysis s WHERE s.personaLabel IS NOT NULL GROUP BY s.personaLabel")
    List<Object[]> countByPersonaLabel();
}