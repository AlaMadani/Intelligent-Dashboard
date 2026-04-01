package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    List<AnomalyEvent> findTop100ByInsuredIdOrderByDetectedAtDesc(String insuredId);
}
