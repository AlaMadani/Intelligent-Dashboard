package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.SessionAnalysis;
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
 * Repository for read-only session analysis queries exposed through the API.
 */
@Repository
public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, Long> {
    // Return recent sessions used to recompute rolling risk metrics.
    List<SessionAnalysis> findByInsuredIdAndEndTimeAfterOrderByEndTimeDesc(String insuredId, Instant after);
    // Return a bounded recent history to compute clean-session streaks.
    List<SessionAnalysis> findTop200ByInsuredIdOrderByEndTimeDesc(String insuredId);
    // Return the latest finalized sessions for dashboard cards.
    List<SessionAnalysis> findTop50ByOrderByCreatedAtDesc();
    // Return the highest-risk finalized sessions for the dashboard.
    List<SessionAnalysis> findTop20ByOrderByFinalRiskScoreDescCreatedAtDesc();
    Optional<SessionAnalysis> findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(String insuredId, String sessionId);
}
