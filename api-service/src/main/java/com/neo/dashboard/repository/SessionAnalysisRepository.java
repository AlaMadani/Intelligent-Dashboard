package com.neo.dashboard.repository;

import com.neo.dashboard.entity.SessionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, Long> {

    Optional<SessionAnalysis> findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(String insuredId, String sessionId);

    Optional<SessionAnalysis> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<SessionAnalysis> findTop10ByInsuredIdOrderByEndTimeDesc(String insuredId);

    List<SessionAnalysis> findTop50ByOrderByCreatedAtDesc();

    List<SessionAnalysis> findTop50ByChurnRiskLevelOrderByCreatedAtDesc(String churnRiskLevel);

    @Query("SELECT s.personaCluster, COUNT(s) FROM SessionAnalysis s WHERE s.personaCluster IS NOT NULL GROUP BY s.personaCluster")
    List<Object[]> countByPersonaCluster();

    @Query("SELECT s.personaLabel, COUNT(s) FROM SessionAnalysis s WHERE s.personaLabel IS NOT NULL GROUP BY s.personaLabel")
    List<Object[]> countByPersonaLabel();
}