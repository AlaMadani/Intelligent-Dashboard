package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.NextActionPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Access layer for the latest next-action prediction snapshot per user.
 */
public interface NextActionPredictionRepository extends JpaRepository<NextActionPrediction, Long> {
    // Each insured user keeps at most one latest prediction record.
    Optional<NextActionPrediction> findByInsuredId(String insuredId);
}
