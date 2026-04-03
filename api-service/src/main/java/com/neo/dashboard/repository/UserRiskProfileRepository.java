package com.neo.dashboard.repository;

import com.neo.dashboard.entity.UserRiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for user risk profile snapshots exposed through the analytics API.
 */
@Repository
public interface UserRiskProfileRepository extends JpaRepository<UserRiskProfile, Long> {
    /* Return the current risk snapshot for the insured id, when it exists. */
    Optional<UserRiskProfile> findByInsuredId(String insuredId);
}
