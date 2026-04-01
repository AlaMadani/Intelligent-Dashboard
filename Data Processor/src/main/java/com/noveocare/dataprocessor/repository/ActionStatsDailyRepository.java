package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.ActionStatsDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ActionStatsDailyRepository extends JpaRepository<ActionStatsDaily, Long> {
    Optional<ActionStatsDaily> findByActionIdAndStatDate(Integer actionId, LocalDate statDate);
    List<ActionStatsDaily> findTop31ByActionIdOrderByStatDateDesc(Integer actionId);
    List<ActionStatsDaily> findByActionIdAndStatDateBetweenOrderByStatDateAsc(Integer actionId, LocalDate start, LocalDate end);
}
