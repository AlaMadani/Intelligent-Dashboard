package com.neo.dashboard.repository;

import com.neo.dashboard.entity.ActionStatsDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ActionStatsDailyRepository extends JpaRepository<ActionStatsDaily, Long> {
    List<ActionStatsDaily> findByStatDate(LocalDate statDate);
}
