package com.neo.dashboard.repository;

import com.neo.dashboard.entity.NextActionPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for looking up the latest next-action prediction snapshot for an
 * insured user.
 */
@Repository
public interface NextActionPredictionRepository extends JpaRepository<NextActionPrediction, Long> {
    /* Return the prediction row associated with the insured id, when available. */
    Optional<NextActionPrediction> findByInsuredId(String insuredId);
}
