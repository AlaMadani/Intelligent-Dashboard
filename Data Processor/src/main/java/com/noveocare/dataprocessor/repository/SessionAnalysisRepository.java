package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.SessionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Access layer for persisted session-analysis summaries.
 */
public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, Long> {
    // Return recent sessions used to recompute rolling risk metrics.
    List<SessionAnalysis> findByInsuredIdAndEndTimeAfterOrderByEndTimeDesc(String insuredId, Instant after);
    // Return a bounded recent history to compute clean-session streaks.
    List<SessionAnalysis> findTop200ByInsuredIdOrderByEndTimeDesc(String insuredId);
}
