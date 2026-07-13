package com.neo.dashboard.repository;

import com.neo.dashboard.entity.NextEventPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NextEventPredictionRepository extends JpaRepository<NextEventPrediction, Long> {

    Optional<NextEventPrediction> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    Optional<NextEventPrediction> findTopByInsuredIdOrderByCreatedAtDesc(String insuredId);

    Optional<NextEventPrediction> findTopBySessionIdAndContextEventIdOrderByCreatedAtDesc(String sessionId, String contextEventId);
}
