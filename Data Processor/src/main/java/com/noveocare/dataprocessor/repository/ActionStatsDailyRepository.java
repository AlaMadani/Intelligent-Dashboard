package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.ActionStatsDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Access layer for daily action-volume history and trend-model inputs.
 */
public interface ActionStatsDailyRepository extends JpaRepository<ActionStatsDaily, Long> {
    // Lookup the exact day/action pair so jobs can upsert actual and predicted counts.
    Optional<ActionStatsDaily> findByActionIdAndStatDate(Integer actionId, LocalDate statDate);
    // Fetch recent history when a caller only needs a small trailing window.
    List<ActionStatsDaily> findTop31ByActionIdOrderByStatDateDesc(Integer actionId);
    // Return ordered history slices for rolling mean and standard-deviation calculations.
    List<ActionStatsDaily> findByActionIdAndStatDateBetweenOrderByStatDateAsc(Integer actionId, LocalDate start, LocalDate end);
}
