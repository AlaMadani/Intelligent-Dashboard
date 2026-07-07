package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.NextEventPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NextEventPredictionRepository extends JpaRepository<NextEventPrediction, Long> {
    Optional<NextEventPrediction> findBySessionIdAndContextEventId(String sessionId, String contextEventId);
}
