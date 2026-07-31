package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.NextEventPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for persisted next-event predictions.
 */
public interface NextEventPredictionRepository extends JpaRepository<NextEventPrediction, Long> {
    /* Retrieve a prediction by its unique session + context key. */
    Optional<NextEventPrediction> findBySessionIdAndContextEventId(String sessionId, String contextEventId);
}
