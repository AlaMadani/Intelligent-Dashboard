package com.neo.dashboard.repository;

import com.neo.dashboard.entity.NextActionPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NextActionPredictionRepository extends JpaRepository<NextActionPrediction, Long> {
    Optional<NextActionPrediction> findByInsuredId(String insuredId);
}
