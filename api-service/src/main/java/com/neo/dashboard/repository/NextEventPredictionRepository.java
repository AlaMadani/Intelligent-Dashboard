package com.neo.dashboard.repository;

import com.neo.dashboard.entity.NextEventPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link NextEventPrediction} entities.
 * Stores LLM-generated predictions about the most likely next anomalous event
 * in a session, used for proactive alerting and preemptive responses.
 */
@Repository
public interface NextEventPredictionRepository extends JpaRepository<NextEventPrediction, Long> {

    /** Returns the latest prediction for a given session. */
    Optional<NextEventPrediction> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** Returns the latest prediction across all sessions for a given insured person. */
    Optional<NextEventPrediction> findTopByInsuredIdOrderByCreatedAtDesc(String insuredId);

    /** Returns the latest prediction for a specific session that was triggered by a given context event. */
    Optional<NextEventPrediction> findTopBySessionIdAndContextEventIdOrderByCreatedAtDesc(String sessionId, String contextEventId);
}
