package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.UserRiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Access layer for user-level risk profiles.
 */
public interface UserRiskProfileRepository extends JpaRepository<UserRiskProfile, Long> {
    /* Profiles are uniquely keyed by insured user id. */
    Optional<UserRiskProfile> findByInsuredId(String insuredId);
}
