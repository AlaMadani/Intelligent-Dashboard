package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.SessionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SessionAnalysisRepository extends JpaRepository<SessionAnalysis, Long> {
    List<SessionAnalysis> findByInsuredIdAndEndTimeAfterOrderByEndTimeDesc(String insuredId, Instant after);
    List<SessionAnalysis> findTop200ByInsuredIdOrderByEndTimeDesc(String insuredId);
}
