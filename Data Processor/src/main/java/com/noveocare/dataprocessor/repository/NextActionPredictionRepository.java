package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.NextActionPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NextActionPredictionRepository extends JpaRepository<NextActionPrediction, Long> {
    Optional<NextActionPrediction> findByInsuredId(String insuredId);
}
