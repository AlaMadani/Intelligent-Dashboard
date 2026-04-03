package com.neo.dashboard.repository;

import com.neo.dashboard.entity.ActionStatsDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for daily per-action aggregates used when stats fall back to SQL.
 */
@Repository
public interface ActionStatsDailyRepository extends JpaRepository<ActionStatsDaily, Long> {
    /* Fetch all action aggregates for the requested business date. */
    List<ActionStatsDaily> findByStatDate(LocalDate statDate);
}
